use super::*;

fn dot(e:&mut Engine,x:f32,y:f32,size:f32,color:u32) {
    e.begin(Sample{x,y,pressure:1.0,tilt:0.0},Brush{size,color,stabilization:0.0,..Brush::default()});e.end();
}
#[test]
fn stroke_undo_redo_restores_exact_pixels() {
    let mut e=Engine::new(64,64).unwrap();let original=e.save().unwrap();
    dot(&mut e,32.0,32.0,12.0,0xff112233);let drawn=e.save().unwrap();assert_ne!(original,drawn);
    assert!(e.undo());assert_eq!(e.save().unwrap(),original);assert!(e.redo());assert_eq!(e.save().unwrap(),drawn);
}
#[test]
fn pressure_changes_brush_footprint() {
    let mut low=Engine::new(64,64).unwrap();let mut high=Engine::new(64,64).unwrap();
    for (e,p) in [(&mut low,0.1),(&mut high,1.0)] {
        e.begin(Sample{x:32.0,y:32.0,pressure:p,tilt:0.0},Brush{size:32.0,pressure_size:1.0,..Brush::default()});e.end();
    }
    let count=|e:&Engine|e.state.layers[0].pixels.iter().filter(|p|**p>>24>0).count();
    assert!(count(&high)>count(&low)*4);
}
#[test]
fn multitouch_cancellation_discards_only_pending_stroke() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,8.0,8.0,5.0,0xff000000);let saved=e.save().unwrap();
    e.begin(Sample{x:24.0,y:24.0,pressure:1.0,tilt:0.0},Brush::default());e.cancel();
    assert_eq!(e.save().unwrap(),saved);assert_eq!(e.undo.len(),1);
}
#[test]
fn eraser_preserves_other_layers_and_undo() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,16.0,16.0,12.0,0xffff0000);
    e.command(&json!({"op":"add"})).unwrap();dot(&mut e,16.0,16.0,12.0,0xff0000ff);
    let saved=e.save().unwrap();e.begin(Sample{x:16.0,y:16.0,pressure:1.0,tilt:0.0},Brush{kind:5,size:20.0,..Brush::default()});e.end();
    assert_eq!(e.color_at(16,16,false),0xffff0000);e.undo();assert_eq!(e.save().unwrap(),saved);
}
#[test]
fn selection_limits_paint_fill_and_adjustments() {
    let mut e=Engine::new(32,32).unwrap();e.command(&json!({"op":"select","shape":"Rectangle","points":[[8,8],[16,16]]})).unwrap();
    e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    assert_eq!(e.state.layers[0].pixels[10*32+10],0xffff0000);assert_eq!(e.state.layers[0].pixels[20*32+20],0);
    e.command(&json!({"op":"adjust","kind":"Invert"})).unwrap();assert_eq!(e.state.layers[0].pixels[10*32+10],0xff00ffff);
}
#[test]
fn flood_respects_enclosed_region() {
    let mut e=Engine::new(32,32).unwrap();
    for x in 5..27 {dot(&mut e,x as f32,5.0,3.0,0xff000000);dot(&mut e,x as f32,26.0,3.0,0xff000000);}
    for y in 5..27 {dot(&mut e,5.0,y as f32,3.0,0xff000000);dot(&mut e,26.0,y as f32,3.0,0xff000000);}
    e.command(&json!({"op":"flood","x":16,"y":16,"color":0xff00ff00u32})).unwrap();
    assert_eq!(e.color_at(16,16,false),0xff00ff00);assert_eq!(e.color_at(0,0,false),0);
}
#[test]
fn alpha_lock_preserves_coverage() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,16.0,16.0,10.0,0xff000000);
    let alpha:Vec<_>=e.state.layers[0].pixels.iter().map(|p|p>>24).collect();
    e.command(&json!({"op":"layer","alphaLock":true})).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    assert_eq!(alpha,e.state.layers[0].pixels.iter().map(|p|p>>24).collect::<Vec<_>>());
}
#[test]
fn document_roundtrip_and_malformed_input() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,15.0,15.0,8.0,0x8899aabb);
    e.command(&json!({"op":"duplicate"})).unwrap();e.command(&json!({"op":"layer","opacity":0.5,"blend":"Multiply"})).unwrap();
    let bytes=e.save().unwrap();let mut restored=Engine::load(&bytes).unwrap();assert_eq!(restored.save().unwrap(),bytes);
    assert!(Engine::load(b"{}").is_err());assert!(Engine::new(8192,8192).is_err());
    assert!(decode_runs(&[(u32::MAX,0)],1024).is_err());assert!(decode_runs(&[(1,0)],1024).is_err());
}
#[test]
fn transform_is_undoable_and_selection_moves_only_selected_pixels() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,8.0,8.0,4.0,0xff000000);dot(&mut e,24.0,24.0,4.0,0xffff0000);
    e.command(&json!({"op":"select","points":[[4,4],[12,12]]})).unwrap();let saved=e.save().unwrap();
    e.command(&json!({"op":"transform","dx":8,"dy":0})).unwrap();
    assert_eq!(e.color_at(8,8,false),0);assert_eq!(e.color_at(16,8,false),0xff000000);assert_eq!(e.color_at(24,24,false),0xffff0000);
    e.undo();assert_eq!(e.save().unwrap(),saved);
}
#[test]
fn layer_structure_is_undoable_and_new_strokes_clear_redo() {
    let mut e=Engine::new(32,32).unwrap();e.command(&json!({"op":"add"})).unwrap();assert_eq!(e.state.layers.len(),2);
    e.undo();assert_eq!(e.state.layers.len(),1);e.redo();assert_eq!(e.state.layers.len(),2);
    e.command(&json!({"op":"reorder","index":1,"to":0})).unwrap();assert_eq!(e.state.active,0);
    e.undo();dot(&mut e,16.0,16.0,4.0,0xff000000);assert!(!e.redo());
}
#[test]
fn transparent_export_is_premultiplied_and_background_can_be_hidden() {
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"fill","color":0x80ff0000u32})).unwrap();
    let mut bytes=vec![0;16*16*4];e.render(&mut bytes,false);assert_eq!(&bytes[0..4],&[128,0,0,128]);
    e.render(&mut bytes,true);assert_eq!(bytes[3],255);assert!(!e.render(&mut [0;3],true));
}
#[test]
fn supported_blend_modes_have_expected_outputs() {
    assert_eq!(composite(0xffffffff,0xff445566,1.0,"Multiply"),0xff445566);
    assert_eq!(composite(0xff000000,0xff445566,1.0,"Screen"),0xff445566);
    assert_eq!(composite(0,0x80445566,1.0,"Normal"),0x80445566);
}
#[test]
fn nan_input_does_not_corrupt_drawing() {
    let mut e=Engine::new(32,32).unwrap();let before=e.save().unwrap();
    e.begin(Sample{x:f32::NAN,y:0.0,pressure:1.0,tilt:0.0},Brush::default());e.end();assert_eq!(e.save().unwrap(),before);
}

#[test]
fn smudge_does_not_introduce_new_paint_on_empty_pixels() {
    let mut e=Engine::new(32,32).unwrap();let before=e.save().unwrap();
    e.begin(Sample{x:8.0,y:8.0,pressure:1.0,tilt:0.0},Brush{kind:6,color:0xffff0000,..Brush::default()});
    e.sample(Sample{x:24.0,y:24.0,pressure:1.0,tilt:0.0});e.end();
    assert_eq!(e.save().unwrap(),before);
}

#[test]
fn canvas_flip_moves_every_layer_and_is_undoable() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,5.5,5.5,3.0,0xffff0000);
    e.command(&json!({"op":"add"})).unwrap();dot(&mut e,10.5,10.5,3.0,0xff0000ff);
    let before=e.save().unwrap();e.command(&json!({"op":"flipCanvas","horizontal":true})).unwrap();
    assert_eq!(e.color_at(26,5,false),0xffff0000);assert_eq!(e.color_at(21,10,false),0xff0000ff);
    e.undo();assert_eq!(e.save().unwrap(),before);
}

#[test]
fn merge_does_not_reveal_hidden_lower_layer() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,5.0,5.0,4.0,0xffff0000);
    e.command(&json!({"op":"layer","visible":false})).unwrap();e.command(&json!({"op":"add"})).unwrap();
    dot(&mut e,24.0,24.0,4.0,0xff0000ff);e.command(&json!({"op":"merge"})).unwrap();
    assert_eq!(e.color_at(5,5,false),0);assert_eq!(e.color_at(24,24,false),0xff0000ff);
}

#[test]
fn saving_during_a_long_stroke_does_not_end_or_split_it() {
    let mut e=Engine::new(512,64).unwrap();
    let brush=Brush{size:6.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
    e.begin(Sample{x:10.0,y:32.0,pressure:1.0,tilt:0.0},brush);
    for x in (20..500).step_by(10) {
        if x%50==0 {
            let saved=e.save().unwrap();assert!(Engine::load(&saved).is_ok());
            assert_eq!(e.info()["undo"],0,"Save must leave the active stroke open");
        }
        e.sample(Sample{x:x as f32,y:32.0,pressure:1.0,tilt:0.0});
    }
    e.end();assert_eq!(e.info()["undo"],1);
    for x in 10..490 {assert!(e.color_at(x,32,false)>>24>0,"Gap at {x}");}
    e.undo();assert!((0..512).all(|x|e.color_at(x,32,false)==0));
}

#[test]
fn incremental_frames_match_full_compositing_and_invalidate_on_edits() {
    let mut e=Engine::new(128,96).unwrap();
    let mut frame=vec![0u32;128*96];
    fn apply(e:&mut Engine,frame:&mut[u32]) {
        if let Some(p)=e.render_patch(false){let(x,y,w,h)=(p[0]as usize,p[1]as usize,p[2]as usize,p[3]as usize);
            for row in 0..h {for col in 0..w {frame[(y+row)*e.width+x+col]=p[4+row*w+col]as u32;}}
        }
        for y in 0..e.height {for x in 0..e.width {assert_eq!(frame[y*e.width+x],e.color_at(x,y,true),"Frame mismatch at {x},{y}");}}
    }
    apply(&mut e,&mut frame);assert!(e.render_patch(false).is_none());
    for kind in [0,2,5] {
        e.begin(Sample{x:10.0,y:20.0,pressure:1.0,tilt:0.0},Brush{kind,size:12.0,color:0x99dc5020,..Brush::default()});
        for x in (15..110).step_by(5){e.sample(Sample{x:x as f32,y:40.0,pressure:0.7,tilt:0.0});apply(&mut e,&mut frame);}
        e.end();apply(&mut e,&mut frame);
    }
    for command in [json!({"op":"undo"}),json!({"op":"redo"}),json!({"op":"background","color":0xffabcdefu32}),json!({"op":"add"}),json!({"op":"layer","opacity":0.4})] {
        e.command(&command).unwrap();apply(&mut e,&mut frame);
    }
    e.begin(Sample{x:40.0,y:30.0,pressure:1.0,tilt:0.0},Brush::default());apply(&mut e,&mut frame);
    e.cancel();apply(&mut e,&mut frame);
}

#[test]
fn repeated_marks_do_not_expand_frame_damage_to_old_strokes() {
    let mut e=Engine::new(1600,1200).unwrap();e.render_patch(false).unwrap();
    for i in 0..80 {
        let x=20.0+(i%20)as f32*70.0;let y=20.0+(i/20)as f32*70.0;
        dot(&mut e,x,y,10.0,0xff102030);
        let patch=e.render_patch(false).unwrap();
        assert!(patch[2]<=14 && patch[3]<=14,"Damage accumulated across strokes");
        assert!(patch.len()<201);
    }
}

#[test]
fn layer_lock_blocks_paint_and_destructive_edits_and_roundtrips() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,12.0,12.0,4.0,0xffff0000);
    e.command(&json!({"op":"layer","locked":true})).unwrap();let before=e.save().unwrap();
    dot(&mut e,24.0,24.0,6.0,0xff0000ff);assert_eq!(e.save().unwrap(),before);
    assert!(e.command(&json!({"op":"clear"})).is_err());
    assert!(e.command(&json!({"op":"fill","color":0xff000000u32})).is_err());
    assert!(e.command(&json!({"op":"transform","dx":2})).is_err());
    let restored=Engine::load(&before).unwrap();assert_eq!(restored.info()["layers"][0]["locked"],true);
    e.command(&json!({"op":"layer","locked":false})).unwrap();dot(&mut e,24.0,24.0,6.0,0xff0000ff);
    assert_eq!(e.color_at(24,24,false),0xff0000ff);
}

