use super::*;

fn outside(e:&mut Engine,x:f32,y:f32){
    e.begin(Sample{x,y,pressure:1.,tilt:0.},Brush{size:8.,pressure_size:0.,stabilization:0.,..Brush::default()});e.end();
}
fn rect_copy(e:&mut Engine){
    e.command(&json!({"op":"select","shape":"Rectangle","points":[[20,20],[40,40]]})).unwrap();
    e.command(&json!({"op":"selectionFill","color":0xffff0000u32})).unwrap();
    e.command(&json!({"op":"selectionCopy"})).unwrap();
}
#[test]
fn audit_paste_does_not_duplicate_unselected_exterior(){
    let mut e=Engine::new(128,128).unwrap();outside(&mut e,-20.,40.);rect_copy(&mut e);
    e.command(&json!({"op":"selectionPaste"})).unwrap();assert!(e.state.layers[e.state.active].exterior.is_empty(),"Paste cloned the source layer's unselected exterior");
}
#[test]
fn audit_paste_rejects_exhausted_ids_without_panicking(){
    let mut e=Engine::new(64,64).unwrap();rect_copy(&mut e);e.next_id=u32::MAX;
    let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||e.command(&json!({"op":"selectionPaste"}))));
    assert!(matches!(result,Ok(Err(_))),"Paste overflowed the layer identifier counter");
}
#[test]
fn audit_group_ids_do_not_saturate_into_duplicate_ids(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"group","action":"create","empty":true})).unwrap();e.state.groups[0].id=u32::MAX;
    assert!(e.command(&json!({"op":"group","action":"create","empty":true})).is_err(),"Group creation reused the maximum identifier");
}
#[test]
fn audit_merge_rejects_group_boundary_appearance_loss(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"fill","color":0xff0000ffu32})).unwrap();
    e.command(&json!({"op":"group","action":"create"})).unwrap();e.command(&json!({"op":"group","action":"update","id":1,"opacity":0.25})).unwrap();
    let before=e.save().unwrap();assert!(e.command(&json!({"op":"merge"})).is_err(),"Merge discarded the upper group's opacity");assert_eq!(e.save().unwrap(),before);
}
#[test]
fn audit_merge_cannot_create_an_unloadable_exterior(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"add"})).unwrap();
    let mut tile=vec![0;128*128];tile[0]=0xff112233;let tile=Arc::new(tile);
    for i in 0..400{let layer=i/200;let x=-1-(i%16) as i32;let y=(i/16) as i32;e.state.layers[layer].exterior.insert(exterior::key(x,y),tile.clone());}
    let before=e.save().unwrap();assert!(e.command(&json!({"op":"merge"})).is_err(),"Merge exceeded the per-layer tile limit");assert_eq!(e.save().unwrap(),before);
}
#[test]
fn audit_group_translation_moves_the_whole_layer(){
    let mut e=Engine::new(128,128).unwrap();outside(&mut e,-20.,40.);e.command(&json!({"op":"group","action":"create"})).unwrap();
    e.command(&json!({"op":"group","action":"translate","id":1,"x":40,"y":0})).unwrap();
    assert_eq!(e.state.layers[0].pixel_at(-20,40,128,128),0,"Old exterior ink did not move");assert!(e.state.layers[0].pixels[40*128+20]>>24>0);
}
#[test]
fn audit_flip_canvas_includes_exterior_ink(){
    let mut e=Engine::new(128,128).unwrap();outside(&mut e,-20.,40.);let before=e.save().unwrap();e.command(&json!({"op":"flipCanvas","horizontal":true})).unwrap();
    assert_eq!(e.state.layers[0].pixel_at(-20,40,128,128),0);assert!(e.state.layers[0].pixel_at(147,40,128,128)>>24>0);e.undo();assert_eq!(e.save().unwrap(),before);
}
#[test]
fn audit_transform_rejects_offcanvas_overflow_instead_of_erasing_art(){
    let mut e=Engine::new(64,64).unwrap();outside(&mut e,32.,32.);let before=e.save().unwrap();
    assert!(e.command(&json!({"op":"transformProjective","matrix":[1,0,10000,0,1,0,0,0,1]})).is_err(),"Transform silently clipped every painted pixel");assert_eq!(e.save().unwrap(),before);
}
#[test]
fn audit_import_preserves_extreme_aspect_ratio_images(){
    let mut e=Engine::new(64,64).unwrap();e.import(1,2048,&vec![0xffff0000;2048]).unwrap();assert!(e.state.layers[e.state.active].pixels.iter().any(|c|c>>24>0),"A thin image was imported as an empty layer");
}

