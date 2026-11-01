use super::*;
#[test]
fn validate_compute_shaders_without_adapter() {
    for source in [include_str!("paint.wgsl"), include_str!("composite.wgsl")] {
        let module = naga::front::wgsl::parse_str(source).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
    }
}
#[test]
fn cpu_renderer_preserves_artwork_and_history() {
    let mut e = Engine::new(64, 64).unwrap();
    e.begin(
        Sample {
            x: 20.0,
            y: 20.0,
            pressure: 1.0,
            tilt: 0.0,
        },
        Brush::default(),
    );
    e.end();
    let before = e.save().unwrap();
    let undo = e.undo.len();
    e.set_renderer("cpu").unwrap();
    assert_eq!(e.info()["renderer"], "cpu");
    assert_eq!(e.save().unwrap(), before);
    assert_eq!(e.undo.len(), undo);
    assert!(e.set_renderer("unsupported").is_err());
    assert!(e.undo());
    assert!(e.redo());
    assert_eq!(e.save().unwrap(), before);
}
fn gpu_engine(w: usize, h: usize) -> Engine {
    let mut e = Engine::new(w, h).unwrap();
    e.set_renderer("auto").unwrap();
    assert!(e.gpu.is_some(), "A physical GPU is required: {}", e.info());
    e
}
#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_brush_smudge_history() {
    let mut gpu = gpu_engine(192, 128);
    let mut cpu = Engine::new(192, 128).unwrap();
    println!("GPU: {}", gpu.info());
    for kind in [
        0, 1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 160, 163, 169, 170, 16, 23, 31, 64, 65, 66, 67, 68, 71, 72, 73, 74, 75, 76, 77, 78, 128, 130, 136, 139,
    ] {
        let brush = Brush {
            kind,
            color: 0xffd65a20,
            size: 42.0,
            stabilization: 0.0,
            pressure_size: 0.5,
            ..Brush::default()
        };
        for engine in [&mut gpu, &mut cpu] {
            engine.begin(
                Sample {
                    x: 32.0,
                    y: 45.0,
                    pressure: 0.4,
                    tilt: 0.3,
                },
                brush,
            );
            for x in (35..150).step_by(7) {
                engine.sample(Sample {
                    x: x as f32,
                    y: 45.0 + (x as f32 * 0.04).sin() * 13.0,
                    pressure: 0.8,
                    tilt: 0.3,
                });
                engine.flush_gpu();
            }
            engine.end();
        }
        let a = &gpu.state.layers[0].pixels;
        let b = &cpu.state.layers[0].pixels;
        let max_error = a
            .iter()
            .zip(b.iter())
            .flat_map(|(a, b)| {
                [0, 8, 16, 24].map(|s| ((a >> s & 255) as i32 - (b >> s & 255) as i32).abs())
            })
            .max()
            .unwrap();
        if max_error>4 {for(i,(x,y))in a.iter().zip(b.iter()).enumerate().filter(|(_, (x,y))|[0,8,16,24].iter().any(|s|((*x>>s&255) as i32-(*y>>s&255) as i32).abs()>4)).take(8){eprintln!("kind {kind}: ({},{}) {x:08x} vs {y:08x}",i%192,i/192);}}
        assert!(max_error <= 4, "kind {kind}, max channel error {max_error}");
        gpu.undo();
        gpu.redo();
    }
    assert!(gpu.info()["gpuBatches"].as_u64().unwrap() > 100);
    let batches = gpu.info()["gpuBatches"].as_u64().unwrap();
    gpu.begin(
        Sample {
            x: 50.0,
            y: 60.0,
            pressure: 1.0,
            tilt: 0.0,
        },
        Brush {
            size: 12.0,
            ..Brush::default()
        },
    );
    gpu.sample(Sample {
        x: 80.0,
        y: 60.0,
        pressure: 1.0,
        tilt: 0.0,
    });
    gpu.end();
    assert_eq!(
        gpu.info()["gpuBatches"].as_u64().unwrap(),
        batches,
        "Thin pens must avoid GPU submission overhead"
    );
    let saved = gpu.save().unwrap();
    let restored = Engine::load(&saved).unwrap();
    assert_eq!(gpu.state.layers[0].pixels, restored.state.layers[0].pixels);
    let before = gpu.state.layers[0].pixels.clone();
    gpu.begin(
        Sample {
            x: 80.0,
            y: 80.0,
            pressure: 1.0,
            tilt: 0.0,
        },
        Brush {
            size: 42.0,
            ..Brush::default()
        },
    );
    let undo_count = gpu.undo.len();
    gpu.sample(Sample {
        x: 100.0,
        y: 84.0,
        pressure: 0.8,
        tilt: 0.0,
    });
    let mid_stroke = gpu.save().unwrap();
    assert!(gpu.stroke.is_some(), "Saving must not end a GPU stroke");
    assert_eq!(undo_count, gpu.undo.len());
    assert_eq!(
        Engine::load(&mid_stroke).unwrap().state.layers[0].pixels,
        gpu.state.layers[0].pixels
    );
    gpu.sample(Sample {
        x: 115.0,
        y: 84.0,
        pressure: 0.8,
        tilt: 0.0,
    });
    gpu.flush_gpu();
    gpu.cancel();
    assert_eq!(before, gpu.state.layers[0].pixels);
}
#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_composite_selection_and_recovery() {
    let mut e = gpu_engine(96, 64);
    let modes = [
        "Normal",
        "Multiply",
        "Colour Burn",
        "Linear Burn",
        "Colour Dodge",
        "Subtract",
        "Exclusion",
        "Hard Light",
        "Screen",
        "Overlay",
        "Add",
        "Darker Colour",
        "Darken",
        "Lighten",
        "Difference",
    ];
    for i in 0..3 {
        if i > 0 {
            e.command(&json!({"op":"add"})).unwrap();
        }
        let mut pixels = e.state.layers[i].pixels.to_vec();
        for (n, pixel) in pixels.iter_mut().enumerate() {
            *pixel = ((n as u32 * 173 + 89) % 256) << 24
                | ((n as u32 * 379 + i as u32 * 123456) & 0xffffff);
        }
        e.state.layers[i].pixels = crate::tiles::Tiles::from_flat(&pixels, e.width, e.height);
    }
    e.state.layers[1].opacity = 0.47;
    e.state.layers[2].clipping = true;
    for mode in modes {
        for visible in [true, false] {
            for background in [true, false] {
                e.state.layers[1].blend = mode.into();
                e.state.layers[1].visible = visible;
                e.state.background_visible = background;
                let patch = e.render_patch(true).unwrap();
                assert!(e.gpu.is_some(), "{}", e.info());
                for y in 0..64 {
                    for x in 0..96 {
                        let a = patch[4 + y * 96 + x] as u32;
                        let b = e.color_at(x, y, true);
                        for shift in [0, 8, 16, 24] {
                            assert!(
                                ((a >> shift & 255) as i32 - (b >> shift & 255) as i32).abs() <= 1,
                                "{mode} at {x},{y}: {a:x} vs {b:x}"
                            );
                        }
                    }
                }
            }
        }
    }
    e.selection = Some((0..96 * 64).map(|i| i % 96 < 48).collect());
    e.state.active = 0;
    e.state.layers[0].alpha_lock = true;
    let before = e.state.layers[0].pixels.clone();
    e.begin(
        Sample {
            x: 47.0,
            y: 30.0,
            pressure: 1.0,
            tilt: 0.0,
        },
        Brush {
            size: 60.0,
            ..Brush::default()
        },
    );
    e.end();
    let after = e.state.layers[0].pixels.clone();
    assert_ne!(before, after);
    for i in 0..before.len() {
        if i % 96 >= 48 {
            assert_eq!(before[i], after[i]);
        }
        assert_eq!(before[i] >> 24, after[i] >> 24);
    }
    e.undo();
    assert_eq!(e.state.layers[0].pixels, before);
    e.redo();
    assert_eq!(e.state.layers[0].pixels, after);
    // Destroying a real device must replay the uncommitted batch on the CPU mirror.
    e.gpu.as_ref().unwrap().destroy_for_test();
    let mut cpu = Engine::load(&e.save().unwrap()).unwrap();
    cpu.selection = e.selection.clone();
    for engine in [&mut e, &mut cpu] {
        engine.begin(
            Sample {
                x: 20.0,
                y: 20.0,
                pressure: 0.6,
                tilt: 0.0,
            },
            Brush {
                size: 40.0,
                kind: 3,
                ..Brush::default()
            },
        );
        engine.sample(Sample {
            x: 30.0,
            y: 28.0,
            pressure: 0.9,
            tilt: 0.0,
        });
        engine.end();
    }
    assert!(e.gpu.is_none());
    assert!(e.renderer_error.is_some());
    assert_eq!(e.state.layers[0].pixels, cpu.state.layers[0].pixels);
}
#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_large_brush_timing() {
    let mut gpu = gpu_engine(1600, 1200);
    let mut cpu = Engine::new(1600, 1200).unwrap();
    for (label, kind, size) in [
        ("pen", 0, 12.0),
        ("airbrush", 2, 400.0),
        ("smudge", 130, 400.0),
    ] {
        for (backend, engine) in [("cpu", &mut cpu), ("auto", &mut gpu)] {
            engine
                .command(&json!({"op":"fill","color":0xff3498abu32}))
                .unwrap();
            engine.render_patch(true);
            let start = std::time::Instant::now();
            engine.begin(
                Sample {
                    x: 150.0,
                    y: 300.0,
                    pressure: 1.0,
                    tilt: 0.0,
                },
                Brush {
                    kind,
                    size,
                    stabilization: 0.0,
                    ..Brush::default()
                },
            );
            for batch in 0..20 {
                for n in 0..4 {
                    let x = 150.0 + (batch * 4 + n) as f32 * 14.0;
                    engine.sample(Sample {
                        x,
                        y: 500.0 + (x * 0.005).sin() * 200.0,
                        pressure: 0.8,
                        tilt: 0.0,
                    });
                }
                engine.flush_gpu();
                engine.render_patch(false);
            }
            engine.end();
            println!("{label} {backend}: {} ms", start.elapsed().as_millis());
        }
    }
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_affine_transform_composite_and_undo() {
    let mut e=gpu_engine(128,96);
    e.begin(Sample{x:45.,y:40.,pressure:1.,tilt:0.},Brush{size:48.,pressure_size:0.,color:0xff2695c8,..Brush::default()});e.end();
    e.render_patch(true).unwrap();let original=e.save().unwrap();let undo=e.undo.len();
    e.transform_snapshot();assert_eq!(original,e.save().unwrap());assert_eq!(undo,e.undo.len());
    e.command(&json!({"op":"transformMatrix","matrix":[0.7,0.4,-0.4,0.7,25,2]})).unwrap();
    for step in 0..3 {
        let patch=e.render_patch(true).unwrap();assert!(e.gpu.is_some());
        for y in 0..96 {for x in 0..128 {let a=patch[4+y*128+x]as u32;let b=e.color_at(x,y,true);
            for shift in [0,8,16,24] {assert!(((a>>shift&255)as i32-(b>>shift&255)as i32).abs()<=1);}
        }}
        if step==0 {assert!(e.undo());assert_eq!(original,e.save().unwrap());}else if step==1 {assert!(e.redo());}
    }
}

