use inkstone::{Engine,Brush,Sample};
use serde_json::json;
use std::time::Instant;
// Usage: save_bench WIDTH HEIGHT LAYERS
fn main(){
    let a:Vec<usize>=std::env::args().skip(1).map(|s|s.parse().unwrap()).collect();
    let (w,h,layers)=(a.first().copied().unwrap_or(2732),a.get(1).copied().unwrap_or(2048),a.get(2).copied().unwrap_or(5));
    let mut e=Engine::new(w,h).unwrap();
    for l in 0..layers {
        if l>0 {e.command(&json!({"op":"add"})).unwrap();}
        for s in 0..60 {
            let y=(s as f32*37.0+l as f32*11.0)%(h as f32);
            let color=0xff000000u32|((s as u32*2654435761u32)>>8);
            e.begin(Sample{x:10.0,y,pressure:0.7,tilt:0.0},Brush{size:40.0,color,stabilization:0.0,..Brush::default()});
            for i in 1..=80 {e.sample(Sample{x:10.0+i as f32*(w as f32/80.0),y:y+(i as f32*0.3+l as f32).sin()*60.0,pressure:0.4+0.5*((i%7) as f32/7.0),tilt:0.0});}
            e.end();
        }
    }
    let t=Instant::now();let bytes=e.save_compressed().unwrap();let save=t.elapsed().as_secs_f64()*1000.0;
    let t=Instant::now();let again=e.save_compressed().unwrap();let resave=t.elapsed().as_secs_f64()*1000.0;
    let t=Instant::now();let mut loaded=Engine::load(&bytes).unwrap();let load=t.elapsed().as_secs_f64()*1000.0;
    let t=Instant::now();for i in 0..5{loaded.command(&json!({"op":"adjustPreview","kind":"Brightness","amount":0.05*i as f64})).unwrap();}let adj=t.elapsed().as_secs_f64()*1000.0/5.0;
    loaded.command(&json!({"op":"adjustCommit"})).unwrap();
    let t=Instant::now();let p=loaded.render_patch(true).unwrap();let full=t.elapsed().as_secs_f64()*1000.0;
    println!("canvas={w}x{h} layers={layers} save_ms={save:.0} resave_ms={resave:.0} bytes={:.1}MB resave_bytes={:.1}MB load_ms={load:.0} adjust_preview_ms={adj:.0} full_render_ms={full:.0} patch={}",bytes.len() as f64/1e6,again.len() as f64/1e6,p.len());
}
