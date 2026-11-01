use super::*;

fn point(x: f32, y: f32, pressure: f32) -> Sample { Sample{x,y,pressure,tilt:0.0} }

#[test]
fn inkflow_curve_rounds_gentle_turns_and_preserves_corners() {
    let a=point(40.0,40.0,0.2);let b=point(80.0,65.0,0.8);
    let curve=stroke_path::curve(a,b,[40.0,0.0]);
    assert!(curve.len()>1);
    assert!(curve.iter().any(|p|p.y<40.0+(p.x-40.0)*0.625-0.2));
    for p in &curve {assert!(p.x>=a.x && p.x<=b.x && p.y>=a.y && p.y<=b.y);}
    let last=curve.last().unwrap();assert_eq!((last.x,last.y,last.pressure),(b.x,b.y,b.pressure));
    assert_eq!(stroke_path::curve(a,point(20.0,40.0,0.2),[40.0,0.0]).len(),1);
    assert_eq!(stroke_path::curve(a,point(40.0,80.0,0.2),[40.0,0.0]).len(),1);
}

#[test]
fn inkflow_filter_uses_elapsed_time_and_releases_at_speed() {
    let response=|dt:f32,speed:f32|{
        let mut p=point(0.0,0.0,0.1);
        for _ in 0..(32.0/dt) as usize{p=stroke_path::filter(p,point(100.0,0.0,0.9),dt,speed,0.8,true);}
        p
    };
    let a=response(2.0,0.0);let b=response(8.0,0.0);
    assert!((a.x-b.x).abs()<0.001);assert!((a.pressure-b.pressure).abs()<0.001);
    assert!(response(8.0,4.0).x>a.x+10.0);
    let p=stroke_path::filter(point(0.0,0.0,0.1),point(1.0,0.0,0.9),2.0,1.0,0.0,true);
    assert!(p.pressure>0.1 && p.pressure<0.5);assert_eq!(p.x,1.0);
}

#[test]
fn inkflow_timed_contact_does_not_stamp_a_full_pressure_blob() {
    let mut e=Engine::new(240,80).unwrap();
    let brush=Brush{size:40.0,pressure_size:1.0,stabilization:0.0,..Brush::default()};
    e.begin_timed(point(30.0,40.0,1.0),brush,0.0);
    assert!(e.state.layers[0].pixels.iter().all(|c|c==0));
    for i in 1..=30{e.sample_timed(point(30.0+i as f32*5.0,40.0,0.2),i as f32*4.0);}
    e.end();
    assert_eq!(e.state.layers[0].pixels[25*240+30]>>24,0);
    assert!(e.state.layers[0].pixels[40*240+30]>>24>0);
    assert_eq!(e.undo.len(),1);let saved=e.save().unwrap();assert!(e.undo());assert!(e.redo());assert_eq!(e.save().unwrap(),saved);
    e.begin_timed(point(210.0,40.0,1.0),brush,0.0);e.end();
    assert!(e.state.layers[0].pixels[25*240+210]>>24>0);
}

#[test]
fn inkflow_pressure_changes_stay_continuous_and_end_reaches_the_pen() {
    let mut e=Engine::new(240,80).unwrap();
    e.begin_timed(point(20.0,40.0,0.2),Brush{size:30.0,stabilization:0.9,..Brush::default()},0.0);
    for i in 1..=35{e.sample_timed(point(20.0+i as f32*5.0,40.0,if i<15{0.2}else{1.0}),i as f32*4.0);}
    let pressure=e.stroke.as_ref().unwrap().records.iter().map(|r|r.sample.pressure).collect::<Vec<_>>();
    assert!(pressure.windows(2).all(|p|(p[1]-p[0]).abs()<0.25));
    e.end();assert!(e.state.layers[0].pixels[40*240+195]>>24>0);
}

#[test]
fn inkflow_duplicate_timestamps_and_pauses_do_not_deposit_extra_ink() {
    let mut e=Engine::new(160,80).unwrap();let brush=Brush{kind:2,size:40.0,stabilization:0.0,..Brush::default()};
    e.begin_timed(point(20.0,40.0,0.5),brush,0.0);e.sample_timed(point(100.0,40.0,0.5),8.0);
    let before=e.state.layers[0].pixels.clone();
    for i in 0..20{e.sample_timed(point(100.0,40.0,0.5),8.0+i as f32);}
    e.sample_timed(point(140.0,40.0,1.0),1.0);
    assert_eq!(before,e.state.layers[0].pixels);
    e.end();
}

#[test]
fn inkflow_distinct_positions_sharing_a_timestamp_keep_tool_switches() {
    let mut e=Engine::new(160,80).unwrap();
    e.state.layers[0].pixels.fill(0xff204080);
    let brush=Brush{size:8.0,pressure_size:0.0,stabilization:0.0,..Brush::default()};
    e.begin_timed(point(20.0,40.0,1.0),brush,0.0);
    e.sample_timed(point(40.0,40.0,1.0),0.0);
    e.update_brush(Brush{kind:64,size:40.0,..brush});
    e.sample_timed(point(130.0,40.0,1.0),0.0);e.end();
    assert_eq!(e.state.layers[0].pixels[40*160+100]>>24,0);
    assert_eq!(e.undo.len(),1);assert!(e.undo());
    assert!(e.state.layers[0].pixels.iter().all(|c|c==0xff204080));
}

#[test]
fn inkflow_soft_tip_coverage_is_preserved_when_spacing_changes() {
    for kind in [2,4,8,9,66,68,72,73] {
        let brush=Brush{kind,size:64.0,pressure_size:0.8,..Brush::default()};
        for pressure in [0.1,0.5,1.0] {
            let p=point(0.0,0.0,pressure);let r=stroke_path::deposition(brush,p);
            for alpha in [0.01,0.05,0.12,0.18] {
                let adjusted=stroke_path::coverage(alpha,r);
                let expected=1.0-(1.0-alpha).powf(r);
                assert!((adjusted-expected).abs()<0.0001);
                let baseline=(1.0-alpha).powf(stroke_path::diameter(brush,p)/ (stroke_path::diameter(brush,p)*0.035).max(0.25));
                let current=(1.0-adjusted).powf(stroke_path::diameter(brush,p)/stroke_path::spacing(brush,p));
                assert!((baseline-current).abs()<0.001);
            }
        }
    }
}