#[test]
#[ignore="Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_symmetry_masks_and_fallback(){
    let mut gpu=gpu_engine(192,128);let mut cpu=Engine::new(192,128).unwrap();
    for e in [&mut gpu,&mut cpu]{
        e.command(&json!({"op":"symmetry","mode":"Quadrant"})).unwrap();
        e.begin(Sample{x:28.0,y:25.0,pressure:1.0,tilt:0.0},Brush{size:40.0,pressure_size:0.0,stabilization:0.0,color:0xffdd5533,..Brush::default()});e.sample(Sample{x:60.0,y:45.0,pressure:1.0,tilt:0.0});e.end();
        e.command(&json!({"op":"mask"})).unwrap();e.begin(Sample{x:28.0,y:25.0,pressure:1.0,tilt:0.0},Brush{size:18.0,pressure_size:0.0,color:0xff000000,..Brush::default()});e.end();
    }
    assert_eq!(gpu.state.layers[0].pixels,cpu.state.layers[0].pixels);assert_eq!(gpu.state.layers[0].mask,cpu.state.layers[0].mask);
    let a=gpu.render_patch(true).unwrap();let b=cpu.render_patch(true).unwrap();assert_eq!(a,b);assert!(gpu.gpu.is_some());
    for e in [&mut gpu,&mut cpu]{e.command(&json!({"op":"mask","action":"delete"})).unwrap();e.command(&json!({"op":"mask","action":"pixels"})).unwrap();}
    gpu.gpu.as_ref().unwrap().destroy_for_test();
    for e in [&mut gpu,&mut cpu]{e.begin(Sample{x:40.0,y:80.0,pressure:1.0,tilt:0.0},Brush{size:40.0,pressure_size:0.0,color:0xff55aa66,..Brush::default()});e.end();}
    assert!(gpu.gpu.is_none());assert_eq!(gpu.state.layers[0].pixels,cpu.state.layers[0].pixels);
}

