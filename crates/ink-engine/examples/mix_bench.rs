//! Paint Mixing versus plain strokes on a large, painted, many-layer canvas.
//! `cargo run --release --example mix_bench` (set INKSTONE_GPU=1 to request the GPU renderer).
use inkstone::{Brush, Engine, Sample};
use serde_json::json;
use std::time::Instant;

fn main() {
    let (w, h, layers) = (3000usize, 2000usize, 11usize);
    let mut e = Engine::new(w, h).unwrap();
    if std::env::var("INKSTONE_GPU").is_ok() {
        e.command(&json!({"op":"renderer","mode":"auto"})).ok();
    }
    // Paint every layer so mixing has pigment underneath and nothing is blank.
    for i in 0..layers {
        if i > 0 { e.command(&json!({"op":"add"})).unwrap(); }
        let color = [0xffc0203a_u32, 0xff2a3cb8, 0xff7a2bb0, 0xffd8d070][i % 4];
        let b = Brush { size: 400.0, opacity: 0.5, color, stabilization: 0.0, ..Brush::default() };
        for row in 0..4 {
            let y = 250.0 + row as f32 * 450.0;
            e.begin(Sample { x: 100.0, y, pressure: 1.0, tilt: 0.0 }, b);
            for s in 1..=30 { e.sample(Sample { x: 100.0 + s as f32 * 95.0, y, pressure: 1.0, tilt: 0.0 }); }
            e.end();
        }
    }
    e.render_patch(true);
    for (name, mix) in [("plain", 0.0), ("mixing", 0.8)] {
        e.command(&json!({"op":"brushEffects","mix":mix,"pickup":0.6,"wetness":0.6})).unwrap();
        for size in [24.0f32, 120.0, 300.0] {
            let brush = Brush { size, opacity: 0.8, color: 0xffffe060, stabilization: 0.0, ..Brush::default() };
            let (mut begin, mut moves, mut frames, mut ends) = (vec![], vec![], vec![], vec![]);
            for n in 0..12 {
                let y = 300.0 + n as f32 * 120.0;
                let t = Instant::now();
                e.begin(Sample { x: 200.0, y, pressure: 0.8, tilt: 0.0 }, brush);
                begin.push(ms(t));
                for i in 1..=40 {
                    // Fast stroke: about 60 px between input samples.
                    let t = Instant::now();
                    e.sample(Sample { x: 200.0 + i as f32 * 60.0, y: y + (i as f32 * 0.4).sin() * 80.0, pressure: 0.8, tilt: 0.0 });
                    moves.push(ms(t));
                    let t = Instant::now();
                    e.render_patch(false);
                    frames.push(ms(t));
                }
                let t = Instant::now();
                e.end();
                ends.push(ms(t));
            }
            println!("{}", json!({"brush":name,"size":size,"begin":stats(begin),"move":stats(moves),"frame":stats(frames),"end":stats(ends)}));
        }
    }
}

fn ms(t: Instant) -> f64 { t.elapsed().as_secs_f64() * 1000.0 }
fn stats(mut v: Vec<f64>) -> serde_json::Value {
    v.sort_by(f64::total_cmp);
    json!({"p50": (v[v.len() / 2] * 100.0).round() / 100.0, "p95": (v[v.len() * 95 / 100] * 100.0).round() / 100.0})
}