#[test]
fn canvas_dpi_and_old_documents_are_compatible() {
    let mut e=Engine::new(64,96).unwrap();
    e.command(&json!({"op":"setup","dpi":150,"color":0xff112233u32,"visible":false})).unwrap();
    assert_eq!(e.info()["undo"],0);let bytes=e.save().unwrap();let restored=Engine::load(&bytes).unwrap();
    assert_eq!(restored.info()["dpi"],150);assert_eq!(restored.info()["backgroundVisible"],false);
    let mut old:Value=serde_json::from_slice(&bytes).unwrap();old.as_object_mut().unwrap().remove("dpi");
    for layer in old["layers"].as_array_mut().unwrap(){layer.as_object_mut().unwrap().remove("locked");}
    let old=Engine::load(&serde_json::to_vec(&old).unwrap()).unwrap();
    assert_eq!(old.info()["dpi"],300);assert_eq!(old.info()["layers"][0]["locked"],false);
}

#[test]
fn colordrop_preview_is_reversible_and_commits_one_undo() {
    let mut e=Engine::new(32,32).unwrap();
    let pixels=Arc::make_mut(&mut e.state.layers[0].pixels);
    for y in 0..32 {for x in 0..32 {pixels[y*32+x]=if x<16 {0xffeeeeee}else{0xffcccccc};}}
    let original=e.save().unwrap();
    e.command(&json!({"op":"floodPreview","x":8,"y":8,"color":0xffff0000u32,"threshold":0.02})).unwrap();
    assert_eq!(e.state.layers[0].pixels[8*32+8],0xffff0000);
    assert_eq!(e.state.layers[0].pixels[8*32+24],0xffcccccc);
    assert_eq!(e.undo.len(),0);assert_eq!(e.save().unwrap(),original);
    e.command(&json!({"op":"floodPreview","x":8,"y":8,"color":0xffff0000u32,"threshold":0.3})).unwrap();
    assert_eq!(e.state.layers[0].pixels[8*32+24],0xffff0000);
    e.command(&json!({"op":"floodPreview","x":8,"y":8,"color":0xffff0000u32,"threshold":0.02})).unwrap();
    assert_eq!(e.state.layers[0].pixels[8*32+24],0xffcccccc);
    e.command(&json!({"op":"floodCancel"})).unwrap();assert_eq!(e.save().unwrap(),original);
    e.command(&json!({"op":"floodPreview","x":8,"y":8,"color":0xff00ff00u32,"threshold":0.02})).unwrap();
    e.command(&json!({"op":"floodCommit"})).unwrap();assert_eq!(e.undo.len(),1);
    assert!(e.undo());assert_eq!(e.save().unwrap(),original);assert!(e.redo());
    assert_eq!(e.state.layers[0].pixels[8*32+8],0xff00ff00);
}

#[test]
fn airbrush_has_smooth_falloff_and_distinct_hardness() {
    let mut soft=Engine::new(96,96).unwrap();let mut medium=Engine::new(96,96).unwrap();let mut hard=Engine::new(96,96).unwrap();
    for (e,kind) in [(&mut soft,2),(&mut medium,8),(&mut hard,9)] {
        e.begin(Sample{x:48.0,y:48.0,pressure:1.0,tilt:0.0},Brush{kind,size:64.0,pressure_size:0.0,..Brush::default()});e.end();
    }
    let alpha=|e:&Engine,x:usize|e.state.layers[0].pixels[48*96+x]>>24;
    assert!(alpha(&soft,48)>alpha(&soft,60));assert!(alpha(&soft,60)>alpha(&soft,72));
    assert!(alpha(&medium,65)>alpha(&soft,65));assert!(alpha(&hard,72)>alpha(&medium,72));
    assert_eq!(alpha(&soft,81),0);
}

#[test]
fn smudge_transports_a_multicolor_tip_without_using_active_color() {
    let mut e=Engine::new(96,64).unwrap();
    let pixels=Arc::make_mut(&mut e.state.layers[0].pixels);
    for y in 0..64 {for x in 0..96 {pixels[y*96+x]=if x<48 {if y<32{0xffff0000}else{0xff00ff00}}else{0xff0000ff};}}
    let original=e.save().unwrap();
    e.begin(Sample{x:40.0,y:32.0,pressure:1.0,tilt:0.0},Brush{kind:128+2,size:28.0,color:0xffffffff,pressure_size:0.0,stabilization:0.0,..Brush::default()});
    assert_eq!(e.save().unwrap(),original,"initial pickup must not paint");
    e.sample(Sample{x:60.0,y:32.0,pressure:1.0,tilt:0.0});e.end();
    let upper=e.state.layers[0].pixels[27*96+53];let lower=e.state.layers[0].pixels[37*96+53];
    assert!(channel(upper,16)>channel(upper,8));assert!(channel(lower,8)>channel(lower,16));
    assert!(channel(upper,0)>0.0 && channel(upper,16)>0.0);
    assert_ne!(upper,0xffffffff);assert!(e.undo());assert_eq!(e.save().unwrap(),original);
}

#[test]
fn colordrop_treats_hidden_rgb_as_transparent_and_respects_locks(){
    let mut e=Engine::new(32,32).unwrap();
    Arc::make_mut(&mut e.state.layers[0].pixels)[16*32+16]=0x00ff0000;
    e.command(&json!({"op":"floodPreview","x":16,"y":16,"threshold":0.0,"color":0xff3050a0u32})).unwrap();
    assert!(e.state.layers[0].pixels.iter().all(|p|*p==0xff3050a0));
    e.command(&json!({"op":"floodCancel"})).unwrap();
    e.command(&json!({"op":"layer","locked":true})).unwrap();
    let saved=e.save().unwrap();
    assert!(e.command(&json!({"op":"floodPreview","x":16,"y":16,"color":0xff3050a0u32})).is_err());
    assert_eq!(saved,e.save().unwrap());
}

#[test]
fn colordrop_stays_inside_a_closed_outline(){
    let mut e=Engine::new(40,40).unwrap();let data=Arc::make_mut(&mut e.state.layers[0].pixels);
    for i in 8..32 {data[8*40+i]=0xff000000;data[31*40+i]=0xff000000;data[i*40+8]=0xff000000;data[i*40+31]=0xff000000;}
    e.command(&json!({"op":"floodPreview","x":20,"y":20,"threshold":0.12,"color":0xffeb5b40u32})).unwrap();
    assert_eq!(e.state.layers[0].pixels[20*40+20],0xffeb5b40);
    assert_eq!(e.state.layers[0].pixels[4*40+4],0);assert_eq!(e.state.layers[0].pixels[8*40+20],0xff000000);
    e.command(&json!({"op":"floodCommit"})).unwrap();assert_eq!(e.undo.len(),1);
}

#[test]
fn object_scale_uses_content_center_and_can_be_undone() {
    let mut e=Engine::new(100,100).unwrap();
    let data=Arc::make_mut(&mut e.state.layers[0].pixels);
    for y in 20..30 {for x in 10..20 {data[y*100+x]=0xffaabbcc;}}
    let before=e.state.layers[0].pixels.clone();
    e.command(&json!({"op":"transform","sx":2.0,"sy":2.0})).unwrap();
    assert_eq!(e.state.layers[0].pixels[25*100+15],0xffaabbcc);
    assert_eq!(e.state.layers[0].pixels[16*100+6],0xffaabbcc);
    assert_eq!(e.state.layers[0].pixels[40*100+40],0);
    assert!(e.undo());assert_eq!(e.state.layers[0].pixels,before);
}

#[test]
fn psd_export_does_not_close_live_stroke_or_change_pixels_and_reports_write_errors(){
    let mut e=Engine::new(32,32).unwrap();
    e.begin(Sample{x:16.0,y:16.0,pressure:1.0,tilt:0.0},Brush::default());
    let before=e.save().unwrap();let mut output=Vec::new();e.write_psd(&mut output).unwrap();
    assert!(e.stroke.is_some());assert_eq!(e.save().unwrap(),before);assert_eq!(e.undo.len(),0);
    assert_eq!(&output[..6],b"8BPS\0\x01");assert_eq!(&output[12..14],&4u16.to_be_bytes());
    struct Broken;
    impl std::io::Write for Broken {fn write(&mut self,_:&[u8])->std::io::Result<usize>{Err(std::io::Error::other("Full disk"))}fn flush(&mut self)->std::io::Result<()>{Ok(())}}
    assert!(e.write_psd(&mut Broken).is_err());assert!(e.stroke.is_some());
}