#[test]
#[ignore="Requires a physical Metal or Vulkan adapter"]
fn many_empty_layers_render_background_then_gpu_stroke(){
    let mut e=gpu_engine(512,512);
    for _ in 0..70{e.command(&json!({"op":"add"})).unwrap();}
    let frame=e.render_patch(true).unwrap();assert_eq!(frame[4] as u32,e.state.background);
    e.begin(Sample{x:100.,y:100.,pressure:1.,tilt:0.},Brush{size:48.,color:0xffff0000,pressure_size:0.,..Brush::default()});e.end();
    let patch=e.render_patch(true).unwrap();assert_eq!(patch[4+100*512+100] as u32,0xffff0000);
    assert_eq!(e.info()["renderer"],"gpu");
}

#[test]
#[ignore="Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_fade_and_midstroke_eraser_match_cpu(){
    fn draw(e:&mut Engine){
        e.command(&json!({"op":"fill","color":0xffcc8844u32})).unwrap();
        e.begin(Sample{x:20.,y:60.,pressure:1.,tilt:0.},Brush{size:64.,fade_length:300.,pressure_size:0.,stabilization:0.,color:0xff2255cc,..Brush::default()});
        e.sample(Sample{x:620.,y:60.,pressure:1.,tilt:0.});e.end();
        e.begin(Sample{x:20.,y:140.,pressure:1.,tilt:0.},Brush{size:48.,pressure_size:0.,stabilization:0.,..Brush::default()});
        e.sample(Sample{x:80.,y:140.,pressure:1.,tilt:0.});
        e.update_brush(Brush{kind:66,size:80.,color:180,pressure_size:0.,stabilization:0.,..Brush::default()});e.sample(Sample{x:300.,y:140.,pressure:1.,tilt:0.});e.end();
    }
    let mut gpu=gpu_engine(640,200);let mut cpu=Engine::new(640,200).unwrap();draw(&mut gpu);draw(&mut cpu);
    let error=gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter()).flat_map(|(a,b)|[0,8,16,24].map(|shift|(((a>>shift)&255) as i32-((b>>shift)&255) as i32).abs())).max().unwrap();
    assert!(error<=3,"CPU/GPU fade mismatch {error}");assert_eq!(gpu.info()["renderer"],"gpu");
}

