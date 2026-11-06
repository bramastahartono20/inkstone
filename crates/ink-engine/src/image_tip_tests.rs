use super::*;

fn encode(bytes: &[u8]) -> String {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in bytes.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = (b[0] as u32) << 16 | (b[1] as u32) << 8 | b[2] as u32;
        for i in 0..4 {
            out.push(if i <= chunk.len() { A[(n >> (18 - 6 * i) & 63) as usize] as char } else { '=' });
        }
    }
    out
}
fn tip(e: &mut Engine, shape: &[u8], size: usize, grain: Option<(&[u8], usize)>, extra: Value) {
    let mut v = json!({"op":"brushImage","key":"test","shape":encode(shape),"size":size});
    if let Some((g, n)) = grain { v["grain"] = json!(encode(g)); v["grainSize"] = json!(n); v["grainScale"] = json!(1.0); }
    for (k, value) in extra.as_object().unwrap() { v[k] = value.clone(); }
    e.command(&v).unwrap();
}
fn dab(e: &mut Engine, kind: u8, x: f32, y: f32, size: f32) {
    e.begin(Sample { x, y, pressure: 1.0, tilt: 0.0 }, Brush { kind, size, pressure_size: 0.0, stabilization: 0.0, color: 0xff2050a0, ..Brush::default() });
    e.end();
}
fn alpha(e: &Engine, x: usize, y: usize) -> u32 { e.state.layers[0].pixels[y * e.width + x] >> 24 }

#[test]
fn image_tip_paints_its_shape_including_square_corners() {
    let mut e = Engine::new(128, 128).unwrap();
    // Left half black, right half white.
    let shape: Vec<u8> = (0..32 * 32).map(|i| if i % 32 >= 16 { 255 } else { 0 }).collect();
    tip(&mut e, &shape, 32, None, json!({}));
    dab(&mut e, image_tip::KIND, 64.0, 64.0, 60.0);
    assert!(alpha(&e, 80, 64) > 200, "right half paints");
    assert_eq!(alpha(&e, 48, 64), 0, "left half stays clear");
    // (90, 90) is outside the round dab (radius 30) but inside its square.
    assert!(alpha(&e, 90, 90) > 200, "square corner paints");
    assert!(e.undo());
    assert!(e.state.layers[0].pixels.iter().all(|p| p >> 24 == 0));
}

#[test]
fn image_tip_grain_is_fixed_to_the_canvas_and_rotation_turns_the_shape() {
    let mut e = Engine::new(128, 128).unwrap();
    let shape = vec![255u8; 16 * 16];
    // Vertical stripes two texels wide.
    let grain: Vec<u8> = (0..8 * 8).map(|i| if (i % 8) / 2 % 2 == 0 { 255 } else { 0 }).collect();
    tip(&mut e, &shape, 16, Some((&grain, 8)), json!({}));
    dab(&mut e, image_tip::KIND, 64.0, 64.0, 40.0);
    assert!(alpha(&e, 64, 64) > 200 && alpha(&e, 66, 64) == 0 && alpha(&e, 68, 64) > 200);

    let mut turned = Engine::new(128, 128).unwrap();
    let half: Vec<u8> = (0..32 * 32).map(|i| if i % 32 >= 16 { 255 } else { 0 }).collect();
    tip(&mut turned, &half, 32, None, json!({"rotation": std::f32::consts::FRAC_PI_2}));
    dab(&mut turned, image_tip::KIND, 64.0, 64.0, 60.0);
    assert!(alpha(&turned, 64, 80) > 200, "white half turns to the bottom");
    assert_eq!(alpha(&turned, 64, 48), 0);
}

