use super::*;
use serde_json::json;

fn paint(e:&mut Engine,x:f32,y:f32,color:u32,size:f32){
    e.begin(Sample{x,y,pressure:0.8,tilt:0.0},Brush{size,color,stabilization:0.0,..Brush::default()});
    for i in 1..=24 {e.sample(Sample{x:x+i as f32*9.0,y:y+(i as f32*0.4).sin()*20.0,pressure:0.5,tilt:0.0});}
    e.end();
}
/// Canonical (legacy JSON) snapshot of the full document, used to compare pixel-exactly.
fn canonical(e:&mut Engine)->Vec<u8>{e.save().unwrap()}
fn roundtrip(e:&mut Engine)->Engine{
    let bytes=e.save_compressed().unwrap();
    assert!(bytes.starts_with(binfmt::MAGIC));
    Engine::load(&bytes).unwrap()
}

#[test] fn binary_document_roundtrips_layers_masks_groups_and_exterior(){
    let mut e=Engine::new(700,530).unwrap();
    paint(&mut e,20.,40.,0xff2050c0,30.);
    e.command(&json!({"op":"add"})).unwrap();paint(&mut e,-30.,300.,0xffc04020,40.); // off-canvas ink
    e.command(&json!({"op":"add"})).unwrap(); // stays empty
    e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"fill","color":0xff336699u32})).unwrap(); // solid tiles
    e.command(&json!({"op":"duplicate","index":0})).unwrap(); // shared pixels → alias
    e.command(&json!({"op":"mask","action":"add"})).ok();
    e.command(&json!({"op":"layer","index":1,"opacity":0.4,"blend":"Multiply","name":"Ink"})).unwrap();
    assert!(e.state.layers.iter().any(|l|l.mask.is_some()),"fixture needs a mask");
    assert!(e.state.layers.iter().any(|l|!l.exterior.is_empty()),"fixture needs off-canvas ink");
    assert!(e.state.layers.iter().any(|l|(l.pixels).is_blank()),"fixture needs an empty layer");
    let expected=canonical(&mut e);
    let mut loaded=roundtrip(&mut e);
    assert_eq!(canonical(&mut loaded),expected);
    // Shared pixel buffers survive the round trip (duplicate layer keeps one allocation).
}

#[test] fn incremental_save_tracks_in_place_edits_from_undo_redo(){
    let mut e=Engine::new(600,600).unwrap();
    for i in 0..6 {paint(&mut e,30.,30.+i as f32*80.,0xff000000|(i*40000+1234),24.);}
    let _=e.save_compressed().unwrap(); // primes the tile cache
    // Undo/redo mutate the existing buffer in place, so pointer identity alone would miss them.
    for _ in 0..3 {assert!(e.undo());}
    let mut a=roundtrip(&mut e);assert_eq!(canonical(&mut a),canonical(&mut e));
    assert!(e.redo());
    let mut b=roundtrip(&mut e);assert_eq!(canonical(&mut b),canonical(&mut e));
    paint(&mut e,100.,500.,0xff00ff00,60.);
    let mut c=roundtrip(&mut e);assert_eq!(canonical(&mut c),canonical(&mut e));
    // A loaded document primes its own cache; editing and saving again stays exact.
    paint(&mut c,300.,100.,0xffff00ff,50.);
    let mut d=roundtrip(&mut c);assert_eq!(canonical(&mut d),canonical(&mut c));
}

#[test] fn legacy_json_and_gzip_documents_still_open(){
    let mut e=Engine::new(256,200).unwrap();paint(&mut e,10.,50.,0xff884422,20.);
    let json=canonical(&mut e);
    let mut gz=flate2::write::GzEncoder::new(Vec::new(),flate2::Compression::fast());
    std::io::Write::write_all(&mut gz,&json).unwrap();let gz=gz.finish().unwrap();
    assert_eq!(canonical(&mut Engine::load(&json).unwrap()),json);
    assert_eq!(canonical(&mut Engine::load(&gz).unwrap()),json);
}