#[test]
#[ignore="Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_feathered_selection_matches_cpu(){
    fn paint(e:&mut Engine){
        e.command(&json!({"op":"select","shape":"Rectangle","points":[[30,20],[100,100]]})).unwrap();e.command(&json!({"op":"selectionFeather","radius":8})).unwrap();
        e.begin(Sample{x:10.,y:60.,pressure:1.,tilt:0.},Brush{size:50.,pressure_size:0.,stabilization:0.,color:0xff2288cc,..Brush::default()});e.sample(Sample{x:120.,y:60.,pressure:1.,tilt:0.});e.end();
    }
    let mut gpu=gpu_engine(128,128);let mut cpu=Engine::new(128,128).unwrap();paint(&mut gpu);paint(&mut cpu);
    let error=gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter()).flat_map(|(a,b)|[0,8,16,24].map(|shift|(((a>>shift)&255) as i32-((b>>shift)&255) as i32).abs())).max().unwrap();assert!(error<=3,"Feather CPU/GPU difference {error}");assert!(gpu.state.layers[0].pixels[60*128+30]>>24>0);assert_eq!(gpu.state.layers[0].pixels[60*128+5]>>24,0);
}

#[test]
#[ignore = "requires a physical GPU"]
fn physical_gpu_timed_fade_matches_cpu(){
    let draw=|gpu:bool|{
        let mut e=Engine::new(800,100).unwrap();if gpu{e.set_renderer("auto").unwrap();assert!(e.gpu.is_some());}
        e.begin(Sample{x:20.,y:50.,pressure:1.,tilt:0.},Brush{size:64.,fade_length:180.,pressure_size:0.,stabilization:0.,..Brush::default()});e.set_sample_time(0.);
        let mut time=0.;for i in 1..=150{time+=if i<75{30.}else{2.};e.sample_timed(Sample{x:20.+i as f32*5.,y:50.,pressure:1.,tilt:0.},time);if i%12==0{e.flush_gpu();}}
        e.end();e.state.layers[0].pixels.clone()
    };
    let cpu=draw(false);let gpu=draw(true);
    let error=cpu.iter().zip(gpu.iter()).map(|(a,b)|((a>>24) as i32-(b>>24) as i32).abs()).max().unwrap();assert!(error<=2,"timed Fade alpha mismatch: {error}");
}