#[test]
fn image_tip_spacing_erasing_and_missing_key() {
    let mut e = Engine::new(256, 64).unwrap();
    assert_eq!(e.command(&json!({"op":"brushImage","key":"other"})).unwrap()["missing"], true);
    let dot: Vec<u8> = (0..16 * 16).map(|i| { let (x, y) = (i % 16, i / 16); if (x as i32 - 8).pow(2) + (y as i32 - 8).pow(2) < 9 { 255 } else { 0 } }).collect();
    tip(&mut e, &dot, 16, None, json!({"spacing": 2.0}));
    assert!(e.command(&json!({"op":"brushImage","key":"test"})).unwrap().get("missing").is_none());
    let p = |x| Sample { x, y: 32.0, pressure: 1.0, tilt: 0.0 };
    let brush = Brush { kind: image_tip::KIND, size: 20.0, pressure_size: 0.0, stabilization: 0.0, ..Brush::default() };
    e.begin(p(20.0), brush); e.sample(p(220.0)); e.end();
    // Dabs 40 px apart leave gaps between them.
    let row: Vec<u32> = (0..256).map(|x| alpha(&e, x, 32)).collect();
    assert!(row[20] > 200 && row[40] == 0 && row[60] > 200, "{:?}", &row[15..65]);
    let painted = e.state.layers[0].pixels.clone();
    e.begin(p(20.0), Brush { kind: 64 + image_tip::KIND, ..brush }); e.sample(p(220.0)); e.end();
    assert!(alpha(&e, 20, 32) < painted[32 * 256 + 20] >> 24, "image eraser removes paint");
    e.command(&json!({"op":"brushImage","clear":true})).unwrap();
    assert_eq!(e.command(&json!({"op":"brushImage","key":"test"})).unwrap()["missing"], true);
    assert!(e.command(&json!({"op":"brushImage","shape":"AAAA","size":4})).is_err(), "wrong pixel count");
}

#[test]
#[ignore = "Requires a physical Metal or Vulkan adapter"]
fn physical_gpu_image_tip_matches_cpu() {
    let shape: Vec<u8> = (0..64 * 64).map(|i| { let (x, y) = (i % 64, i / 64); ((x * 4) ^ (y * 3)) as u8 }).collect();
    let grain: Vec<u8> = (0..32 * 32).map(|i| ((i * 37) % 251) as u8).collect();
    // Paint strokes are glazed (blend 1 is Multiply); erasers are not.
    for (kind, rolling, blend) in [(image_tip::KIND, false, 0), (image_tip::KIND, true, 1), (image_tip::KIND, false, 3), (64 + image_tip::KIND, true, 0)] {
        let mut gpu = Engine::new(200, 120).unwrap();
        gpu.set_renderer("auto").unwrap();
        assert!(gpu.gpu.is_some());
        let mut cpu = Engine::new(200, 120).unwrap();
        for e in [&mut gpu, &mut cpu] {
            e.command(&json!({"op":"fill","color":0xffffffffu32})).unwrap();
            tip(e, &shape, 64, Some((&grain, 32)), json!({"follow":1.0,"jitter":0.5,"spacing":0.3,"rolling":rolling,"grainScale":if rolling {1.2} else {1.0},"sizeJitter":0.5,"opacityJitter":0.4,"scatter":0.8,"blend":blend}));
            e.begin(Sample { x: 30.0, y: 40.0, pressure: 1.0, tilt: 0.0 }, Brush { kind, size: 50.0, pressure_size: 0.0, stabilization: 0.0, color: if kind >= 64 { 255 } else { 0xff883322 }, ..Brush::default() });
            e.sample(Sample { x: 120.0, y: 70.0, pressure: 1.0, tilt: 0.0 });
            e.sample(Sample { x: 170.0, y: 40.0, pressure: 1.0, tilt: 0.0 });
            e.end();
        }
        assert!(gpu.info()["gpuBatches"].as_u64().unwrap() > 0, "{}", gpu.info());
        let total: f64 = gpu.state.layers[0].pixels.iter().zip(cpu.state.layers[0].pixels.iter())
            .map(|(a, b)| [0, 8, 16, 24].iter().map(|s| ((a >> s & 255) as i32 - (b >> s & 255) as i32).unsigned_abs() as f64).sum::<f64>()).sum();
        assert!(total / (200.0 * 120.0 * 4.0) < 1.0, "kind {kind} blend {blend}: mean difference {}", total / (200.0 * 120.0 * 4.0));
    }
}