#[test]
fn soft_brush_deposition_depends_on_distance_not_event_count() {
    for kind in [2, 4, 8, 9] {
        let brush=Brush{kind,size:40.0,stabilization:0.0,pressure_size:0.0,..Brush::default()};
        let p=|x|Sample{x,y:40.0,pressure:1.0,tilt:0.0};
        let mut sparse=Engine::new(160,80).unwrap();let mut dense=Engine::new(160,80).unwrap();
        sparse.begin(p(20.0),brush);dense.begin(p(20.0),brush);
        sparse.sample(p(140.0));
        for x in 21..=140 {dense.sample(p(x as f32));}
        let before=dense.state.layers[0].pixels.clone();
        for _ in 0..100 {dense.sample(p(140.0));}
        assert_eq!(before,dense.state.layers[0].pixels,"stationary samples darken kind {kind}");
        sparse.end();dense.end();
        let difference:f32=sparse.state.layers[0].pixels.iter().zip(dense.state.layers[0].pixels.iter()).map(|(a,b)|((*a>>24) as f32-(*b>>24) as f32).abs()).sum();
        assert!(difference/12800.0<0.1,"event density changes kind {kind}: {difference}");
    }
}

#[test]
fn eraser_hardness_preserves_feathered_edges_and_undo() {
    let mut results=Vec::new();
    for kind in [16,23,5] {
        let mut e=Engine::new(64,64).unwrap();Arc::make_mut(&mut e.state.layers[0].pixels).fill(0xff204060);
        let before=e.save().unwrap();
        e.begin(Sample{x:32.0,y:32.0,pressure:1.0,tilt:0.0},Brush{kind,size:40.0,pressure_size:0.0,stabilization:0.0,..Brush::default()});e.end();
        results.push(e.state.layers[0].pixels[32*64+46]>>24);
        assert!(e.undo());assert_eq!(before,e.save().unwrap());
    }
    assert!(results[0]>results[1] && results[1]>results[2]);assert_eq!(results[2],0);
}

#[test]
fn colordrop_repairs_only_the_antialiased_fringe() {
    for opaque in [false,true] {
        let mut e=Engine::new(64,64).unwrap();let bg=if opaque{0xffffffff}else{0};
        let pixels=Arc::make_mut(&mut e.state.layers[0].pixels);pixels.fill(bg);
        for y in 14..50 {for x in 14..50 {
            let inner=(16..48).contains(&x) && (16..48).contains(&y);
            let fringe=(15..49).contains(&x) && (15..49).contains(&y);
            pixels[y*64+x]=if inner{bg}else if fringe{if opaque{0xff808080}else{0x80000000}}else{0xff000000};
        }}
        let before=e.save().unwrap();let color=0xff20c060u32;
        e.command(&json!({"op":"floodPreview","x":30,"y":30,"threshold":0.1,"color":color})).unwrap();
        let p=&e.state.layers[0].pixels;
        assert_eq!(p[30*64+16],color);assert_eq!(p[30*64+14],0xff000000);assert_eq!(p[30*64+13],bg);
        let edge=p[30*64+15];assert_eq!(edge>>24,255);assert!(channel(edge,8)>0.30 && channel(edge,8)<0.50);
        e.command(&json!({"op":"floodCancel"})).unwrap();assert_eq!(before,e.save().unwrap());
        e.command(&json!({"op":"flood","x":30,"y":30,"threshold":0.1,"color":color})).unwrap();
        assert!(e.undo());assert_eq!(before,e.save().unwrap());assert!(e.redo());assert_eq!(e.state.layers[0].pixels[30*64+15],edge);
    }
}

#[test]
fn colordrop_fringe_respects_selection_and_alpha_lock(){
    let mut e=Engine::new(32,32).unwrap();let pixels=Arc::make_mut(&mut e.state.layers[0].pixels);
    pixels[16*32+17]=0x80000000;
    e.selection=Some((0..1024).map(|i|i%32<17).collect());
    e.command(&json!({"op":"flood","x":16,"y":16,"color":0xffff0000u32})).unwrap();assert_eq!(e.state.layers[0].pixels[16*32+17],0x80000000);
    e.selection=None;e.state.layers[0].alpha_lock=true;let before=e.state.layers[0].pixels.clone();
    e.command(&json!({"op":"flood","x":20,"y":20,"color":0xff00ff00u32})).unwrap();
    assert!(before.iter().zip(e.state.layers[0].pixels.iter()).all(|(a,b)|a>>24==b>>24));
}

#[test]
fn affine_transform_has_content_bounds_and_exact_integer_motion(){
    let mut e=Engine::new(64,48).unwrap();let pixels=Arc::make_mut(&mut e.state.layers[0].pixels);
    for y in 10..30 {for x in 20..40{pixels[y*64+x]=if x<30{0xffee4433}else{0x802244ee};}}
    let before=e.save().unwrap();let undo=e.undo.len();let preview=e.transform_snapshot();
    assert_eq!(&preview[..6],&[1,0,20,10,40,30]);assert_eq!(undo,e.undo.len());assert_eq!(before,e.save().unwrap());
    e.command(&json!({"op":"transformMatrix","matrix":[1,0,0,1,7,-3]})).unwrap();
    assert_eq!(e.state.layers[0].pixels[7*64+27],0xffee4433);assert_eq!(e.state.layers[0].pixels[7*64+37],0x802244ee);assert_eq!(e.state.layers[0].pixels[10*64+20],0);
    assert_eq!(e.undo.len(),undo+1);assert!(e.undo());assert_eq!(before,e.save().unwrap());assert!(e.redo());
    e.command(&json!({"op":"layer","locked":true})).unwrap();let locked=e.save().unwrap();assert!(e.command(&json!({"op":"transformMatrix","matrix":[1,0,0,1,1,1]})).is_err());assert_eq!(locked,e.save().unwrap());
}
#[test]
fn affine_rotation_selection_and_premultiplied_edges(){
    let mut e=Engine::new(32,32).unwrap();let pixels=Arc::make_mut(&mut e.state.layers[0].pixels);pixels[10*32+8]=0xffff0000;pixels[20*32+20]=0xff00ff00;
    e.selection=Some((0..1024).map(|i|i==10*32+8).collect());let preview=e.transform_snapshot();assert_eq!(&preview[2..6],&[8,10,9,11]);
    let before=e.save().unwrap();e.command(&json!({"op":"transformMatrix","matrix":[0,1,-1,0,31,0]})).unwrap();
    assert_eq!(e.state.layers[0].pixels[8*32+20],0xffff0000);assert_eq!(e.state.layers[0].pixels[20*32+20],0xff00ff00);
    assert_eq!(e.state.layers[0].pixels[10*32+8],0);assert!(e.undo());assert_eq!(before,e.save().unwrap());
    e.selection=None;Arc::make_mut(&mut e.state.layers[0].pixels).fill(0);Arc::make_mut(&mut e.state.layers[0].pixels)[10*32+8]=0xffff0000;
    e.command(&json!({"op":"transformMatrix","matrix":[1,0,0,1,0.5,0]})).unwrap();
    for x in [8,9]{let color=e.state.layers[0].pixels[10*32+x];assert_eq!(color&0xffffff,0xff0000);assert!((127..=128).contains(&(color>>24)));}
    let state=e.save().unwrap();assert!(e.transform_affine([0.0;6]).is_err());assert!(e.transform_affine([f32::NAN,0.,0.,1.,0.,0.]).is_err());assert_eq!(state,e.save().unwrap());
}
#[test]
fn transform_snapshot_planes_are_cropped_and_bounded(){
    let mut e=Engine::new(2000,2000).unwrap();Arc::make_mut(&mut e.state.layers[0].pixels).fill(0xff112233);let data=e.transform_snapshot();
    assert_eq!(&data[2..6],&[0,0,2000,2000]);let mut offset=6;let mut pixels=0;
    for _ in 0..data[0]+1 {let n=(data[offset+4]*data[offset+5])as usize;pixels+=n;offset+=6+n;}
    assert_eq!(offset,data.len());assert!(pixels<=6_000_000);assert!(data.len()<4_000_000);
}