#[test]
#[ignore = "requires a physical GPU"]
fn physical_gpu_fade_start_and_tap_match_cpu(){
    let draw=|gpu:bool|{
        let mut e=Engine::new(400,160).unwrap();if gpu{e.set_renderer("auto").unwrap();assert!(e.gpu.is_some());}
        let b=Brush{size:40.,fade_length:100.,pressure_size:0.,stabilization:0.,..Brush::default()};
        e.begin(Sample{x:30.,y:50.,pressure:1.,tilt:0.},b);e.set_sample_time(0.);
        e.sample_timed(Sample{x:30.,y:50.,pressure:1.,tilt:0.},2.);
        for i in 1..=60{e.sample_timed(Sample{x:30.+i as f32*5.,y:50.,pressure:1.,tilt:0.},2.+i as f32*2.);}e.end();
        assert!(e.state.layers[0].pixels[50*400+30]>>24<150);
        e.begin(Sample{x:30.,y:120.,pressure:1.,tilt:0.},b);e.set_sample_time(0.);e.end();assert!(e.state.layers[0].pixels[120*400+30]>>24>0);e.state.layers[0].pixels.clone()
    };
    let a=draw(false);let b=draw(true);let error=a.iter().zip(b.iter()).map(|(a,b)|((a>>24) as i32-(b>>24) as i32).abs()).max().unwrap();assert!(error<=2,"Fade cap/tap mismatch: {error}");
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_multicolor_and_group_appearance_match_cpu(){
    let mut gpu=gpu_engine(160,96);let mut cpu=Engine::new(160,96).unwrap();
    for e in [&mut gpu,&mut cpu]{
        e.command(&json!({"op":"brushEffects","mode":"Rainbow","cycle":120})).unwrap();
        e.begin(Sample{x:20.,y:48.,pressure:1.,tilt:0.},Brush{size:40.,pressure_size:0.,stabilization:0.,..Brush::default()});
        e.sample(Sample{x:140.,y:48.,pressure:1.,tilt:0.});e.end();
    }
    let difference=gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter()).map(|(a,b)|[0,8,16,24].iter().map(|s|((a>>s&255)as i32-(b>>s&255)as i32).abs()).max().unwrap()).max().unwrap();assert!(difference<=4,"CPU/GPU rainbow difference {difference}");
    assert!(gpu.info()["gpuBatches"].as_u64().unwrap()>0);
    for e in [&mut gpu,&mut cpu]{e.command(&json!({"op":"group","action":"create"})).unwrap();e.command(&json!({"op":"group","action":"update","id":1,"opacity":0.4,"blend":"Multiply"})).unwrap();let patch=e.render_patch(true).unwrap();assert!(patch.len()>4);}
    for (a,b) in gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter()){assert!(((a>>24)as i32-(b>>24)as i32).abs()<=4);}
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_across_width_and_bristle_colors_match_cpu(){
    for layout in ["Across","Bristles"] {for kind in [0,7,11,13]{
        let mut gpu=gpu_engine(160,112);let mut cpu=Engine::new(160,112).unwrap();
        for e in [&mut gpu,&mut cpu]{e.command(&json!({"op":"brushEffects","mode":"Palette","layout":layout,"cycle":900,"palette":[0xffdd5454u32,0xffd8be65u32,0xff66ae9bu32,0xff687db9u32]})).unwrap();
            e.begin(Sample{x:20.,y:30.,pressure:1.,tilt:0.},Brush{kind,size:40.,pressure_size:0.,stabilization:0.,..Brush::default()});e.sample(Sample{x:75.,y:30.,pressure:1.,tilt:0.});e.sample(Sample{x:135.,y:78.,pressure:1.,tilt:0.});e.end();}
        let total:f64=gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter()).map(|(a,b)|[0,8,16,24].iter().map(|s|((a>>s&255)as i32-(b>>s&255)as i32).unsigned_abs()as f64).sum::<f64>()).sum();
        assert!(total/(160.*112.*4.)<1.5,"Color mismatch {layout} {kind}: {total}");assert!(gpu.info()["gpuBatches"].as_u64().unwrap()>0);
    }}
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_inkflow_timed_pressure_curves_and_soft_erasers_match_cpu(){
    let mut gpu=gpu_engine(256,128);let mut cpu=Engine::new(256,128).unwrap();
    for kind in [0,2,4,8,9,66,68,72,73] {
        for e in [&mut gpu,&mut cpu] {
            e.state.layers[0].pixels.fill(if kind>=64{0xff204080}else{0});
            let b=Brush{kind,size:48.0,pressure_size:0.7,stabilization:0.6,flow:0.65,pressure_opacity:0.4,..Brush::default()};
            e.begin_timed(Sample{x:25.0,y:64.0,pressure:1.0,tilt:0.0},b,0.0);
            for i in 1..=40 {
                e.sample_timed(Sample{x:25.0+i as f32*5.0,y:64.0+(i as f32*0.15).sin()*25.0,
                    pressure:0.2+i as f32*0.015,tilt:0.0},i as f32*4.0);
                if i%7==0{e.flush_gpu();}
            }
            e.end();
        }
        let max=gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter())
            .flat_map(|(a,b)|[0,8,16,24].map(|s|((a>>s&255)as i32-(b>>s&255)as i32).abs())).max().unwrap();
        assert!(max<=4,"Inkflow kind {kind}: channel difference {max}");
    }
    assert!(gpu.info()["gpuBatches"].as_u64().unwrap()>0);
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_reusable_shape_resets_pending_dabs_and_restores_previous_areas(){
    let mut gpu=gpu_engine(256,192);let mut cpu=Engine::new(256,192).unwrap();
    let brush=Brush{kind:2,size:44.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
    for i in 0..25{
        let x=25.0+i as f32*4.0;
        let points=[Sample{x,y:40.0,pressure:1.0,tilt:0.0},Sample{x:x+65.0,y:115.0,pressure:1.0,tilt:0.0}];
        for e in [&mut gpu,&mut cpu]{e.replace_stroke(&points,brush);if i%3==0{e.render_patch(false);}}
    }
    for e in [&mut gpu,&mut cpu]{e.end();}
    let max=gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter())
        .flat_map(|(a,b)|[0,8,16,24].map(|s|((a>>s&255)as i32-(b>>s&255)as i32).abs())).max().unwrap();
    assert!(max<=4,"Reusable shape CPU/GPU difference {max}");
    for e in [&mut gpu,&mut cpu]{assert_eq!(e.undo.len(),1);assert!(e.undo());assert!(e.state.layers[0].pixels.iter().all(|c|c==0));}
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_paint_and_cached_many_layer_backdrop_match_cpu(){
    let mut gpu=gpu_engine(192,128);let mut cpu=Engine::new(192,128).unwrap();
    for e in [&mut gpu,&mut cpu]{
        for i in 0..24{
            if i>0{e.command(&json!({"op":"add"})).unwrap();}
            e.state.layers[i].pixels.fill(0x60304080+i as u32*0x00030201);
        }
        e.render_patch(true);
    }
    for n in 0..8{
        for e in [&mut gpu,&mut cpu]{
            e.begin_timed(Sample{x:30.0,y:35.0+n as f32*7.0,pressure:0.5,tilt:0.0},Brush{kind:2,size:42.0,pressure_size:0.0,..Brush::default()},0.0);
            e.sample_timed(Sample{x:160.0,y:45.0+n as f32*7.0,pressure:0.7,tilt:0.0},8.0);e.end();
        }
        let a=gpu.render_patch(true).unwrap();let b=cpu.render_patch(true).unwrap();
        let max=a[4..].iter().zip(&b[4..]).flat_map(|(a,b)|[0,8,16,24].map(|s|((*a as u32>>s&255)as i32-(*b as u32>>s&255)as i32).abs())).max().unwrap();
        assert!(max<=4,"Cached GPU paint difference {max}");
    }
    assert!(gpu.info()["gpuBatches"].as_u64().unwrap()>0);
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_paint_mixing_matches_cpu(){
    // Pickup only, wetness only, and both: each mixes from the pre-stroke snapshot, so the GPU can paint it.
    for (pickup,wetness) in [(0.7,0.0),(0.0,0.8),(0.6,0.6)] {for kind in [0,2,8,23,33]{
        let mut gpu=gpu_engine(192,128);let mut cpu=Engine::new(192,128).unwrap();
        for e in [&mut gpu,&mut cpu]{
            // Two colours underneath, so pickup and the contact field have something to mix with.
            let under:Vec<u32>=(0..192*128).map(|i|if i%192<96{0xffc0203a}else{0xc02a3cb8}).collect();e.state.layers[0].pixels=tiles::Tiles::from_flat(&under,192,128);
            e.command(&json!({"op":"brushEffects","mix":0.8,"pickup":pickup,"wetness":wetness})).unwrap();
            e.begin(Sample{x:40.,y:64.,pressure:1.,tilt:0.},Brush{kind,size:48.,color:0xffffe060,pressure_size:0.,stabilization:0.,..Brush::default()});
            for i in 1..=20{e.sample(Sample{x:40.+i as f32*6.,y:64.+(i as f32*0.3).sin()*20.,pressure:1.,tilt:0.});if i%6==0{e.flush_gpu();}}
            e.end();
        }
        assert!(gpu.info()["gpuBatches"].as_u64().unwrap()>0,"Mixing stroke did not use the GPU");
        let pixels=|e:&Engine|e.state.layers[0].pixels.to_vec();
        let (g,c)=(pixels(&gpu),pixels(&cpu));
        let total:f64=g.iter().zip(&c).map(|(a,b)|[0,8,16,24].iter().map(|s|((*a>>s&255)as i32-(*b>>s&255)as i32).unsigned_abs()as f64).sum::<f64>()).sum();
        let worst=g.iter().zip(&c).map(|(a,b)|[0,8,16,24].iter().map(|s|((*a>>s&255)as i32-(*b>>s&255)as i32).abs()).max().unwrap()).max().unwrap();
        assert!(total/(192.*128.*4.)<0.5 && worst<=6,"Mixing mismatch pickup {pickup} wetness {wetness} kind {kind}: mean {} worst {worst}",total/(192.*128.*4.));
    }}
}