#[test] fn corrupt_binary_documents_are_rejected(){
    let mut e=Engine::new(300,300).unwrap();paint(&mut e,10.,50.,0xff884422,20.);
    let bytes=e.save_compressed().unwrap();
    for cut in [9,20,bytes.len()/2,bytes.len()-1]{assert!(Engine::load(&bytes[..cut]).is_err(),"truncated at {cut}");}
    let mut extra=bytes.clone();extra.push(0);assert!(Engine::load(&extra).is_err());
}

#[test] fn adjustment_preview_reuses_one_buffer_and_commits_like_direct_adjust(){
    let mut e=Engine::new(320,240).unwrap();paint(&mut e,10.,60.,0xff406080,40.);
    let mut direct=Engine::load(&e.save_compressed().unwrap()).unwrap();
    direct.command(&json!({"op":"adjust","kind":"Brightness","amount":0.3})).unwrap();
    for i in 0..8 {e.command(&json!({"op":"adjustPreview","kind":"Brightness","amount":i as f64*0.05})).unwrap();}
    let working=e.state.layers[0].pixels.allocated_bytes();
    e.command(&json!({"op":"adjustPreview","kind":"Brightness","amount":0.3})).unwrap();
    assert_eq!(e.state.layers[0].pixels.allocated_bytes(),working,"preview ticks should keep one working buffer's tiles");
    e.command(&json!({"op":"adjustCommit"})).unwrap();
    assert_eq!(e.state.layers[0].pixels,direct.state.layers[0].pixels);
    assert!(e.undo());
    let mut original=Engine::new(320,240).unwrap();paint(&mut original,10.,60.,0xff406080,40.);
    assert_eq!(e.state.layers[0].pixels,original.state.layers[0].pixels);
}

#[test] fn adjustment_layer_slider_drag_is_one_undo_step(){
    let mut e=Engine::new(64,64).unwrap();
    e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"layer","name":"Brightness adjustment","adjustmentKind":"Brightness","amount":0})).unwrap();
    let steps=e.undo.len();
    for i in 1..=30 {e.command(&json!({"op":"layer","adjustmentKind":"Brightness","amount":i as f64/40.0})).unwrap();}
    assert_eq!(e.undo.len(),steps+1);
    assert_eq!(e.state.layers[1].adjustment.as_ref().unwrap().1,0.75);
    assert!(e.undo());assert_eq!(e.state.layers[1].adjustment.as_ref().unwrap().1,0.0);
    assert!(e.redo());assert_eq!(e.state.layers[1].adjustment.as_ref().unwrap().1,0.75);
    // A new drag after another edit starts a new step.
    paint(&mut e,5.,5.,0xff000000,8.);
    e.command(&json!({"op":"layer","index":1,"adjustmentKind":"Brightness","amount":0.1})).unwrap();
    e.command(&json!({"op":"layer","index":1,"adjustmentKind":"Brightness","amount":0.2})).unwrap();
    assert_eq!(e.undo.len(),steps+3);
}

#[test]
fn streamed_save_matches_the_in_memory_document(){
    let mut e=Engine::new(300,200).unwrap();
    e.begin(Sample{x:40.,y:40.,pressure:1.,tilt:0.},Brush{size:60.,stabilization:0.,..Brush::default()});e.sample(Sample{x:260.,y:160.,pressure:1.,tilt:0.});e.end();
    e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"add"})).unwrap();
    e.begin(Sample{x:250.,y:30.,pressure:1.,tilt:0.},Brush{size:40.,color:0xff20a040,stabilization:0.,..Brush::default()});e.sample(Sample{x:30.,y:180.,pressure:1.,tilt:0.});e.end();
    let bytes=e.save_compressed().unwrap();
    let mut streamed=Vec::new();e.save_compressed_to(&mut streamed).unwrap();
    assert_eq!(streamed,bytes);
    let reopened=Engine::load(&streamed).unwrap();
    for (a,b) in reopened.state.layers.iter().zip(&e.state.layers){assert!(a.pixels.to_vec()==b.pixels.to_vec());}
}