#[test]
fn speed_fades_timed_strokes_only() {
    let painted = |speed_opacity: f64, timed: bool| {
        let mut e = Engine::new(400, 60).unwrap();
        tip(&mut e, &vec![255u8; 16 * 16], 16, None, json!({"speedOpacity": speed_opacity, "spacing": 0.1}));
        let brush = Brush { kind: image_tip::KIND, size: 20.0, pressure_size: 0.0, stabilization: 0.0, opacity: 0.5, ..Brush::default() };
        let p = |x| Sample { x, y: 30.0, pressure: 1.0, tilt: 0.0 };
        if timed {
            e.begin_timed(p(20.0), brush, 0.0);
            for i in 1..=30 { e.sample_timed(p(20.0 + i as f32 * 12.0), i as f32 * 2.0); }
        } else {
            e.begin(p(20.0), brush);
            e.sample(p(380.0));
        }
        e.end();
        alpha(&e, 200, 30)
    };
    assert!(painted(1.0, true) < painted(0.0, true) * 3 / 4, "a fast stroke fades");
    assert_eq!(painted(1.0, false), painted(0.0, false), "untimed input has no speed");
}

#[test]
fn rolling_grain_follows_the_stroke_and_jitter_varies_dabs() {
    // Horizontal stripes in the grain: rolling grain keeps them along the stroke in any direction.
    let shape = vec![255u8; 16 * 16];
    let grain: Vec<u8> = (0..16 * 16).map(|i| if (i / 16) / 4 % 2 == 0 { 255 } else { 0 }).collect();
    let stroke = |e: &mut Engine, a: (f32, f32), b: (f32, f32)| {
        let brush = Brush { kind: image_tip::KIND, size: 32.0, pressure_size: 0.0, stabilization: 0.0, ..Brush::default() };
        e.begin(Sample { x: a.0, y: a.1, pressure: 1.0, tilt: 0.0 }, brush);
        e.sample(Sample { x: b.0, y: b.1, pressure: 1.0, tilt: 0.0 });
        e.end();
    };
    let mut across = Engine::new(160, 160).unwrap();
    tip(&mut across, &shape, 16, Some((&grain, 16)), json!({"rolling":true,"grainScale":1.0,"spacing":0.05}));
    stroke(&mut across, (80.0, 20.0), (80.0, 140.0));
    // The stroke runs down, so its stripes run down too: one column is all paint or all clear.
    let column = |x: usize| (40..120).map(|y| alpha(&across, x, y) > 128).collect::<Vec<_>>();
    let painted = (66..94).filter(|x| column(*x).iter().all(|v| *v)).count();
    let clear = (66..94).filter(|x| column(*x).iter().all(|v| !*v)).count();
    assert!(painted >= 6 && clear >= 6, "painted {painted}, clear {clear}");

    let mut jittered = Engine::new(300, 100).unwrap();
    tip(&mut jittered, &shape, 16, None, json!({"spacing":1.5,"sizeJitter":1.0,"scatter":1.0}));
    stroke(&mut jittered, (20.0, 50.0), (280.0, 50.0));
    let first = jittered.state.layers[0].pixels.clone();
    let rows: std::collections::HashSet<usize> = (0..300).filter_map(|x| (0..100).find(|y| alpha(&jittered, x, *y) > 0)).collect();
    assert!(rows.len() > 4, "dabs scatter to different heights");
    jittered.undo();
    stroke(&mut jittered, (20.0, 50.0), (280.0, 50.0));
    assert_eq!(jittered.state.layers[0].pixels, first, "the same stroke jitters the same way");
}

/// Painted height of column `x`.
fn width_at(e: &Engine, x: usize) -> usize { (0..e.height).filter(|y| alpha(e, x, *y) > 40).count() }

#[test]
fn tapers_thin_both_ends_after_the_stroke_ends() {
    let mut e = Engine::new(900, 120).unwrap();
    tip(&mut e, &vec![255u8; 16 * 16], 16, None, json!({"spacing":0.1,"taperStart":0.3,"taperEnd":0.3,"taperSize":1.0}));
    let brush = Brush { kind: image_tip::KIND, size: 40.0, pressure_size: 0.0, stabilization: 0.0, ..Brush::default() };
    let p = |x| Sample { x, y: 60.0, pressure: 1.0, tilt: 0.0 };
    e.begin(p(20.0), brush);
    for i in 1..=86 { e.sample(p(20.0 + i as f32 * 10.0)); }
    e.end();
    // Taper length is 0.3 × 400 px = 120 px at each end.
    let (start, middle, end) = (width_at(&e, 40), width_at(&e, 450), width_at(&e, 860));
    assert!(middle >= 38, "middle {middle}");
    assert!(start < middle / 2 && end < middle / 2, "start {start} middle {middle} end {end}");
    // Undo still restores the blank canvas after the replay.
    assert!(e.undo());
    assert!(e.state.layers[0].pixels.iter().all(|c| c >> 24 == 0));
}

