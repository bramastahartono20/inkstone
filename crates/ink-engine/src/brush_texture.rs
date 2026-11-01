use crate::noise;
fn ramp(n:f32,a:f32,b:f32)->f32{((n-a)/(b-a)).clamp(0.0,1.0)}

fn grain(x:f32,y:f32,scale:f32,seed:u32)->f32 {
    let x=x/scale;let y=y/scale;let ix=x.floor() as i32;let iy=y.floor() as i32;
    let u=x-x.floor();let v=y-y.floor();let u=u*u*(3.0-2.0*u);let v=v*v*(3.0-2.0*v);
    let a=noise(ix as u32,iy as u32,seed);let b=noise(ix.wrapping_add(1) as u32,iy as u32,seed);
    let c=noise(ix as u32,iy.wrapping_add(1) as u32,seed);let d=noise(ix.wrapping_add(1) as u32,iy.wrapping_add(1) as u32,seed);
    (a+(b-a)*u)*(1.0-v)+(c+(d-c)*u)*v
}

pub fn sample(kind:u8,x:f32,y:f32,lateral:f32,longitudinal:f32,radius:f32,d:f32)->f32 {
    let fine=grain(x,y,1.6,119);
    match kind {
        // Grain is anchored to paper, so overlapping dabs do not turn into changing static.
        4 => {let paper=grain(x,y,5.0,211);let body=(1.0-d*d).max(0.0);let rim=((d-0.64)/0.25).clamp(0.0,1.0)*(1.0-d).max(0.0);(body*0.065+rim*0.14)*(0.3+paper*0.9+fine*0.15)},
        11 | 42 => {
            let edge=(1.0-(longitudinal/(radius*0.6).max(0.1)).abs().powi(4)).clamp(0.0,1.0);
            let bristle=grain(lateral,longitudinal*0.08,1.8,57);
            let gaps=if kind==42{(0.16+(bristle-0.43)*0.9).max(0.0)*ramp(bristle,0.40,0.46)}else{0.08+bristle*0.25};
            edge*gaps*(0.4+fine*0.6)
        },
        32 => {let warp=(lateral*0.72).sin();let weft=(longitudinal*0.66).sin();0.14+0.045*warp*weft},
        33 => {let tooth=grain(x,y,3.0,47);(0.12+fine*0.2)*(0.5+tooth)*ramp(tooth,0.20,0.55)},
        34 => {let cells=grain(x,y,8.0,89);(0.09+0.18*fine)*ramp((cells-0.5).abs(),0.02,0.16)},
        35 => {let holes=grain(x,y,4.0,173);0.32*(holes-0.45).max(0.0)*(1.0-d*d).max(0.0)*ramp(holes,0.52,0.58)},
        36 => {let patches=grain(x,y,7.0,257);patches*0.23*ramp(patches,0.25,0.65)*(0.6+fine*0.4)},
        37 => {
            let cx=x/13.0;let cy=y/17.0;let seed=noise(cx.floor() as i32 as u32,cy.floor() as i32 as u32,317);
            let u=(cx-cx.floor())*2.0-1.0+(seed-0.5)*0.45;let v=(cy-cy.floor())*2.0-1.0+(noise(cx.floor() as i32 as u32,cy.floor() as i32 as u32,733)-0.5)*0.4;
            let sign=if seed<0.6{-1.0}else{1.0};let a=(u+sign*v)*0.70710677;let b=(-sign*u+v)*0.70710677;let leaf=a*a/0.25+b.abs()/0.85;
            if seed>0.26 {(1.0-ramp(leaf,0.88,1.0))*(0.18+0.26*(1.0-leaf).max(0.0))*ramp(a.abs(),0.025,0.065)}else{0.0}
        },
        38 => {let a=(x*0.72+y*0.48).sin().abs();let b=(x*0.38-y*0.76).sin().abs();0.37*(1.0-ramp(a,0.16,0.22)*ramp(b,0.09,0.15))},
        39 => {let dot=(((x/3.0).fract().abs()-0.5).powi(2)+((y/3.0).fract().abs()-0.5).powi(2)).sqrt();0.35*(1.0-ramp(dot,0.28,0.32))},
        40 => {let wear=grain(x,y,4.0,421);(0.14+fine*0.2)*ramp(wear,0.16,0.55)},
        41 => {let paper=grain(x,y,2.8,509);(0.19+fine*0.24)*(1.0-d.powi(6)).max(0.0)*ramp(paper,0.10,0.45)},
        _=>1.0,
    }
}