#[test]
fn eraser_library_uses_every_paint_footprint_and_preserves_rgb() {
    for kind in [0,1,2,3,4,7,8,9,10,11,12,13,14] {
        let mut paint=Engine::new(64,64).unwrap();let mut erase=Engine::new(64,64).unwrap();
        Arc::make_mut(&mut erase.state.layers[0].pixels).fill(0xff123456);
        let saved=erase.save().unwrap();
        for (engine,k,color) in [(&mut paint,kind,0xffffffff),(&mut erase,64+kind,255)] {
            engine.begin(Sample{x:32.0,y:32.0,pressure:0.7,tilt:0.3},Brush{kind:k,color,size:40.0,flow:0.8,opacity:0.7,stabilization:0.0,..Brush::default()});engine.end();
        }
        assert!(erase.state.layers[0].pixels.iter().any(|p|p>>24<255),"kind {kind}");
        for (a,b) in paint.state.layers[0].pixels.iter().zip(erase.state.layers[0].pixels.iter()) {
            assert!(((a>>24)+(b>>24)).abs_diff(255)<=1,"kind {kind}");assert_eq!(b&0xffffff,0x123456);
        }
        let erased=erase.save().unwrap();assert!(erase.undo());assert_eq!(erase.save().unwrap(),saved);assert!(erase.redo());assert_eq!(erase.save().unwrap(),erased);
    }
}
#[test]
fn eraser_library_hardness_selection_and_alpha_lock() {
    let mut e=Engine::new(64,64).unwrap();Arc::make_mut(&mut e.state.layers[0].pixels).fill(0xff123456);
    e.selection=Some((0..4096).map(|i|i%64<32).collect());
    let brush=Brush{kind:64,color:0,size:48.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
    let point=Sample{x:32.0,y:32.0,pressure:1.0,tilt:0.0};e.begin(point,brush);e.end();
    assert_eq!(e.state.layers[0].pixels[32*64+40],0xff123456);
    assert!((e.state.layers[0].pixels[32*64+12]>>24)>200);assert!((e.state.layers[0].pixels[32*64+31]>>24)<10);
    e.state.layers[0].alpha_lock=true;let before=e.save().unwrap();e.begin(point,brush);e.end();assert_eq!(e.save().unwrap(),before);
}

#[test]
fn layer_mask_paint_is_nondestructive_reversible_and_persistent(){
    let mut e=Engine::new(64,64).unwrap();e.command(&json!({"op":"fill","color":0xffe05020u32})).unwrap();let pixels=e.state.layers[0].pixels.clone();
    e.command(&json!({"op":"mask","action":"add"})).unwrap();let before=e.save().unwrap();
    e.begin(Sample{x:32.0,y:32.0,pressure:1.0,tilt:0.0},Brush{color:0xff000000,size:24.0,pressure_size:0.0,..Brush::default()});e.end();
    assert_eq!(e.state.layers[0].pixels,pixels);assert_eq!(e.color_at(32,32,false)>>24,0);assert_eq!(e.color_at(4,4,false),0xffe05020);
    let masked=e.save().unwrap();assert_eq!(Engine::load(&masked).unwrap().save().unwrap(),masked);
    assert!(e.undo());assert_eq!(e.save().unwrap(),before);assert!(e.redo());assert_eq!(e.save().unwrap(),masked);
    e.begin(Sample{x:32.0,y:32.0,pressure:1.0,tilt:0.0},Brush{color:0xffffffff,size:10.0,pressure_size:0.0,..Brush::default()});e.cancel();assert_eq!(e.save().unwrap(),masked);
    e.command(&json!({"op":"mask","action":"enable","enabled":false})).unwrap();assert_eq!(e.color_at(32,32,false),0xffe05020);
    e.command(&json!({"op":"mask","action":"enable","enabled":true})).unwrap();e.command(&json!({"op":"mask","action":"apply"})).unwrap();assert!(e.state.layers[0].mask.is_none());assert_eq!(e.state.layers[0].pixels[32*64+32]>>24,0);
    e.undo();assert!(e.state.layers[0].mask.is_some());assert_eq!(e.state.layers[0].pixels,pixels);
}
#[test]
fn masks_respect_selection_lock_clipping_and_transform(){
    let mut e=Engine::new(64,64).unwrap();dot(&mut e,20.0,20.0,16.0,0xffff0000);
    e.command(&json!({"op":"select","shape":"Rectangle","points":[[16,10],[40,40]]})).unwrap();e.command(&json!({"op":"mask"})).unwrap();assert_eq!(e.state.layers[0].mask.as_ref().unwrap()[20*64+12],0);
    e.command(&json!({"op":"mask","action":"pixels"})).unwrap();assert!(e.command(&json!({"op":"transformMatrix","matrix":[1,0,0,1,10,0]})).is_err());e.command(&json!({"op":"deselect"})).unwrap();let before=e.save().unwrap();
    e.transform_affine([1.0,0.0,0.0,1.0,10.0,0.0]).unwrap();assert_eq!(e.color_at(30,20,false),0xffff0000);assert_eq!(e.color_at(22,20,false)>>24,0);e.undo();assert_eq!(e.save().unwrap(),before);
    e.command(&json!({"op":"layer","locked":true})).unwrap();assert!(e.command(&json!({"op":"mask","action":"delete"})).is_err());
}
#[test]
fn symmetry_mirrors_strokes_once_and_stays_one_undo(){
    for (mode,points) in [("Vertical",vec![(12,18),(52,18)]),("Horizontal",vec![(12,18),(12,46)]),("Quadrant",vec![(12,18),(52,18),(12,46),(52,46)]),("Radial 4",vec![(12,18),(46,12),(52,46),(18,52)])]{
        let mut e=Engine::new(64,64).unwrap();e.command(&json!({"op":"symmetry","mode":mode})).unwrap();dot(&mut e,12.0,18.0,6.0,0xffff0000);
        for (x,y)in points{assert!(e.color_at(x,y,false)>>24>0,"{mode} {x},{y}");}assert_eq!(e.undo.len(),1);e.undo();assert!(e.state.layers[0].pixels.iter().all(|p|*p==0));
    }
}
#[test]
fn layered_psd_import_retains_pixels_layers_masks_and_merged_appearance(){
    let mut e=Engine::new(48,32).unwrap();e.command(&json!({"op":"setup","visible":false,"dpi":144})).unwrap();dot(&mut e,18.0,15.0,20.0,0xff885522);
    e.command(&json!({"op":"mask","hide":true})).unwrap();e.command(&json!({"op":"fill","color":0xff808080u32})).unwrap();e.command(&json!({"op":"mask","action":"pixels"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();dot(&mut e,24.0,18.0,12.0,0x9044bbcc);e.command(&json!({"op":"layer","name":"Top · 顶部","opacity":0.5,"blend":"Multiply"})).unwrap();
    let mut data=vec![];e.write_psd(&mut data).unwrap();let (copy,warnings)=Engine::read_psd(&data).unwrap();assert!(warnings.is_empty());assert_eq!(copy.dpi,144);assert_eq!(copy.state.layers.len(),2);assert_eq!(copy.state.layers[1].name,"Top · 顶部");assert_eq!(copy.state.layers[0].mask,e.state.layers[0].mask);
    for i in 0..48*32{assert_eq!(copy.state.layers[0].pixels[i],e.state.layers[0].pixels[i]);let a=copy.color_at(i%48,i/48,false);let b=e.color_at(i%48,i/48,false);for shift in [0,8,16,24]{assert!(((a>>shift&255) as i32-(b>>shift&255) as i32).abs()<=1);}}
    std::fs::write("../../artifacts/masked-roundtrip.psd",data).unwrap();
    let mut source:Value=serde_json::from_slice(&e.save().unwrap()).unwrap();source["composite"]=json!((0..48*32).map(|i|e.color_at(i%48,i/48,true)).collect::<Vec<_>>());std::fs::write("../../artifacts/masked-roundtrip.json",serde_json::to_vec(&source).unwrap()).unwrap();
}
#[test]
fn psd_import_rejects_truncation_and_unsupported_header(){
    let mut e=Engine::new(16,16).unwrap();let mut data=vec![];e.write_psd(&mut data).unwrap();
    for n in [0,4,25,40,100,data.len()/2]{assert!(Engine::read_psd(&data[..n]).is_err());}
    data[22]=0;data[23]=16;assert!(Engine::read_psd(&data).is_err());
}
#[test]
fn independent_psd_fixtures_support_raw_rle_zip_prediction_and_masks(){
    for (data,json) in [
        (&include_bytes!("../tests/fixtures/layered-0.psd")[..],include_str!("../tests/fixtures/layered-0.json")),
        (&include_bytes!("../tests/fixtures/layered-1.psd")[..],include_str!("../tests/fixtures/layered-1.json")),
        (&include_bytes!("../tests/fixtures/layered-2.psd")[..],include_str!("../tests/fixtures/layered-2.json")),
        (&include_bytes!("../tests/fixtures/layered-3.psd")[..],include_str!("../tests/fixtures/layered-3.json")),
    ]{
        let (e,warnings)=Engine::read_psd(data).unwrap();assert!(warnings.is_empty(),"{warnings:?}");assert_eq!(e.state.layers.len(),2);assert_eq!(e.state.layers[0].name,"Cropped base");assert!(e.state.layers[0].mask.is_some());
        let source:Value=serde_json::from_str(json).unwrap();
        for (i,p) in source["pixels"].as_array().unwrap().iter().enumerate(){let expected=p.as_u64().unwrap() as u32;let actual=e.color_at(i%48,i/48,false);
            for shift in [0,8,16,24]{let a=(actual>>shift&255) as i32;let b=(expected>>shift&255) as i32;if shift==24 || expected>>24>0{assert!((a-b).abs()<=2,"pixel {i} shift {shift}: {actual:08x} != {expected:08x}");}}
        }
    }
}
#[test]
fn small_mask_strokes_keep_history_proportional_to_changed_area(){
    let mut e=Engine::new(512,512).unwrap();e.command(&json!({"op":"mask"})).unwrap();
    for x in (20..220).step_by(10){e.begin(Sample{x:x as f32,y:50.0,pressure:1.0,tilt:0.0},Brush{color:0xff000000,size:6.0,pressure_size:0.0,..Brush::default()});e.end();}
    assert_eq!(e.undo.len(),21);let mask_bytes:usize=e.undo.iter().skip(1).map(Change::bytes).sum();assert!(mask_bytes<10_000,"Small mask strokes used {mask_bytes} history bytes");
    for _ in 0..20{assert!(e.undo());}assert!(e.state.layers[0].mask.as_ref().unwrap().iter().all(|p|*p==255));
}

#[test]
fn history_keeps_thirty_steps_after_spilling_and_cleans_cache() {
    let folder=std::env::temp_dir().join(format!("ink-history-test-{}",std::process::id()));
    std::fs::create_dir_all(&folder).unwrap();
    let mut e=Engine::new(32,32).unwrap();e.history_directory=folder.clone();
    let mut states=vec![e.save().unwrap()];
    for i in 0..35 {
        e.command(&json!({"op":"fill","color":0xff000000u32|((i+1)*0x010307)})).unwrap();
        e.compact_history_to(256);states.push(e.save().unwrap());
    }
    assert_eq!(e.undo.len(),35);assert!(e.undo.iter().any(|c|matches!(c,Change::Archived(_))));
    for index in (5..35).rev(){assert!(e.undo());assert_eq!(e.save().unwrap(),states[index]);}
    assert_eq!(e.redo.len(),30);
    for state in states.iter().skip(6){assert!(e.redo());assert_eq!(e.save().unwrap(),*state);}
    e.command(&json!({"op":"mask","action":"add"})).unwrap();e.compact_history_to(0);
    let masked=e.save().unwrap();assert!(e.undo());assert!(e.redo());assert_eq!(masked,e.save().unwrap());
    drop(e);assert_eq!(std::fs::read_dir(&folder).unwrap().count(),0);std::fs::remove_dir(folder).unwrap();
}

#[test]
fn missing_history_cache_does_not_pop_or_modify_artwork() {
    let mut e=Engine::new(32,32).unwrap();dot(&mut e,8.0,8.0,4.0,0xffff0000);
    e.compact_history_to(0);let before=e.save().unwrap();
    if let Change::Archived(entry)=&e.undo[0]{entry.remove_for_test();}else{panic!("history not archived")}
    assert!(!e.undo());assert_eq!(e.undo.len(),1);assert_eq!(e.redo.len(),0);assert_eq!(e.save().unwrap(),before);assert!(e.history_error.is_some());
}

#[test]
fn large_canvas_blank_layers_share_memory_and_remain_drawable() {
    let mut e=Engine::new(2048,1536).unwrap();
    let started=std::time::Instant::now();
    for _ in 0..100 {e.command(&json!({"op":"add"})).unwrap();}
    assert_eq!(e.state.layers.len(),101);
    assert_eq!(Engine::layer_bytes(&e.state.layers),2048*1536*4);
    assert!(!e.undo.iter().any(|c|matches!(c,Change::Archived(_))));
    assert!(started.elapsed().as_secs()<5,"Adding empty layers must not serialize whole canvases");
    e.begin(Sample{x:400.,y:400.,pressure:1.,tilt:0.},Brush{color:0xffff0000,..Brush::default()});e.end();
    assert_eq!(e.color_at(400,400,false),0xffff0000);
    let mut loaded=Engine::load(&e.save().unwrap()).unwrap();
    assert_eq!(loaded.state.layers.len(),101);
    assert_eq!(loaded.color_at(400,400,false),0xffff0000);
    loaded.command(&json!({"op":"add"})).unwrap();
    assert!(e.undo());assert_eq!(e.color_at(400,400,false),0);assert!(e.redo());assert_eq!(e.color_at(400,400,false),0xffff0000);
}

#[test]
fn history_structure_binary_roundtrip_preserves_shared_buffers_and_masks(){
    let mut e=Engine::new(32,32).unwrap();
    e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"mask","action":"add"})).unwrap();
    let c=e.undo.last().unwrap();
    let entry=history::Entry::write(c,&std::env::temp_dir()).unwrap();
    match entry.read().unwrap(){
        Change::Structure{before,after}=>{
            assert!(after.layers[after.active].mask.is_some());
            assert!(Arc::ptr_eq(&before.layers[0].pixels,&after.layers[0].pixels));
            assert!(Arc::ptr_eq(&after.layers[0].pixels,&after.layers[1].pixels));
        },_=>panic!("Expected layer metadata change")
    }
}

#[test]
fn many_duplicated_layers_reload_without_copying_every_bitmap(){
    let mut e=Engine::new(128,128).unwrap();e.command(&json!({"op":"fill","color":0xff123456u32})).unwrap();
    for _ in 0..80{e.command(&json!({"op":"duplicate"})).unwrap();}
    let loaded=Engine::load(&e.save().unwrap()).unwrap();assert_eq!(loaded.state.layers.len(),81);
    assert_eq!(Engine::layer_bytes(&loaded.state.layers),128*128*4);
    assert_eq!(loaded.color_at(20,20,false),0xff123456);
}

#[test]
fn fade_tail_keeps_width_and_long_strokes_resets_and_is_undoable(){
    let mut e=Engine::new(640,160).unwrap();
    let brush=Brush{size:28.,color:0xff2255ff,fade_length:300.,pressure_size:0.,stabilization:0.,..Brush::default()};
    e.begin(Sample{x:20.,y:60.,pressure:1.,tilt:0.},brush);e.sample(Sample{x:620.,y:60.,pressure:1.,tilt:0.});e.end();
    let alpha=|e:&Engine,x:usize,y:usize|e.state.layers[0].pixels[y*640+x]>>24;
    assert!(alpha(&e,35,60)>200);assert!(alpha(&e,550,60)<alpha(&e,35,60));assert!(alpha(&e,400,60)>0);assert_eq!(alpha(&e,639,60),0);
    assert!(alpha(&e,220,70)>0);assert!(alpha(&e,35,70)>0);
    assert_eq!(e.undo.len(),1);let saved=e.save().unwrap();assert!(e.undo());assert_eq!(alpha(&e,35,60),0);assert!(e.redo());assert_eq!(saved,e.save().unwrap());
    e.begin(Sample{x:20.,y:120.,pressure:1.,tilt:0.},brush);e.sample(Sample{x:620.,y:120.,pressure:1.,tilt:0.});e.end();assert!(alpha(&e,35,120)>200);
    e.begin(Sample{x:20.,y:25.,pressure:1.,tilt:0.},brush);e.sample(Sample{x:200.,y:25.,pressure:1.,tilt:0.});e.cancel();assert_eq!(alpha(&e,35,25),0);
}

#[test]
fn fade_is_independent_of_input_batch_count(){
    let draw=|count:usize|{
        let mut e=Engine::new(640,100).unwrap();let brush=Brush{size:36.,fade_length:300.,pressure_size:0.,stabilization:0.,..Brush::default()};
        e.begin(Sample{x:20.,y:50.,pressure:1.,tilt:0.},brush);
        for i in 1..=count{e.sample(Sample{x:20.+600.*i as f32/count as f32,y:50.,pressure:1.,tilt:0.});}e.end();e.state.layers[0].pixels.clone()
    };
    let one=draw(1);let many=draw(120);
    let max=one.iter().zip(many.iter()).map(|(a,b)|((*a>>24) as i32-(*b>>24) as i32).abs()).max().unwrap();assert!(max<=1,"batch-dependent fade: {max}");
}

#[test]
fn mid_stroke_brush_updates_use_eraser_profile_and_keep_one_undo(){
    let mut e=Engine::new(320,240).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    let sample=|x|Sample{x,y:120.,pressure:1.,tilt:0.};
    e.begin(sample(60.),Brush{size:4.,color:0xff0000ff,pressure_size:0.,stabilization:0.,..Brush::default()});
    e.update_brush(Brush{kind:64,size:40.,color:255,pressure_size:0.,stabilization:0.,..Brush::default()});e.sample(sample(180.));e.end();
    assert_eq!(e.state.layers[0].pixels[132*320+140]>>24,0);assert_eq!(e.state.layers[0].pixels[149*320+140],0xffff0000);
    assert_eq!(e.undo.len(),2);assert!(e.undo());assert_eq!(e.state.layers[0].pixels[132*320+140],0xffff0000);assert!(e.redo());assert_eq!(e.state.layers[0].pixels[132*320+140]>>24,0);
}

#[test]
fn selection_modes_automatic_feather_and_named_roundtrip(){
    let mut e=Engine::new(40,32).unwrap();
    e.command(&json!({"op":"select","shape":"Rectangle","points":[[4,4],[20,20]]})).unwrap();
    e.command(&json!({"op":"select","mode":"Add","shape":"Rectangle","points":[[16,16],[32,28]]})).unwrap();
    assert_eq!(e.selection_coverage(25*40+25),255);assert_eq!(e.selection_coverage(8*40+8),255);assert_eq!(e.selection_coverage(30*40+2),0);
    e.command(&json!({"op":"select","mode":"Remove","shape":"Rectangle","points":[[8,8],[12,12]]})).unwrap();assert_eq!(e.selection_coverage(10*40+10),0);
    e.command(&json!({"op":"selectionFeather","radius":3})).unwrap();let soft=e.selection_alpha.clone().unwrap();assert!(soft.iter().any(|a|*a>0 && *a<255));
    e.command(&json!({"op":"selectionSave","name":"Subject"})).unwrap();let bytes=e.save().unwrap();let mut loaded=Engine::load(&bytes).unwrap();loaded.command(&json!({"op":"selectionLoad","name":"Subject"})).unwrap();assert_eq!(loaded.selection_alpha.as_ref().unwrap(),&soft);
    e.command(&json!({"op":"selectionFeather","radius":0})).unwrap();assert!(e.selection_alpha.as_ref().unwrap().iter().all(|a|*a==0 || *a==255));
    e.command(&json!({"op":"deselect"})).unwrap();for y in 0..32{for x in 0..20{Arc::make_mut(&mut e.state.layers[0].pixels)[y*40+x]=0xffff0000;}}
    e.command(&json!({"op":"selectionAutomatic","x":4,"y":4,"threshold":0.01})).unwrap();assert_eq!(e.selection_coverage(10*40+10),255);assert_eq!(e.selection_coverage(10*40+30),0);
    e.command(&json!({"op":"invertSelection"})).unwrap();assert_eq!(e.selection_coverage(10*40+10),0);assert_eq!(e.selection_coverage(10*40+30),255);
}

#[test]
fn selection_clipboard_fill_clear_and_feather_alpha_are_undoable(){
    let mut e=Engine::new(40,32).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    e.command(&json!({"op":"select","shape":"Rectangle","points":[[8,8],[24,24]]})).unwrap();e.command(&json!({"op":"selectionFeather","radius":2})).unwrap();
    let index=10*40+8;let coverage=e.selection_coverage(index);assert!(coverage>0 && coverage<255);
    e.command(&json!({"op":"selectionCopyPaste"})).unwrap();assert_eq!(e.state.layers.len(),2);assert_eq!(e.state.layers[1].pixels[index]>>24,coverage as u32);assert!(e.undo());assert_eq!(e.state.layers.len(),1);assert!(e.redo());assert_eq!(e.state.layers.len(),2);
    e.command(&json!({"op":"select","shape":"Rectangle","points":[[8,8],[24,24]]})).unwrap();let before=e.save().unwrap();e.command(&json!({"op":"selectionClear"})).unwrap();assert_eq!(e.state.layers[1].pixels[index]>>24,0);assert!(e.undo());assert_eq!(before,e.save().unwrap());
    e.command(&json!({"op":"selectionFill","color":0xff0044ffu32})).unwrap();assert_eq!(e.state.layers[1].pixels[16*40+16],0xff0044ff);assert!(e.undo());
    e.command(&json!({"op":"selectionCut"})).unwrap();assert!(e.info()["clipboard"].as_bool().unwrap());assert_eq!(e.state.layers[1].pixels[16*40+16]>>24,0);
    e.command(&json!({"op":"selectionPaste"})).unwrap();assert_eq!(e.state.layers.len(),3);assert!(e.state.layers[2].pixels[16*40+16]>>24>0);
}

#[test]
fn projective_and_mesh_transforms_reject_folds_and_keep_undo_pixels(){
    let mut e=Engine::new(64,64).unwrap();for y in 10..40{for x in 10..40{Arc::make_mut(&mut e.state.layers[0].pixels)[y*64+x]=if x<25{0xffff0000}else{0xff0044ff};}}
    let before=e.save().unwrap();e.transform_projective([1.,0.,0.,0.,1.,0.,0.01,0.,1.],"Nearest").unwrap();assert_eq!(e.state.layers[0].pixels[15*64+15],0xffff0000);assert_eq!(e.state.layers[0].pixels[38*64+38],0);assert!(e.undo());assert_eq!(before,e.save().unwrap());
    let points:Vec<_>=(0..=2).flat_map(|y|(0..=2).map(move|x|(10.+15.*x as f32+5.,10.+15.*y as f32+3.))).collect();e.transform_mesh(2,2,&points,"Nearest").unwrap();assert_eq!(e.state.layers[0].pixels[13*64+15],0xffff0000);assert_eq!(e.state.layers[0].pixels[13*64+40],0xff0044ff);assert!(e.undo());assert_eq!(before,e.save().unwrap());
    let mut folded=points.clone();folded[4]=(60.,60.);assert!(e.transform_mesh(2,2,&folded,"Bilinear").is_err());assert_eq!(before,e.save().unwrap());
    assert!(e.transform_projective([1.,0.,0.,0.,1.,0.,-0.04,0.,1.],"Bilinear").is_err());assert_eq!(before,e.save().unwrap());
}

#[test]
fn fade_keeps_a_tap_and_short_strokes_visible(){
    let mut e=Engine::new(128,64).unwrap();let b=Brush{size:20.,fade_length:150.,pressure_size:0.,stabilization:0.,..Brush::default()};
    e.begin(Sample{x:20.,y:20.,pressure:1.,tilt:0.},b);e.end();assert!(e.state.layers[0].pixels[20*128+20]>>24>200);
    e.begin(Sample{x:60.,y:40.,pressure:1.,tilt:0.},b);e.sample(Sample{x:85.,y:40.,pressure:1.,tilt:0.});e.end();assert!(e.state.layers[0].pixels[40*128+60]>>24>200);
}

#[test]
fn finishing_fade_refreshes_the_whole_live_tail_without_ghost_pixels(){
    let mut e=Engine::new(320,96).unwrap();let mut display=e.render_patch(true).unwrap()[4..].to_vec();
    let apply=|display:&mut Vec<i32>,patch:Vec<i32>|{let(x,y,w,h)=(patch[0] as usize,patch[1] as usize,patch[2] as usize,patch[3] as usize);for row in 0..h{display[(y+row)*320+x..(y+row)*320+x+w].copy_from_slice(&patch[4+row*w..4+(row+1)*w]);}};
    e.begin(Sample{x:20.,y:48.,pressure:1.,tilt:0.},Brush{size:28.,fade_length:100.,pressure_size:0.,stabilization:0.,..Brush::default()});e.sample(Sample{x:270.,y:48.,pressure:1.,tilt:0.});apply(&mut display,e.render_patch(false).unwrap());e.end();apply(&mut display,e.render_patch(false).unwrap());assert_eq!(display,&e.render_patch(true).unwrap()[4..]);
}

#[test]
fn fade_speed_changes_opacity_and_tail_without_changing_radius(){
    let draw=|dt:f32|{
        let mut e=Engine::new(800,80).unwrap();
        e.begin(Sample{x:20.,y:40.,pressure:1.,tilt:0.},Brush{size:28.,fade_length:150.,pressure_size:0.,stabilization:0.,..Brush::default()});e.set_sample_time(0.);
        for i in 1..=150{e.sample_timed(Sample{x:20.+i as f32*5.,y:40.,pressure:1.,tilt:0.},i as f32*dt);}
        let radius=e.stroke.as_ref().unwrap().brush.size;e.end();(e,radius)
    };
    let (slow,sr)=draw(35.);let (fast,fr)=draw(2.);
    let alpha=|e:&Engine,x:usize|e.state.layers[0].pixels[40*800+x]>>24;
    assert_eq!(sr,fr);assert!(alpha(&slow,300)>alpha(&fast,300)+40);
    assert!(alpha(&slow,680)>alpha(&fast,680)+15);
    assert!(fast.state.layers[0].pixels[50*800+300]>>24>0);
    assert_eq!(slow.undo.len(),1);assert_eq!(fast.undo.len(),1);
}

#[test]
fn groups_preserve_member_flags_save_history_and_translate_together(){
    let mut e=Engine::new(64,64).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Sketch"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();assert_eq!(e.state.layers[1].group_id,Some(1));
    e.command(&json!({"op":"group","action":"update","id":1,"visible":false,"locked":true})).unwrap();
    assert_eq!(e.color_at(10,10,false),0);assert!(e.state.layers[0].visible);assert!(e.state.layers[1].is_locked());
    assert!(e.command(&json!({"op":"fill","color":1})).is_err());
    let mut loaded=Engine::load(&e.save().unwrap()).unwrap();assert_eq!(loaded.state.groups.len(),1);assert!(!loaded.state.layers[0].is_visible());
    e.command(&json!({"op":"group","action":"update","id":1,"visible":true,"locked":false})).unwrap();
    e.command(&json!({"op":"group","action":"translate","id":1,"x":8,"y":0})).unwrap();assert_eq!(e.color_at(0,20,false),0);assert_eq!(e.color_at(10,20,false),0xffff0000);
    e.undo();assert_eq!(e.color_at(0,20,false),0xffff0000);e.redo();assert_eq!(e.color_at(0,20,false),0);
    loaded.command(&json!({"op":"group","action":"ungroup","id":1})).unwrap();assert!(loaded.state.layers[0].is_visible());assert!(!loaded.state.layers[1].is_locked());
}

#[test]
fn timed_fade_has_no_opaque_start_cap_and_keeps_taps(){
    let mut e=Engine::new(800,80).unwrap();let brush=Brush{size:28.,fade_length:150.,pressure_size:0.,stabilization:0.,..Brush::default()};
    e.begin(Sample{x:30.,y:40.,pressure:1.,tilt:0.},brush);e.set_sample_time(0.);
    for i in 1..=140{e.sample_timed(Sample{x:30.+i as f32*5.,y:40.,pressure:1.,tilt:0.},i as f32*2.);}
    e.end();let pixels=&e.state.layers[0].pixels;
    assert!(pixels[40*800+30]>>24<150,"first stamp must not be an opaque circle");
    assert!(pixels[40*800+30]>>24<=pixels[40*800+60]>>24);
    e.begin(Sample{x:10.,y:10.,pressure:1.,tilt:0.},brush);e.set_sample_time(0.);e.end();assert!(e.state.layers[0].pixels[10*800+10]>>24>0);
}

#[test]
fn layer_drag_moves_into_out_of_groups_reorders_and_undoes(){
    let mut e=Engine::new(64,64).unwrap();e.command(&json!({"op":"group","action":"create","name":"Sketch"})).unwrap();e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"moveLayer","id":2,"groupId":null,"targetId":null})).unwrap();assert_eq!(e.state.layers[0].id,2);assert_eq!(e.state.layers[0].group_id,None);
    e.command(&json!({"op":"moveLayer","id":2,"groupId":1,"targetId":1,"above":true})).unwrap();assert_eq!(e.state.layers.iter().map(|l|l.id).collect::<Vec<_>>(),vec![1,2,3]);
    assert_eq!(e.state.layers[1].group_id,Some(1));assert_eq!(e.info()["undoAction"],"Move layer");
    e.command(&json!({"op":"undo"})).unwrap();assert_eq!(e.info()["historyNotice"],"Undo: Move layer");assert_eq!(e.state.layers[0].group_id,None);
    e.command(&json!({"op":"redo"})).unwrap();assert_eq!(e.info()["historyNotice"],"Redo: Move layer");assert_eq!(e.state.layers[1].group_id,Some(1));
}