#[test]
fn audit_document_preflight_bounds_masks_before_pixel_allocation(){
    let mut e=Engine::new(16,16).unwrap();let mut doc:SavedDocument=serde_json::from_slice(&e.save().unwrap()).unwrap();
    doc.width=8192;doc.height=2048;let template=serde_json::to_value(&doc.layers[0]).unwrap();doc.layers.clear();
    for id in 1..=13{let mut layer:SavedLayer=serde_json::from_value(template.clone()).unwrap();layer.id=id;layer.runs=vec![(16_777_216,0)];layer.mask=Some(vec![(16_777_216,255)]);doc.layers.push(layer);}
    assert!(doc.validate_decode_budget().unwrap_err().contains("memory budget"));
    doc.layers.truncate(1);doc.layers[0].mask=Some(vec![(16_777_216,256)]);assert!(doc.validate_decode_budget().is_err());
}
#[test]
fn audit_metadata_layer_limit_is_checked_without_decoding_images(){
    let mut e=Engine::new(16,16).unwrap();let mut doc:Value=serde_json::from_slice(&e.save().unwrap()).unwrap();let original=doc["layers"][0].clone();doc["layers"]=Value::Array((1..=4097).map(|id|{let mut l=original.clone();l["id"]=json!(id);l}).collect());
    assert!(Engine::load(&serde_json::to_vec(&doc).unwrap()).is_err());
}
#[test]
fn audit_preflight_accepts_documents_at_the_existing_pixel_budget(){
    let mut e=Engine::new(16,16).unwrap();let mut doc:SavedDocument=serde_json::from_slice(&e.save().unwrap()).unwrap();doc.width=8192;doc.height=2048;let template=serde_json::to_value(&doc.layers[0]).unwrap();doc.layers.clear();
    for id in 1..=4{let mut l:SavedLayer=serde_json::from_value(template.clone()).unwrap();l.id=id;l.runs=vec![(16_777_216,0xff000000+id)];doc.layers.push(l);}assert!(doc.validate_decode_budget().is_ok());
}
#[test]
fn audit_explicit_layer_actions_do_not_target_a_different_active_layer(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"fill","color":0xff0000ffu32})).unwrap();
    e.command(&json!({"op":"duplicate","index":0})).unwrap();assert_eq!(e.state.layers[e.state.active].pixels[0],0xffff0000,"Duplicate ignored its explicit source index");
    let active=e.state.layers[e.state.active].id;e.command(&json!({"op":"delete","index":0})).unwrap();assert_eq!(e.state.layers[e.state.active].id,active,"Deleting another layer changed the active selection");assert!(!e.state.layers.iter().any(|l|l.id==1));
}
#[test]
fn audit_runtime_metadata_limit_prevents_unsaveable_extra_layers(){
    let mut e=Engine::new(16,16).unwrap();let layer=e.state.layers[0].clone();e.state.layers=(1..=4096).map(|id|{let mut l=layer.clone();l.id=id;l}).collect();e.next_id=4097;
    assert!(e.command(&json!({"op":"add"})).is_err());
}
#[test]
fn audit_flip_preserves_shared_empty_planes(){
    let mut e=Engine::new(64,64).unwrap();for _ in 0..3{e.command(&json!({"op":"add"})).unwrap();}e.command(&json!({"op":"flipCanvas","horizontal":true})).unwrap();
    assert!(e.state.layers.iter().all(|l|Arc::ptr_eq(&l.pixels,&e.empty_pixels)),"Canvas flip expanded every empty layer into a separate pixel buffer");
}
#[test]
fn audit_duplicate_and_live_stamps_respect_the_document_tile_budget(){
    let mut e=Engine::new(16,16).unwrap();let layer=e.state.layers[0].clone();let mut tile=vec![0;128*128];tile[0]=0xff112233;let tile=Arc::new(tile);
    e.state.layers=(1..=5).map(|id|{let mut l=layer.clone();l.id=id;l}).collect();e.next_id=6;
    for(i,count)in [254,256,256,256,1].into_iter().enumerate(){for n in 0..count{e.state.layers[i].exterior.insert(exterior::key(-1-(n%16) as i32,(n/16) as i32),tile.clone());}}
    assert!(e.command(&json!({"op":"duplicate"})).is_err());
    e.begin(Sample{x:-128.,y:-1.,pressure:1.,tilt:0.},Brush{size:32.,pressure_size:0.,stabilization:0.,..Brush::default()});e.end();
    assert!(e.state.layers.iter().map(|l|l.exterior.len()).sum::<usize>()<=1024);assert!(Engine::load(&e.save().unwrap()).is_ok());
}
