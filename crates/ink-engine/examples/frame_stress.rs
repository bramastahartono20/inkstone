use inkstone::{Engine,Brush,Sample};
use std::time::Instant;
fn main(){
    if std::env::args().any(|a|a=="--shapes"){shapes();return;}
    if std::env::args().any(|a|a=="--history"){history();return;}
    let count=std::env::var("INKSTONE_STRESS_LAYERS").ok().and_then(|s|s.parse::<usize>().ok()).unwrap_or(24);
    let mut e=Engine::new(1024,768).unwrap();
    for i in 0..count{
        if i>0{e.command(&serde_json::json!({"op":"add"})).unwrap();}
        e.begin(Sample{x:512.0,y:384.0,pressure:1.0,tilt:0.0},Brush{size:600.0,opacity:0.25,color:0xff376ac5,..Brush::default()});e.end();
    }
    e.render_patch(false);
    let mut times=Vec::new();let mut frame=Vec::new();
    for n in 0..80{
        let start=Instant::now();
        e.begin_timed(Sample{x:80.0,y:180.0+(n%8) as f32*45.0,pressure:0.5,tilt:0.0},Brush{size:24.0,stabilization:0.2,opacity:0.6,..Brush::default()},0.0);
        for i in 1..=32{
            e.sample_timed(Sample{x:80.0+i as f32*26.0,y:180.0+(n%8)as f32*45.0+(i as f32*0.3).sin()*55.0,pressure:0.5,tilt:0.0},i as f32*4.0);
            if i%4==0{let before=Instant::now();e.render_patch(false);frame.push(before.elapsed().as_secs_f64()*1000.0);}
        }
        e.end();times.push(start.elapsed().as_secs_f64()*1000.0);
    }
    frame.sort_by(f64::total_cmp);
    println!("{}",serde_json::json!({"strokes":80,"layers":count,"frames":frame.len(),"frame_p50_ms":frame[frame.len()/2],"frame_p95_ms":frame[frame.len()*95/100],"first10_stroke_avg_ms":times[..10].iter().sum::<f64>()/10.0,"last10_stroke_avg_ms":times[70..].iter().sum::<f64>()/10.0,"note":"Host CPU workload, not Android display latency"}));
}

fn shapes(){
    let mut e=Engine::new(1600,1200).unwrap();e.render_patch(false);
    let brush=Brush{size:8.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
    let mut times=Vec::new();let mut area=0;
    for n in 0..120{
        let radius=70.0+n as f32*0.6;
        let points=(0..=80).map(|i|{let a=i as f32*std::f32::consts::TAU/80.0;Sample{x:500.0+a.cos()*radius,y:400.0+a.sin()*radius*0.6,pressure:1.0,tilt:0.0}}).collect::<Vec<_>>();
        let start=Instant::now();
        e.replace_stroke(&points,brush);
        if let Some(p)=e.render_patch(false){area+=(p.len()-4)as u64;}
        times.push(start.elapsed().as_secs_f64()*1000.0);
    }
    e.end();times.sort_by(f64::total_cmp);
    println!("{}",serde_json::json!({"resizes":120,"canvas":[1600,1200],"resize_p50_ms":times[60],"resize_p95_ms":times[114],"mean_patch_pixels":area/120,"note":"Host CPU workload including rasterization and patch compositing"}));
}

fn history(){
    let mut e=Engine::new(2048,1536).unwrap();let mut times=Vec::new();
    let b=Brush{size:6.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
    for i in 0..100{
        let start=Instant::now();let offset=(i%10)as f32*5.0;
        e.begin(Sample{x:50.0,y:60.0+offset,pressure:1.0,tilt:0.0},b);
        e.sample(Sample{x:1980.0,y:1430.0-offset,pressure:1.0,tilt:0.0});e.end();
        times.push(start.elapsed().as_secs_f64()*1000.0);
    }
    let first=times[..10].iter().sum::<f64>()/10.0;let last=times[90..].iter().sum::<f64>()/10.0;
    times.sort_by(f64::total_cmp);
    println!("{}",serde_json::json!({"strokes":100,"canvas":[2048,1536],"first10_stroke_avg_ms":first,"last10_stroke_avg_ms":last,"stroke_p95_ms":times[95],"undo_steps":e.info()["undo"],"note":"Host CPU and undo-cache workload; no Android rendering or input"}));
}