#[test]
fn undo_names_survive_history_archiving_and_redo_branching(){
    let mut e=Engine::new(64,64).unwrap();e.command(&json!({"op":"fill","color":0xff123456u32})).unwrap();e.command(&json!({"op":"add"})).unwrap();e.compact_history_to(0);
    assert!(e.undo.iter().any(|c|matches!(c,Change::Archived(_))));
    e.command(&json!({"op":"undo"})).unwrap();assert_eq!(e.info()["historyNotice"],"Undo: Create layer");
    e.command(&json!({"op":"undo"})).unwrap();assert_eq!(e.info()["historyNotice"],"Undo: Fill layer");e.command(&json!({"op":"redo"})).unwrap();assert_eq!(e.info()["historyNotice"],"Redo: Fill layer");
    e.command(&json!({"op":"redo"})).unwrap();assert_eq!(e.info()["historyNotice"],"Redo: Create layer");
    e.command(&json!({"op":"undo"})).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();assert!(e.redo_names.is_empty());assert_eq!(e.info()["undoAction"],"Fill layer");
}

#[test]
fn liquify_push_cancel_commit_restore_selection_and_history(){
    let mut e=Engine::new(128,128).unwrap();
    e.begin(Sample{x:64.,y:20.,pressure:1.,tilt:0.},Brush{size:10.,color:0xffff0000,pressure_size:0.,stabilization:0.,..Brush::default()});e.sample(Sample{x:64.,y:108.,pressure:1.,tilt:0.});e.end();let original=e.save().unwrap();
    let begin=|e:&mut Engine|e.command(&json!({"op":"liquifyBegin"})).unwrap();
    begin(&mut e);e.command(&json!({"op":"liquifyDab","mode":"Push","size":64,"strength":1.,"start":true,"points":[[64,64,1]]})).unwrap();e.command(&json!({"op":"liquifyDab","mode":"Push","size":64,"strength":1.,"end":true,"points":[[90,64,1]]})).unwrap();
    assert_ne!(e.save().unwrap(),original);assert_eq!(e.undo.len(),1);e.command(&json!({"op":"liquifyCancel"})).unwrap();assert_eq!(e.save().unwrap(),original);
    for mode in ["Push","Pinch","Expand","Twirl right","Twirl left"]{
        begin(&mut e);e.command(&json!({"op":"liquifyDab","mode":mode,"size":80,"strength":1.,"start":true,"points":[[64,64,1]]})).unwrap();e.command(&json!({"op":"liquifyDab","mode":mode,"size":80,"strength":1.,"end":true,"points":[[90,64,1]]})).unwrap();e.command(&json!({"op":"liquifyCommit"})).unwrap();
        assert_eq!(e.undo.len(),2);assert_eq!(e.info()["undoAction"],"Liquify");e.undo();assert_eq!(e.save().unwrap(),original);
    }
    let mut selection=vec![0u8;128*128];for y in 48..80{for x in 48..80{selection[y*128+x]=255;}}e.set_selection(Some(selection));begin(&mut e);
    e.command(&json!({"op":"liquifyDab","mode":"Expand","size":100,"strength":1.,"points":[[64,64,1],[64,64,1],[64,64,1]]})).unwrap();
    assert_eq!(e.color_at(64,25,false),0xffff0000);e.command(&json!({"op":"liquifyDab","mode":"Restore","size":600,"strength":1.,"points":[[64,64,1]]})).unwrap();e.command(&json!({"op":"liquifyCancel"})).unwrap();assert_eq!(e.save().unwrap(),original);
}

