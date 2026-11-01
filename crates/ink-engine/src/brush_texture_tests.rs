use super::*;
#[test]
fn material_tips_are_distinct_and_support_eraser_and_history(){
    let mut fingerprints=std::collections::HashSet::new();
    for kind in 32..=42 {
        let mut e=Engine::new(240,100).unwrap();
        let brush=Brush{kind,size:64.0,color:0xff296ac0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
        let point=|x|Sample{x,y:50.0,pressure:1.0,tilt:0.0};
        e.begin(point(40.0),brush);e.sample(point(200.0));e.end();
        let pixels=e.state.layers[0].pixels.clone();let painted=pixels.iter().filter(|p|p>>24>0).count();assert!(painted>150,"kind {kind}: empty tip");
        assert!(fingerprints.insert(pixels.to_vec()),"kind {kind}: duplicate texture");
        e.begin(point(40.0),Brush{kind:64+kind,..brush});e.sample(point(200.0));e.end();
        assert!(e.state.layers[0].pixels.iter().zip(pixels.iter()).any(|(a,b)|a>>24<b>>24),"eraser {kind}");
        assert!(e.undo());assert_eq!(e.state.layers[0].pixels,pixels);assert!(e.redo());
    }
}
#[test]
fn adjustment_layers_preserve_source_pixels_and_save_with_clipping(){
 let mut e=Engine::new(32,32).unwrap();
 e.command(&json!({"op":"fill","color":0xff406080u32})).unwrap();let original=e.state.layers[0].pixels.clone();
 e.command(&json!({"op":"add"})).unwrap();
 e.command(&json!({"op":"layer","adjustmentKind":"Brightness","amount":0.2,"clipping":true})).unwrap();
 assert!(channel(e.color_at(4,4,false),16)>0.4);assert_eq!(e.state.layers[0].pixels,original);
 let bytes=e.save().unwrap();let restored=Engine::load(&bytes).unwrap();assert_eq!(e.color_at(4,4,false),restored.color_at(4,4,false));
 e.command(&json!({"op":"layer","visible":false})).unwrap();assert_eq!(e.color_at(4,4,false),0xff406080);
 e.undo();assert!(channel(e.color_at(4,4,false),16)>0.4);
}
#[test]
fn live_adjustment_preview_is_absolute_and_commits_one_history_step(){
 let mut e=Engine::new(32,32).unwrap();e.command(&json!({"op":"fill","color":0xff406080u32})).unwrap();let original=e.state.layers[0].pixels.clone();let count=e.undo.len();
 for amount in [0.1,0.4,0.2]{e.command(&json!({"op":"adjustPreview","kind":"Brightness","amount":amount})).unwrap();}
 assert_eq!(e.undo.len(),count);e.command(&json!({"op":"adjustCommit"})).unwrap();assert_eq!(e.undo.len(),count+1);e.undo();assert_eq!(e.state.layers[0].pixels,original);
}
#[test]
fn compressed_native_save_roundtrips_and_stroke_before_shares_pixels(){
 let mut e=Engine::new(512,512).unwrap();
 e.begin(Sample{x:20.,y:20.,pressure:1.,tilt:0.},Brush::default());
 assert!(e.stroke.as_ref().unwrap().before.is_blank());e.sample(Sample{x:60.,y:50.,pressure:1.,tilt:0.});e.end();
 let pixels=e.state.layers[0].pixels.clone();let bytes=e.save_compressed().unwrap();assert!(bytes.len()<10000);let loaded=Engine::load(&bytes).unwrap();assert_eq!(loaded.state.layers[0].pixels,pixels);
}

#[test]
fn adding_blank_layers_does_not_invalidate_clean_canvas_and_ink_records_stay_bounded(){
 let mut e=Engine::new(1024,1024).unwrap();
 e.command(&json!({"op":"fill","color":0xff223344u32})).unwrap();e.render_patch(true).unwrap();
 for _ in 0..20{e.command(&json!({"op":"add"})).unwrap();assert!(e.render_patch(false).is_none());}
 let p=|x|Sample{x,y:30.,pressure:1.,tilt:0.};e.begin(p(10.),Brush{size:2.,pressure_size:0.,..Brush::default()});
 for i in 11..1000{e.sample(p(i as f32));}
 assert_eq!(e.stroke.as_ref().unwrap().records.len(),1);e.end();assert!(e.render_patch(false).is_some());
}

#[test]
fn wet_mix_single_color_fast_path_keeps_foreign_color_mixing_enabled(){
 let mut e=Engine::new(3000,2000).unwrap();e.brush_color.mix=0.7;e.brush_color.mode="Solid".into();
 let p=Sample{x:20.,y:20.,pressure:1.,tilt:0.};let b=Brush{size:48.,color:0xff336699,..Brush::default()};
 e.begin(p,b);assert_eq!(e.stroke.as_ref().unwrap().effects.mix,0.0);e.end();
 e.begin(p,b);assert_eq!(e.stroke.as_ref().unwrap().effects.mix,0.0);e.cancel();
 e.state.layers[0].pixels[100] =0xffff0000;
 e.begin(p,b);assert!(e.stroke.as_ref().unwrap().effects.mix>0.0);e.cancel();
}
