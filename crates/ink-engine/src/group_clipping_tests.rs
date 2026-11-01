use super::*;
fn fixture()->Engine{
    let mut e=Engine::new(16,16).unwrap();e.state.background_visible=false;
    e.command(&json!({"op":"group","action":"create","name":"Base"})).unwrap();
    e.state.layers[0].pixels[3*16+3] =0xffff0000;
    e.command(&json!({"op":"add"})).unwrap();
    e.state.layers[1].pixels[3*16+10] =0xff0000ff;
    // A transparent last member must not hide the earlier members from the clip mask.
    e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"group","action":"removeMember","id":1})).unwrap();
    e.command(&json!({"op":"fill","color":0xff00ff00u32})).unwrap();
    e.command(&json!({"op":"layer","clipping":true})).unwrap();e
}
#[test] fn clip_layer_uses_union_of_group_members_and_empty_top_member(){
    let mut e=fixture();
    assert_eq!(e.color_at(3,3,false),0xff00ff00);assert_eq!(e.color_at(10,3,false),0xff00ff00);assert_eq!(e.color_at(7,7,false),0);
    let patch=e.render_patch(true).unwrap();assert_eq!(patch[4+3*16+3] as u32,0xff00ff00);assert_eq!(patch[4+7*16+7],0);
    let mut rgba=vec![0;16*16*4];assert!(e.render(&mut rgba,false));assert_eq!(&rgba[(3*16+10)*4..(3*16+10)*4+4],&[0,255,0,255]);
    let saved=e.save().unwrap();let loaded=Engine::load(&saved).unwrap();assert_eq!(loaded.color_at(10,3,false),0xff00ff00);
    e.undo();assert_eq!(e.color_at(7,7,false),0xff00ff00);e.redo();assert_eq!(e.color_at(7,7,false),0);
}
#[test] fn clip_group_obeys_visibility_masks_opacity_and_psd_export(){
    let mut e=fixture();
    let mut mask=vec![255;256];mask[3*16+3]=0;e.state.layers[0].mask=Some(Arc::new(mask));
    assert_eq!(e.color_at(3,3,false),0);assert_eq!(e.color_at(10,3,false),0xff00ff00);
    e.command(&json!({"op":"group","action":"update","id":1,"opacity":0.5})).unwrap();
    let expected=e.color_at(10,3,false);assert!((expected>>24)<255);
    let mut psd=Vec::new();e.write_psd(&mut psd).unwrap();let(imported,_)=Engine::read_psd(&psd).unwrap();
    for (x,y) in [(3,3),(10,3),(7,7)]{let a=e.color_at(x,y,false);let b=imported.color_at(x,y,false);for shift in [0,8,16,24]{assert!((((a>>shift)&255)as i32-((b>>shift)&255)as i32).abs()<=2,"{a:x} != {b:x}");}}
    e.command(&json!({"op":"group","action":"update","id":1,"visible":false})).unwrap();assert_eq!(e.color_at(10,3,false),0);
}
#[test] fn nested_group_clip_and_multiple_clipped_layers_keep_same_base(){
    let mut e=fixture();
    e.command(&json!({"op":"group","action":"create","name":"Outer","empty":true})).unwrap();
    e.command(&json!({"op":"group","action":"move","id":1,"parentId":2,"targetId":4,"above":false})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"fill","color":0xffff00ffu32})).unwrap();e.command(&json!({"op":"layer","clipping":true})).unwrap();
    assert_eq!(e.color_at(3,3,false),0xffff00ff);assert_eq!(e.color_at(10,3,false),0xffff00ff);assert_eq!(e.color_at(7,7,false),0);
}
#[test] fn clipped_group_cache_does_not_leak_after_group_movement_or_ungroup(){
    let mut e=fixture();e.render_patch(true);
    e.command(&json!({"op":"group","action":"translate","id":1,"x":2,"y":0})).unwrap();
    let p=e.render_patch(false).unwrap();assert_eq!(p[4+3*16+5] as u32,0xff00ff00);assert_eq!(p[4+3*16+3],0);
    e.command(&json!({"op":"group","action":"ungroup","id":1})).unwrap();assert_eq!(e.color_at(5,3,false),0xffff0000);assert_eq!(e.color_at(12,3,false),0xff0000ff);
    e.undo();assert_eq!(e.color_at(5,3,false),0xff00ff00);
}
#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn group_clipping_with_gpu_enabled_matches_cpu_fallback(){
    // GPU compositing cannot flatten a group clip mask; use the shared prepared plan.
    let mut e=fixture();e.set_renderer("auto").unwrap();assert!(e.gpu.is_some());let p=e.render_patch(true).unwrap();
    assert_eq!(p[4+3*16+3] as u32,0xff00ff00);assert_eq!(p[4+7*16+7],0);
}
#[test] fn disabling_clip_and_alpha_lock_restores_paint_and_erase_after_many_strokes(){
    let mut e=fixture();e.command(&json!({"op":"layer","alphaLock":true})).unwrap();
    for _ in 0..80{e.begin(Sample{x:7.,y:7.,pressure:1.,tilt:0.},Brush{size:4.,stabilization:0.,..Brush::default()});e.end();e.render_patch(false);}
    e.command(&json!({"op":"layer","alphaLock":false,"clipping":false})).unwrap();
    e.begin(Sample{x:7.,y:7.,pressure:1.,tilt:0.},Brush{size:8.,color:0xff123456,stabilization:0.,..Brush::default()});e.end();
    assert_eq!(e.color_at(7,7,false),0xff123456);
    e.begin(Sample{x:7.,y:7.,pressure:1.,tilt:0.},Brush{kind:64,size:12.,opacity:1.,stabilization:0.,..Brush::default()});e.end();
    assert_eq!(e.color_at(7,7,false),0);
}