#[test]
fn slow_fade_lead_in_has_no_local_cap_peak_and_keeps_short_marks(){
    for dt in [10.,35.,100.] {
        let mut e=Engine::new(800,100).unwrap();let b=Brush{size:40.,fade_length:150.,pressure_size:0.,stabilization:0.,..Brush::default()};
        e.begin(Sample{x:60.,y:50.,pressure:1.,tilt:0.},b);e.set_sample_time(0.);
        e.sample_timed(Sample{x:60.,y:50.,pressure:1.,tilt:0.},500.);
        for i in 1..=120{e.sample_timed(Sample{x:60.+i as f32*5.,y:50.,pressure:1.,tilt:0.},500.+i as f32*dt);}
        e.end();let a=|x:usize|e.state.layers[0].pixels[50*800+x]>>24;
        assert!(a(60)<a(80),"slow stroke starts with a cap: {} vs {}",a(60),a(80));assert!(a(65)<a(90));
        e.begin(Sample{x:20.,y:20.,pressure:1.,tilt:0.},b);e.set_sample_time(0.);e.sample_timed(Sample{x:26.,y:20.,pressure:1.,tilt:0.},100.);e.end();assert!(e.state.layers[0].pixels[20*800+23]>>24>0);
    }
}

#[test]
fn only_member_can_be_dragged_past_its_own_folder_boundary(){
    let mut e=Engine::new(64,64).unwrap();e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"group","action":"create"})).unwrap();
    e.command(&json!({"op":"moveLayer","id":2,"targetId":2,"groupId":null,"above":false})).unwrap();assert_eq!(e.state.layers[1].group_id,None);assert_eq!(e.state.layers[1].id,2);
    e.undo();assert_eq!(e.state.layers[1].group_id,Some(1));
}