#[test]
fn tilt_grows_the_dab_and_speed_thins_it() {
    let dab_width = |tilt: f32, extra: Value| {
        let mut e = Engine::new(300, 300).unwrap();
        tip(&mut e, &vec![255u8; 16 * 16], 16, None, extra);
        e.begin(Sample { x: 150.0, y: 150.0, pressure: 1.0, tilt }, Brush { kind: image_tip::KIND, size: 40.0, pressure_size: 0.0, stabilization: 0.0, ..Brush::default() });
        e.end();
        width_at(&e, 150)
    };
    let upright = dab_width(0.0, json!({"tiltSize":1.0}));
    let leaning = dab_width(1.5, json!({"tiltSize":1.0}));
    assert!(leaning > upright * 2, "upright {upright} leaning {leaning}");
    assert_eq!(dab_width(1.5, json!({})), upright, "no tilt setting, no change");

    let stroke_width = |speed_size: f64| {
        let mut e = Engine::new(500, 120).unwrap();
        tip(&mut e, &vec![255u8; 16 * 16], 16, None, json!({"speedSize": speed_size, "spacing": 0.1}));
        let brush = Brush { kind: image_tip::KIND, size: 40.0, pressure_size: 0.0, stabilization: 0.0, ..Brush::default() };
        let p = |x| Sample { x, y: 60.0, pressure: 1.0, tilt: 0.0 };
        e.begin_timed(p(20.0), brush, 0.0);
        for i in 1..=40 { e.sample_timed(p(20.0 + i as f32 * 11.0), i as f32 * 2.0); }
        e.end();
        width_at(&e, 250)
    };
    assert!(stroke_width(1.0) < stroke_width(0.0) * 3 / 4, "a fast stroke thins");
}

#[test]
fn stroke_blend_modes_apply_once_per_stroke() {
    let shade_after = |blend: u64| {
        let mut e = Engine::new(200, 80).unwrap();
        e.command(&json!({"op":"fill","color":0xff808080u32})).unwrap();
        tip(&mut e, &vec![255u8; 16 * 16], 16, None, json!({"blend": blend, "spacing": 0.05}));
        let brush = Brush { kind: image_tip::KIND, size: 40.0, pressure_size: 0.0, stabilization: 0.0, color: 0xff808080, ..Brush::default() };
        e.begin(Sample { x: 20.0, y: 40.0, pressure: 1.0, tilt: 0.0 }, brush);
        e.sample(Sample { x: 180.0, y: 40.0, pressure: 1.0, tilt: 0.0 });
        e.end();
        e.state.layers[0].pixels[40 * 200 + 100] & 0xff
    };
    assert_eq!(shade_after(0), 0x80, "Normal");
    // Overlapping dabs of one stroke would compound to black; the stroke blends once instead.
    assert!((0x3e..=0x42).contains(&shade_after(1)), "Multiply {:#x}", shade_after(1));
    assert!((0xbd..=0xc1).contains(&shade_after(2)), "Screen {:#x}", shade_after(2));
    assert_eq!(shade_after(3), 0xff, "Add");
}

#[test]
fn glazed_strokes_cap_opacity_within_a_stroke() {
    let mut e = Engine::new(300, 80).unwrap();
    tip(&mut e, &vec![255u8; 16 * 16], 16, None, json!({"spacing": 0.02}));
    let brush = Brush { kind: image_tip::KIND, size: 30.0, opacity: 0.5, pressure_size: 0.0, stabilization: 0.0, color: 0xff000000, ..Brush::default() };
    let p = |x| Sample { x, y: 40.0, pressure: 1.0, tilt: 0.0 };
    // Dozens of dabs overlap here, yet the stroke stays at its 50 % opacity.
    e.begin(p(20.0), brush); e.sample(p(280.0)); e.end();
    assert!((126..=129).contains(&alpha(&e, 150, 40)), "one stroke {}", alpha(&e, 150, 40));
    // A second stroke glazes over the first.
    e.begin(p(20.0), brush); e.sample(p(280.0)); e.end();
    assert!((189..=193).contains(&alpha(&e, 150, 40)), "two strokes {}", alpha(&e, 150, 40));
}
