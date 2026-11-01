use super::*;

#[test]
fn wet_reservoir_refills_at_maximum_wetness_and_picks_up_again(){
    let base=0xffffff00;let blue=0xff0000ff;let red=0xffff0000;let mut load=None;
    for _ in 0..120{brush_color::transport(base,&mut load,blue,1.0,0.9,1.0,0.1);}
    let carried=brush_color::transport(base,&mut load,0,1.0,0.9,1.0,0.1);
    assert!(channel(carried,0)>0.5);
    let mut refill=carried;
    for _ in 0..1800{refill=brush_color::transport(base,&mut load,0,1.0,0.9,1.0,0.1);}
    assert_eq!(refill,base,"Float reservoir must not stick at a quantization threshold");
    let mut remixed=refill;
    for _ in 0..100{remixed=brush_color::transport(base,&mut load,red,1.0,0.9,1.0,0.1);}
    assert!(channel(remixed,16)>0.9 && channel(remixed,8)<0.4);
}

#[test]
fn pigment_footprint_samples_subpixels_without_a_color_step(){
    let pixels=(0..16).flat_map(|_|(0..16).map(|x|if x<8{0xffff0000}else{0xffffff00})).collect::<Vec<_>>();
    let mut previous=None;
    for i in 0..40{
        let color=brush_color::sample_paint(&crate::tiles::Tiles::from_flat(&pixels,16,16),16,16,7.0+i as f32/20.0,8.0,0.1);
        assert!(channel(color,16)>0.98 && channel(color,24)>0.98);
        if let Some(old)=previous{assert!((channel(color,8)-channel(old,8)).abs()<0.15);}
        previous=Some(color);
    }
}

#[test]
fn long_mixing_stroke_refills_then_crosses_another_color_and_keeps_exact_history(){
    let mut e=Engine::new(2048,64).unwrap();
    let pixels=&mut e.state.layers[0].pixels;
    for y in 0..64{for x in 40..140{pixels[y*2048+x]=0xff0000ff;}for x in 850..950{pixels[y*2048+x]=0xffff0000;}}
    let before=e.save().unwrap();
    e.command(&json!({"op":"brushEffects","mix":1.0,"pickup":0.9,"wetness":1.0})).unwrap();
    e.begin(Sample{x:25.0,y:32.0,pressure:1.0,tilt:0.0},Brush{color:0xffffff00,size:20.0,pressure_size:0.0,stabilization:0.0,..Brush::default()});
    for x in (30..2000).step_by(5){e.sample(Sample{x:x as f32,y:32.0,pressure:1.0,tilt:0.0});}e.end();
    let carried=e.color_at(180,32,false);let fresh=e.color_at(800,32,false);let remixed=e.color_at(980,32,false);let final_color=e.color_at(1950,32,false);
    assert!(channel(carried,0)>0.2);assert!(channel(fresh,16)>0.9 && channel(fresh,8)>0.9);
    assert!(channel(remixed,8)<0.7);assert!(channel(final_color,16)>0.98 && channel(final_color,8)>0.98);
    let after=e.save().unwrap();assert!(e.undo());assert_eq!(e.save().unwrap(),before);assert!(e.redo());assert_eq!(e.save().unwrap(),after);
}

#[test]
fn wet_contact_preserves_local_color_and_clear_paper(){
    let pixels=(0..32).flat_map(|_|(0..32).map(|x|if x<16{0xff0000ff}else{0})).collect::<Vec<_>>();
    let field=brush_color::ContactField::new(&crate::tiles::Tiles::from_flat(&pixels,32,32),32,32,16.,16.,12.,0xffffff00,0.8);
    let wet=field.at(-0.75,0.);let clear=field.at(0.75,0.);
    assert_ne!(wet,clear);assert_eq!(clear,0xffffff00);assert!(channel(wet,16)<channel(clear,16));
    let mut prev=field.at(-0.4,0.);
    for i in 1..40{let c=field.at(-0.4+i as f32*0.02,0.);for shift in [0,8,16]{assert!((channel(c,shift)-channel(prev,shift)).abs()<0.15);}prev=c;}
}

#[test]
fn overlapping_washes_mix_locally_without_losing_history(){
    let mut e=Engine::new(160,96).unwrap();e.state.background=0xffffffff;
    e.begin(Sample{x:20.,y:48.,pressure:1.,tilt:0.},Brush{kind:4,color:0xff1458df,size:38.,pressure_size:0.,stabilization:0.,..Brush::default()});
    e.sample(Sample{x:140.,y:48.,pressure:1.,tilt:0.});e.end();let before=e.save().unwrap();
    e.command(&json!({"op":"brushEffects","mix":0.85,"pickup":0.55,"wetness":0.8})).unwrap();
    e.begin(Sample{x:80.,y:15.,pressure:1.,tilt:0.},Brush{kind:4,color:0xffffda35,size:42.,pressure_size:0.,stabilization:0.,..Brush::default()});
    e.sample(Sample{x:80.,y:85.,pressure:1.,tilt:0.});e.end();let after=e.save().unwrap();
    assert_ne!(e.color_at(80,48,false),e.color_at(80,22,false));
    assert!(e.undo());assert_eq!(e.save().unwrap(),before);assert!(e.redo());assert_eq!(e.save().unwrap(),after);
    let path=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../artifacts/local-water-mixing.ppm");
    let mut data=b"P6\n160 96\n255\n".to_vec();for y in 0..96{for x in 0..160{let c=e.color_at(x,y,true);data.extend([(c>>16)as u8,(c>>8)as u8,c as u8]);}}std::fs::write(path,data).unwrap();
}