#[test]
fn group_opacity_is_applied_after_members_are_combined_and_survives_history(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Paint"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"fill","color":0xff0000ffu32})).unwrap();
    e.command(&json!({"op":"group","action":"update","id":1,"opacity":0.5,"blend":"Normal"})).unwrap();
    let color=e.color_at(8,8,false);assert!(((color>>24)as i32-128).abs()<=1);assert_eq!(color&0xffffff,0x0000ff);
    let loaded=Engine::load(&e.save().unwrap()).unwrap();assert_eq!(loaded.color_at(8,8,false),color);
    e.undo();assert_eq!(e.color_at(8,8,false),0xff0000ff);e.redo();assert_eq!(e.color_at(8,8,false),color);
    e.command(&json!({"op":"group","action":"update","id":1,"visible":false})).unwrap();assert_eq!(e.color_at(8,8,false),0);
}
#[test]
fn isolated_group_multiply_blends_against_the_outer_backdrop(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"fill","color":0xff808080u32})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    e.command(&json!({"op":"group","action":"create"})).unwrap();
    e.command(&json!({"op":"group","action":"update","id":1,"blend":"Multiply"})).unwrap();
    assert_eq!(e.color_at(4,4,false),0xff800000);
    e.command(&json!({"op":"group","action":"update","id":1,"opacity":0.0})).unwrap();assert_eq!(e.color_at(4,4,false),0xff808080);
}
#[test]
fn old_group_documents_default_to_pass_through_and_full_opacity(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"group","action":"create"})).unwrap();
    let mut doc:Value=serde_json::from_slice(&e.save().unwrap()).unwrap();doc["groups"][0].as_object_mut().unwrap().remove("opacity");doc["groups"][0].as_object_mut().unwrap().remove("blend");
    let other=Engine::load(&serde_json::to_vec(&doc).unwrap()).unwrap();let info=other.info();assert_eq!(info["groups"][0]["opacity"],1.0);assert_eq!(info["groups"][0]["blend"],"Pass Through");
}
#[test]
fn rainbow_changes_along_a_stroke_without_changing_width_or_history_count(){
    let mut e=Engine::new(128,32).unwrap();e.command(&json!({"op":"brushEffects","mode":"Rainbow","cycle":96})).unwrap();
    let brush=Brush{size:8.,pressure_size:0.,stabilization:0.,..Brush::default()};
    e.begin(Sample{x:8.,y:16.,pressure:1.,tilt:0.},brush);e.sample(Sample{x:112.,y:16.,pressure:1.,tilt:0.});e.end();
    let a=e.color_at(16,16,false);let b=e.color_at(48,16,false);assert_ne!(a&0xffffff,b&0xffffff);assert!((a>>24)>200 && (b>>24)>200);assert_eq!(e.undo.len(),1);
    let saved=e.save().unwrap();e.undo();assert_eq!(e.color_at(48,16,false),0);e.redo();assert_eq!(e.save().unwrap(),saved);
}
#[test]
fn palette_interpolation_and_ryb_wet_mix_are_predictable(){
    let effects=brush_color::BrushColor::parse(&json!({"mode":"Palette","cycle":100,"palette":[0xffff0000u32,0xff0000ffu32]})).unwrap();
    assert_eq!(effects.color(0xff123456,0.),0xffff0000);assert_eq!(effects.color(0xff123456,50.),0xff0000ff);
    let mix=brush_color::wet_mix(0xff0000ff,0xffffff00,0.5);assert!(channel(mix,8)>channel(mix,16) && channel(mix,8)>channel(mix,0));
    assert_eq!(brush_color::wet_mix(0xff123456,0,1.),0xff123456);
}

#[test]
fn psd_preserves_group_appearance_by_baking_the_group_without_changing_the_native_document(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"background","visible":false})).unwrap();
    e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();e.command(&json!({"op":"group","action":"create","name":"Paint folder"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();e.command(&json!({"op":"fill","color":0xff0000ffu32})).unwrap();e.command(&json!({"op":"group","action":"update","id":1,"opacity":0.5,"blend":"Normal"})).unwrap();
    let before=e.save().unwrap();let mut bytes=Vec::new();e.write_psd(&mut bytes).unwrap();assert_eq!(before,e.save().unwrap());
    let (loaded,_)=Engine::read_psd(&bytes).unwrap();let color=loaded.color_at(8,8,false);assert!(((color>>24)as i32-128).abs()<=1);assert_eq!(color&0xffffff,0x0000ff);
}

#[test]
fn multicolor_carries_distinct_colors_across_width_and_uses_custom_color_stops(){
    let mut e=Engine::new(128,80).unwrap();e.command(&json!({"op":"brushEffects","mode":"Palette","layout":"Across","cycle":20000,"palette":[0xffff0000u32,0xff00ff00u32,0xff0000ffu32]})).unwrap();
    e.begin(Sample{x:12.,y:40.,pressure:1.,tilt:0.},Brush{size:40.,pressure_size:0.,stabilization:0.,..Brush::default()});e.sample(Sample{x:115.,y:40.,pressure:1.,tilt:0.});e.end();
    let top=e.color_at(64,26,false);let middle=e.color_at(64,40,false);let bottom=e.color_at(64,54,false);
    assert!(channel(top,0)>channel(top,8));assert!(channel(middle,16)>channel(middle,0));assert!(channel(bottom,8)>channel(bottom,16));assert!(channel(middle,24)>0.9);
    let bytes=e.save().unwrap();e.undo();assert_eq!(e.color_at(64,40,false),0);e.redo();assert_eq!(bytes,e.save().unwrap());
}
#[test]
fn multicolor_cycle_length_controls_distance_and_bristle_layout_survives_fade_replay(){
    let short=brush_color::BrushColor::parse(&json!({"mode":"Rainbow","cycle":60,"layout":"Across"})).unwrap();let long=brush_color::BrushColor::parse(&json!({"mode":"Rainbow","cycle":6000,"layout":"Across"})).unwrap();
    assert_ne!(short.at(0xff000000,20.,0.),long.at(0xff000000,20.,0.));
    let mut e=Engine::new(160,100).unwrap();e.command(&json!({"op":"brushEffects","mode":"Rainbow","layout":"Bristles","cycle":600})).unwrap();
    e.begin(Sample{x:15.,y:30.,pressure:1.,tilt:0.},Brush{size:30.,pressure_size:0.,stabilization:0.,fade_length:40.,..Brush::default()});e.sample(Sample{x:80.,y:30.,pressure:1.,tilt:0.});e.sample(Sample{x:130.,y:70.,pressure:1.,tilt:0.});e.end();assert!(e.state.layers[0].pixels.iter().any(|c|*c>>24>0));assert_eq!(e.undo.len(),1);
}

#[test]
fn wet_paint_load_has_independent_pickup_retention_and_strength(){
    let base=0xffffff00;let blue=0xff0000ff;let mut no_pickup=None;
    for _ in 0..20{assert_eq!(brush_color::transport(base,&mut no_pickup,blue,1.0,0.0,1.0,0.1),base);}
    let mut wet=None;for _ in 0..30{brush_color::transport(base,&mut wet,blue,1.0,0.8,1.0,0.1);}
    let wet_color=brush_color::transport(base,&mut wet,0,1.0,0.8,1.0,0.1);
    let mut dry=wet;for _ in 0..20{brush_color::transport(base,&mut dry,0,1.0,0.8,0.0,0.2);}
    let dry_color=brush_color::transport(base,&mut dry,0,1.0,0.8,0.0,0.2);
    assert!(channel(wet_color,0)>0.8);assert!(channel(dry_color,16)>0.9 && channel(dry_color,8)>0.9);
    assert_eq!(brush_color::transport(base,&mut wet,blue,0.0,1.0,1.0,0.1),base);
    assert_eq!(brush_color::sample_paint(&[0,0,0,0],2,2,0.5,0.5,0.3),0);
}

#[test]
fn wet_paint_carries_color_into_empty_pixels_and_undo_redo_is_exact(){
    fn draw(wetness:f64,fade:f32)->Engine{
        let mut e=Engine::new(160,48).unwrap();
        let pixels=Arc::make_mut(&mut e.state.layers[0].pixels);
        for y in 0..48{for x in 0..65{pixels[y*160+x]=0xff0000ff;}}
        e.command(&json!({"op":"brushEffects","mix":1.0,"pickup":0.85,"wetness":wetness})).unwrap();
        let brush=Brush{color:0xffffff00,size:18.,flow:0.4,stabilization:0.,fade_length:fade,..Brush::default()};
        e.begin(Sample{x:10.,y:24.,pressure:1.,tilt:0.},brush);
        for x in 11..145{e.sample(Sample{x:x as f32,y:24.,pressure:1.,tilt:0.});}e.end();e
    }
    let wet=draw(1.0,0.0);let dry=draw(0.0,0.0);
    assert!(channel(wet.color_at(115,24,false),0)>channel(dry.color_at(115,24,false),0)+0.3);
    let mut fade=draw(0.7,25.0);let painted=fade.save().unwrap();assert!(fade.undo());assert!(fade.redo());assert_eq!(fade.save().unwrap(),painted);
    let a=brush_color::BrushColor::parse(&json!({"mix":1.0,"pickup":8.0,"wetness":-3.0})).unwrap();assert_eq!(a.pickup,1.0);assert_eq!(a.wetness,0.0);
}

#[test]
fn exterior_paint_survives_save_cancel_undo_and_layer_visibility(){
    let mut e=Engine::new(64,64).unwrap();
    dot(&mut e,-12.0,20.0,12.0,0xffab2244);
    let saved=e.save().unwrap();assert!(!e.state.layers[0].exterior.is_empty());
    assert!(e.state.layers[0].pixels.iter().all(|p|*p==0));
    let mut loaded=Engine::load(&saved).unwrap();assert_eq!(saved,loaded.save().unwrap());
    assert!(!loaded.exterior_preview()["tiles"].as_array().unwrap().is_empty());
    e.undo();assert!(e.state.layers[0].exterior.is_empty());e.redo();assert_eq!(saved,e.save().unwrap());
    e.begin(Sample{x:-20.0,y:40.0,pressure:1.0,tilt:0.0},Brush::default());e.cancel();assert_eq!(saved,e.save().unwrap());
    e.compact_history_to(0);assert!(e.history_error.is_none());e.undo();assert!(e.state.layers[0].exterior.is_empty());e.redo();assert_eq!(saved,e.save().unwrap());
    e.command(&json!({"op":"layer","visible":false})).unwrap();
    assert!(e.exterior_preview()["tiles"].as_array().unwrap().is_empty());
}
#[test]
fn exterior_crossing_stroke_eraser_clear_and_export_are_bounded(){
    let mut e=Engine::new(64,64).unwrap();let brush=Brush{size:12.0,stabilization:0.0,..Brush::default()};
    e.begin(Sample{x:20.0,y:20.0,pressure:1.0,tilt:0.0},brush);e.sample(Sample{x:-20.0,y:20.0,pressure:1.0,tilt:0.0});e.end();
    assert_eq!(e.undo.len(),1);let saved=e.save().unwrap();
    assert!(e.state.layers[0].pixels.iter().any(|p|p>>24!=0));assert!(!e.state.layers[0].exterior.is_empty());
    e.begin(Sample{x:-15.0,y:20.0,pressure:1.0,tilt:0.0},Brush{kind:64,size:24.0,..brush});e.end();e.undo();assert_eq!(saved,e.save().unwrap());
    e.command(&json!({"op":"clear"})).unwrap();assert!(e.state.layers[0].exterior.is_empty());e.undo();assert_eq!(saved,e.save().unwrap());
    let mut out=vec![0u8;64*64*4];assert!(e.render(&mut out,false));assert_eq!(out.len(),16384);
}

#[test]
fn nested_groups_move_as_a_subtree_and_reject_cycles(){
    let mut e=Engine::new(16,16).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Inner"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"moveLayer","id":2,"groupId":null})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Outer"})).unwrap();
    e.command(&json!({"op":"group","action":"move","id":1,"parentId":2})).unwrap();
    assert_eq!(e.state.groups[0].parent_id,Some(2));
    e.command(&json!({"op":"group","action":"update","id":2,"visible":false,"locked":true})).unwrap();
    assert!(e.state.layers.iter().all(|l|!l.is_visible() && l.is_locked()));
    assert!(e.command(&json!({"op":"group","action":"move","id":2,"parentId":1})).is_err());
    let loaded=Engine::load(&e.save().unwrap()).unwrap();assert_eq!(loaded.state.groups[0].parent_id,Some(2));
    e.command(&json!({"op":"group","action":"move","id":1,"parentId":null,"targetId":2,"above":true})).unwrap();
    assert_eq!(e.state.layers.last().unwrap().id,1);assert!(e.state.layers.last().unwrap().is_visible());
    e.undo();assert_eq!(e.state.groups[0].parent_id,Some(2));
    e.redo();assert_eq!(e.state.groups[0].parent_id,None);
    let mut doc:Value=serde_json::from_slice(&e.save().unwrap()).unwrap();doc["groups"][0]["parent_id"]=json!(2);doc["groups"][1]["parent_id"]=json!(1);
    assert!(Engine::load(&serde_json::to_vec(&doc).unwrap()).is_err());
}

