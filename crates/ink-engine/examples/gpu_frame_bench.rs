use inkstone::{Engine,Brush,Sample};
use std::time::Instant;
// Big-brush strokes on a multi-layer canvas: the GPU paint path plus the per-frame patch composite.
fn main(){
    let layers=std::env::var("INKSTONE_STRESS_LAYERS").ok().and_then(|s|s.parse::<usize>().ok()).unwrap_or(8);
    let size=std::env::var("INKSTONE_BRUSH").ok().and_then(|s|s.parse::<f32>().ok()).unwrap_or(160.0);
    let (w,h)=std::env::var("INKSTONE_CANVAS").ok().and_then(|s|{let (a,b)=s.split_once('x')?;Some((a.parse().ok()?,b.parse().ok()?))}).unwrap_or((2800usize,2000usize));
    if let Some(mb)=std::env::var("INKSTONE_BUDGET_MB").ok().and_then(|s|s.parse::<usize>().ok()){inkstone::set_pixel_budget(mb<<20);}
    let mut e=Engine::new(w,h).unwrap();
    if std::env::var("INKSTONE_RENDERER").as_deref()!=Ok("cpu"){e.set_renderer("auto").unwrap();}
    for i in 0..layers{
        if i>0{e.command(&serde_json::json!({"op":"add"})).unwrap();}
        e.begin(Sample{x:w as f32/2.0,y:h as f32/2.0,pressure:1.0,tilt:0.0},Brush{size:w.max(h) as f32*1.5,opacity:0.3,color:0xff376ac5,..Brush::default()});e.end();
    }
    if let Some(i)=std::env::var("INKSTONE_ACTIVE").ok().and_then(|s|s.parse::<usize>().ok()){e.command(&serde_json::json!({"op":"active","index":i})).unwrap();}
    e.render_patch(true);
    let (mut frame,mut strokes)=(vec![],vec![]);
    for n in 0..30{
        let start=Instant::now();let y=200.0+(n%16)as f32*100.0;
        e.begin_timed(Sample{x:100.0,y,pressure:0.8,tilt:0.0},Brush{size,stabilization:0.0,opacity:0.8,..Brush::default()},0.0);
        for i in 1..=60{
            e.sample_timed(Sample{x:100.0+i as f32*42.0,y:y+(i as f32*0.2).sin()*80.0,pressure:0.8,tilt:0.0},i as f32*8.0);
            if i%2==0{
                // Like the Android frame: pending dabs are painted inside render_patch.
                let a=Instant::now();e.render_patch(false);frame.push(a.elapsed().as_secs_f64()*1000.0);
            }
        }
        e.end();strokes.push(start.elapsed().as_secs_f64()*1000.0);
    }
    let pct=|v:&mut Vec<f64>,p:usize|{v.sort_by(f64::total_cmp);v[v.len()*p/100]};
    println!("{}",serde_json::json!({"canvas":[w,h],"layers":layers,"brush":size,"renderer":e.info()["renderer"],
        "frame_p50_ms":pct(&mut frame,50),"frame_p95_ms":pct(&mut frame,95),"stroke_p50_ms":pct(&mut strokes,50),
        "gpu_batches":e.info()["gpuBatches"],"gpu_composites":e.info()["gpuComposites"]}));
}
