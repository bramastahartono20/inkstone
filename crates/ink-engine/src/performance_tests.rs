use super::*;

#[test]
fn reusable_shape_preview_keeps_snapshot_and_incremental_damage(){
    let mut e=Engine::new(512,512).unwrap();e.render_patch(false);
    let before=e.save().unwrap();let b=Brush{size:6.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
    let line=|x|[Sample{x,y:40.0,pressure:1.0,tilt:0.0},Sample{x:x+50.0,y:80.0,pressure:1.0,tilt:0.0}];
    e.replace_stroke(&line(30.0),b);let snapshot=e.stroke.as_ref().unwrap().before.generation();e.render_patch(false);
    for i in 0..100{
        e.replace_stroke(&line(30.0+i as f32),b);
        assert_eq!(e.stroke.as_ref().unwrap().before.generation(),snapshot);
        assert_eq!(e.undo.len(),0);
        let p=e.render_patch(false).unwrap();assert!((p[2]*p[3])<512*512/8);
    }
    e.cancel();assert_eq!(before,e.save().unwrap());
    e.replace_stroke(&line(100.0),b);e.end();assert_eq!(e.undo.len(),1);
    let after=e.save().unwrap();e.undo();assert_eq!(before,e.save().unwrap());e.redo();assert_eq!(after,e.save().unwrap());
}

#[test]
fn prepared_compositor_matches_nested_groups_masks_and_clipping(){
    let mut e=Engine::new(32,32).unwrap();
    e.command(&json!({"op":"group","action":"create","empty":true})).unwrap();
    e.command(&json!({"op":"group","action":"create","empty":true,"parentId":1})).unwrap();
    for i in 0..6{
        if i>0{e.command(&json!({"op":"add"})).unwrap();}
        let l=&mut e.state.layers[i];l.group_id=if i<3{Some(2)}else if i<5{Some(1)}else{None};
        l.opacity=0.75;l.clipping=i==2;l.visible=i!=3;
        l.pixels.fill(0x803060a0+i as u32*0x00090803);
        if i==1{l.mask=Some(Arc::new((0..1024).map(|n|(n%256)as u8).collect()));}
    }
    e.refresh_groups();
    for (mode,opacity) in [("Pass Through",1.0),("Pass Through",0.45),("Normal",0.8),("Multiply",0.6),("Screen",0.8)]{
        e.state.groups[0].blend=mode.into();e.state.groups[0].opacity=opacity;
        e.state.groups[1].blend="Multiply".into();e.state.groups[1].opacity=0.6;
        let layers=e.compositing_layers();let plan=groups::PreparedComposite::new(&e.state.groups,&layers);
        for idx in 0..1024{
            let sample=|l:&Layer|(l.pixels[idx],l.mask_coverage(idx));
            assert_eq!(plan.color(0xfffaf9f6,sample),e.composite_grouped(&layers,0xfffaf9f6,sample));
        }
    }
}

#[test]
fn normal_composite_fast_path_preserves_reference_channels(){
    for opacity in [0.000001,0.0001,0.001,0.01]{
        let src=0x013570d6;let sa=channel(src,24)*opacity;let weight=sa/sa.max(0.0001);
        let expected=pack(channel(src,16)*weight,channel(src,8)*weight,channel(src,0)*weight,sa);
        assert_eq!(composite(0,src,opacity,"Normal"),expected);
    }
    let mut seed=42u32;
    for _ in 0..20000{
        seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);let src=seed;
        seed=seed.wrapping_mul(1664525).wrapping_add(1013904223);let dst=seed;
        let opacity=(seed%101)as f32/100.0;
        let sa=channel(src,24)*opacity;let da=channel(dst,24);let a=sa+da*(1.0-sa);
        let reference=if sa<=0.0{dst}else{let c=|s|{let sc=channel(src,s);let dc=channel(dst,s);(sa*(1.0-da)*sc+sa*da*sc+(1.0-sa)*da*dc)/a.max(0.0001)};pack(c(16),c(8),c(0),a)};
        let actual=composite(dst,src,opacity,"Normal");
        for shift in [0,8,16,24]{assert!(((actual>>shift&255)as i32-(reference>>shift&255)as i32).abs()<=1);}
    }
}

#[test]
fn reusable_shape_restores_mask_and_exterior_without_trails(){
    for mask in [false,true]{
        let mut e=Engine::new(96,96).unwrap();
        if mask{e.command(&json!({"op":"mask"})).unwrap();}
        let before=e.save().unwrap();let undo=e.undo.len();
        let b=Brush{size:12.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
        for x in [-30.0,20.0,75.0]{e.replace_stroke(&[Sample{x,y:40.0,pressure:1.0,tilt:0.0},Sample{x:x+30.0,y:55.0,pressure:1.0,tilt:0.0}],b);e.render_patch(false);}
        e.cancel();assert_eq!(e.save().unwrap(),before);assert_eq!(e.undo.len(),undo);
    }
}

#[test]
fn diagonal_stroke_history_keeps_only_touched_tiles_and_archives_exactly(){
    let mut e=Engine::new(1024,1024).unwrap();let before=e.save().unwrap();
    e.begin(Sample{x:30.0,y:30.0,pressure:1.0,tilt:0.0},Brush{size:6.0,pressure_size:0.0,stabilization:0.0,..Brush::default()});
    e.sample(Sample{x:980.0,y:980.0,pressure:1.0,tilt:0.0});e.end();
    assert!(matches!(e.undo.last(),Some(Change::PixelTiles{..})));
    assert!(e.undo.last().unwrap().bytes()<1024*1024*8/4);
    let after=e.save().unwrap();e.compact_history_to(0);
    assert!(matches!(e.undo.last(),Some(Change::Archived(_))));
    assert!(e.undo());assert_eq!(e.save().unwrap(),before);
    assert!(e.redo());assert_eq!(e.save().unwrap(),after);
}

#[test]
fn cached_backdrop_matches_full_compositing_after_layer_mask_and_background_edits(){
    let mut e=Engine::new(96,80).unwrap();
    for i in 0..5{
        if i>0{e.command(&json!({"op":"add"})).unwrap();}
        let l=&mut e.state.layers[i];l.pixels.fill(0x60306090+i as u32*0x00070903);l.opacity=0.7;
    }
    for step in 0..9{
        match step{
            1=>e.state.layers[4].pixels.fill(0x80b09040),
            2=>e.state.layers[0].pixels.fill(0x90304020),
            3=>e.state.layers[1].opacity=0.25,
            4=>e.state.layers[2].mask=Some(Arc::new(vec![64;96*80])),
            5=>e.state.background=0xff112233,
            6=>e.state.layers[3].visible=false,
            7=>e.state.layers[0].blend="Multiply".into(),
            8=>{e.state.active=2;e.state.layers[4].clipping=true;},
            _=>{}
        }
        e.dirty=false;e.damage=Some([0,0,96,80]);let patch=e.render_patch(false).unwrap();
        for y in 0..80{for x in 0..96{assert_eq!(patch[4+y*96+x]as u32,e.color_at(x,y,true),"step {step}, {x},{y}");}}
    }
}