#[test]
fn nested_group_opacity_compositing_psd_and_ungroup_keep_children(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"background","visible":false})).unwrap();e.command(&json!({"op":"fill","color":0xffff0000u32})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Inner"})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Outer","empty":true})).unwrap();
    e.command(&json!({"op":"group","action":"move","id":1,"parentId":2})).unwrap();
    for id in [1,2]{e.command(&json!({"op":"group","action":"update","id":id,"opacity":0.5,"blend":"Normal"})).unwrap();}
    let pixel=e.color_at(4,4,false);assert!((60..=66).contains(&(pixel>>24)));assert_eq!(pixel&0xffffff,0xff0000);
    let mut psd=Vec::new();e.write_psd(&mut psd).unwrap();let (imported,_)=Engine::read_psd(&psd).unwrap();assert!(((imported.color_at(4,4,false)>>24) as i32-(pixel>>24) as i32).abs()<=1);
    e.command(&json!({"op":"group","action":"ungroup","id":2})).unwrap();assert_eq!(e.state.groups[0].parent_id,None);assert_eq!(e.state.layers[0].group_id,Some(1));
    e.undo();assert_eq!(e.state.groups[0].parent_id,Some(2));
}


#[test]
fn dragging_parent_moves_every_nested_layer_and_keeps_their_order(){
    let mut e=Engine::new(16,16).unwrap();
    e.command(&json!({"op":"group","action":"create"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Nested"})).unwrap();
    e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"moveLayer","id":3,"groupId":null,"targetId":2,"above":true})).unwrap();
    e.command(&json!({"op":"group","action":"move","id":1,"parentId":null,"targetId":3,"above":true})).unwrap();
    assert_eq!(e.state.layers.iter().map(|l|l.id).collect::<Vec<_>>(),vec![3,1,2]);
    assert_eq!(e.state.groups[1].parent_id,Some(1));
    e.command(&json!({"op":"undo"})).unwrap();assert_eq!(e.state.layers.iter().map(|l|l.id).collect::<Vec<_>>(),vec![1,2,3]);
    e.command(&json!({"op":"redo"})).unwrap();assert_eq!(e.info()["historyNotice"],"Redo: Move layer group");
}

#[test]
fn empty_group_position_is_undoable_and_persists_between_layers(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Empty","empty":true})).unwrap();
    e.command(&json!({"op":"group","action":"move","id":1,"targetId":1,"above":false})).unwrap();
    assert_eq!(e.state.groups[0].anchor_id,Some(1));assert!(e.state.layers.iter().all(|l|l.group_id.is_none()));
    let loaded=Engine::load(&e.save().unwrap()).unwrap();assert_eq!(loaded.state.groups[0].anchor_id,Some(1));
    e.undo();assert_eq!(e.state.groups[0].anchor_id,None);e.redo();assert_eq!(e.state.groups[0].anchor_id,Some(1));
    e.command(&json!({"op":"group","action":"create","name":"Second empty","empty":true})).unwrap();
    e.command(&json!({"op":"group","action":"move","id":2,"targetGroupId":1,"above":false})).unwrap();
    assert_eq!(e.state.groups[0].id,2);assert_eq!(e.state.groups[0].anchor_id,Some(1));
}

#[test]
fn off_canvas_preview_updates_only_changed_tiles_and_can_clear_them(){
    let mut e=Engine::new(128,128).unwrap();e.render_patch(true);e.exterior_patch();
    e.begin(Sample{x:-40.,y:40.,pressure:1.,tilt:0.},Brush{size:12.,pressure_size:0.,stabilization:0.,..Brush::default()});e.end();
    let first=e.exterior_patch().unwrap();assert_eq!(first[1],0);assert_eq!(first[2],1);assert!(e.exterior_patch().is_none());
    e.begin(Sample{x:180.,y:40.,pressure:1.,tilt:0.},Brush{size:12.,pressure_size:0.,stabilization:0.,..Brush::default()});e.end();
    let next=e.exterior_patch().unwrap();assert_eq!(next[1],0);assert_eq!(next[2],1);assert_eq!(next[3],128);
    e.command(&json!({"op":"clear"})).unwrap();let clear=e.exterior_patch().unwrap();assert_eq!(clear[1],1);assert_eq!(clear[2],0);
}

#[test]
fn whole_layer_transform_moves_and_resizes_ink_across_artboard_edges(){
    let mut e=Engine::new(128,128).unwrap();
    e.begin(Sample{x:-30.,y:40.,pressure:1.,tilt:0.},Brush{size:12.,pressure_size:0.,stabilization:0.,..Brush::default()});e.sample(Sample{x:30.,y:40.,pressure:1.,tilt:0.});e.end();
    let before=e.save().unwrap();let snapshot=e.transform_snapshot();assert!(snapshot[2]<0);assert!(snapshot[4]>0);
    assert!(e.state.layers[0].pixel_at(-30,40,e.width,e.height)>>24>0);
    e.command(&json!({"op":"transformProjective","matrix":[1,0,60,0,1,0,0,0,1],"filter":"Nearest"})).unwrap();
    assert_eq!(e.state.layers[0].pixel_at(-30,40,e.width,e.height),0);assert!(e.state.layers[0].pixels[40*128+30]>>24>0);assert!(e.state.layers[0].pixels[40*128+90]>>24>0);
    e.undo();assert_eq!(e.save().unwrap(),before);e.redo();
    e.command(&json!({"op":"transformProjective","matrix":[2,0,-160,0,2,-40,0,0,1],"filter":"Bilinear"})).unwrap();
    assert!(e.transform_extent(true,0)[0]<0);assert!(e.state.layers[0].pixel_at(-100,40,e.width,e.height)>>24>0);
    let loaded=Engine::load(&e.save().unwrap()).unwrap();assert_eq!(loaded.state.layers[0].pixel_at(-100,40,128,128),e.state.layers[0].pixel_at(-100,40,128,128));
}

#[test]
fn placing_the_anchor_layer_into_an_empty_folder_keeps_the_folder_in_place(){
    let mut e=Engine::new(16,16).unwrap();e.command(&json!({"op":"add"})).unwrap();
    e.command(&json!({"op":"group","action":"create","empty":true})).unwrap();
    e.command(&json!({"op":"group","action":"move","id":1,"targetId":1,"above":false})).unwrap();
    e.command(&json!({"op":"moveLayer","id":1,"groupId":1})).unwrap();
    assert_eq!(e.state.layers[0].id,1);assert_eq!(e.state.layers[0].group_id,Some(1));assert_eq!(e.state.groups[0].anchor_id,Some(2));
    e.command(&json!({"op":"moveLayer","id":1,"groupId":null,"targetId":1,"above":false})).unwrap();
    assert_eq!(e.state.groups[0].anchor_id,Some(2));
}

#[test]
fn explicit_top_level_creation_and_nested_empty_folders_do_not_steal_layers(){
    let mut e=Engine::new(16,16).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Existing layer folder"})).unwrap();
    for name in ["Second","Third","Fourth"]{
        let info=e.command(&json!({"op":"group","action":"create","name":name,"empty":true,"parentId":null})).unwrap();assert!(info["createdGroupId"].as_u64().unwrap()>1);
    }
    assert_eq!(e.state.groups.len(),4);assert!(e.state.groups.iter().all(|g|g.parent_id.is_none()));assert_eq!(e.state.layers[0].group_id,Some(1));
    e.command(&json!({"op":"group","action":"update","id":2,"collapsed":true})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Child","empty":true,"parentId":2})).unwrap();
    e.command(&json!({"op":"group","action":"update","id":2,"collapsed":true})).unwrap();
    e.command(&json!({"op":"group","action":"update","id":5,"collapsed":true})).unwrap();
    e.command(&json!({"op":"group","action":"create","name":"Grandchild","empty":true,"parentId":5})).unwrap();
    assert_eq!(e.group_path(Some(6)).len(),3);let info=e.info();assert_eq!(info["groups"][1]["collapsed"],false);assert_eq!(info["groups"][4]["collapsed"],false);
    let restored=Engine::load(&e.save().unwrap()).unwrap();assert_eq!(restored.group_path(Some(6)).len(),3);
    e.command(&json!({"op":"group","action":"create","name":"Wrap at root","index":0,"parentId":null})).unwrap();assert_eq!(e.state.groups.last().unwrap().parent_id,None);
}
