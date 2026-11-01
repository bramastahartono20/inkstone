use super::*;
use crate::tiles::Tiles;

#[derive(Clone,Default)]
pub(super) struct BrushColor {pub(super) mode:String,pub(super) cycle:f32,pub(super) mix:f32,pub(super) pickup:f32,pub(super) wetness:f32,pub(super) palette:Vec<u32>,pub(super) layout:u32}
impl BrushColor {
    pub(super) fn parse(v:&Value)->Result<Self,String>{
        let mode=v["mode"].as_str().unwrap_or("Solid");if !matches!(mode,"Solid"|"Rainbow"|"Palette"){return Err("Unknown brush color mode".into());}
        let palette=v["palette"].as_array().map(|a|a.iter().take(12).filter_map(|v|v.as_u64().filter(|n|*n<=u32::MAX as u64).map(|n|n as u32)).collect()).unwrap_or_default();
        Ok(Self{mode:mode.into(),cycle:finite(v["cycle"].as_f64().unwrap_or(600.)as f32,600.).clamp(20.,20000.),layout:match v["layout"].as_str().unwrap_or("Along"){"Across"=>1,"Bristles"=>2,_=>0},mix:finite(v["mix"].as_f64().unwrap_or(0.)as f32,0.).clamp(0.,1.),pickup:finite(v["pickup"].as_f64().unwrap_or(0.)as f32,0.).clamp(0.,1.),wetness:finite(v["wetness"].as_f64().unwrap_or(0.)as f32,0.).clamp(0.,1.),palette})
    }
    pub(super) fn color(&self,base:u32,distance:f32)->u32{
        self.at(base,distance,0.0)
    }
    pub(super) fn at(&self,base:u32,distance:f32,lateral:f32)->u32{
        let across=if self.layout==0{0.0}else if self.layout==2{((lateral+1.0)*8.0).floor()/16.0}else{lateral*0.5};
        let t=(distance/self.cycle.max(20.)+across).rem_euclid(1.0);
        if self.mode=="Palette" && self.palette.len()>=2{
            let p=t.fract()*self.palette.len()as f32;let a=self.palette[p as usize%self.palette.len()];let b=self.palette[(p as usize+1)%self.palette.len()];let f=p.fract();
            return pack(channel(a,16)*(1.-f)+channel(b,16)*f,channel(a,8)*(1.-f)+channel(b,8)*f,channel(a,0)*(1.-f)+channel(b,0)*f,channel(base,24));
        }
        if self.mode!="Rainbow"{return base;}
        let h=t.fract()*6.;let x=1.-(h%2.-1.).abs();let (r,g,b)=match h as u32{0=>(1.,x,0.),1=>(x,1.,0.),2=>(0.,1.,x),3=>(0.,x,1.),4=>(x,0.,1.),_=>(1.,0.,x)};
        pack(r,g,b,channel(base,24))
    }
}
fn rgb_ryb(mut c:[f32;3])->[f32;3]{
    let white=c[0].min(c[1]).min(c[2]);for n in &mut c{*n-=white;}let max=c[0].max(c[1]).max(c[2]);
    let mut y=c[0].min(c[1]);c[0]-=y;c[1]-=y;if c[2]>0. && c[1]>0.{c[2]*=0.5;c[1]*=0.5;}y+=c[1];c[2]+=c[1];let scale=max/c[0].max(y).max(c[2]).max(1e-6);
    [c[0]*scale+white,y*scale+white,c[2]*scale+white]
}
fn ryb_rgb(mut c:[f32;3])->[f32;3]{
    let white=c[0].min(c[1]).min(c[2]);for n in &mut c{*n-=white;}let max=c[0].max(c[1]).max(c[2]);
    let mut g=c[1].min(c[2]);c[1]-=g;c[2]-=g;if c[2]>0. && g>0.{c[2]*=2.;g*=2.;}c[0]+=c[1];g+=c[1];let scale=max/c[0].max(g).max(c[2]).max(1e-6);
    [c[0]*scale+white,g*scale+white,c[2]*scale+white]
}
pub(super) fn wet_mix(a:u32,b:u32,amount:f32)->u32{
    if channel(b,24)<=0. || amount<=0.{return a;}
    let x=rgb_ryb([channel(a,16),channel(a,8),channel(a,0)]);let y=rgb_ryb([channel(b,16),channel(b,8),channel(b,0)]);let t=amount*channel(b,24);
    let c=ryb_rgb([x[0]*(1.-t)+y[0]*t,x[1]*(1.-t)+y[1]*t,x[2]*(1.-t)+y[2]*t]);pack(c[0],c[1],c[2],channel(a,24))
}

// Read pre-stroke pigment with a subpixel footprint, without repicking fresh ink from this stroke.
pub(super) fn sample_paint(pixels:&Tiles,width:usize,height:usize,x:f32,y:f32,radius:f32)->u32{
    let mut alpha=0.0;let mut ink=[0.0;3];
    for oy in -1..=1{for ox in -1..=1{
        let weight=if ox==0{2.0}else{1.0}*if oy==0{2.0}else{1.0};
        let sx=x+ox as f32*radius-0.5;let sy=y+oy as f32*radius-0.5;
        let ix=sx.floor() as i32;let iy=sy.floor() as i32;let fx=sx-ix as f32;let fy=sy-iy as f32;
        let mut a=0.0;let mut rgb=[0.0;3];
        for(dy,wy)in [(0,1.0-fy),(1,fy)]{for(dx,wx)in [(0,1.0-fx),(1,fx)]{
            let px=ix+dx;let py=iy+dy;if px<0||py<0||px>=width as i32||py>=height as i32{continue;}
            let c=pixels[py as usize*width+px as usize];let amount=channel(c,24)*wx*wy;a+=amount;
            for(i,shift)in [16,8,0].into_iter().enumerate(){rgb[i]+=channel(c,shift)*amount;}
        }}
        for i in 0..3{ink[i]+=rgb[i]*weight;}
        alpha+=a*weight;
    }}
    if alpha<=0.0{return 0;}
    let c=ink.map(|c|c/alpha);pack(c[0],c[1],c[2],alpha/16.0)
}

#[derive(Clone,Copy)]
pub(super) struct PaintLoad{pigment:[f32;3]}
pub(super) fn transport(base:u32,loaded:&mut Option<PaintLoad>,under:u32,mix:f32,pickup:f32,wetness:f32,step:f32)->u32{
    if mix<=0.0{return base;}
    let step=step.clamp(0.0,4.0);
    let fresh=rgb_ryb([channel(base,16),channel(base,8),channel(base,0)]);
    let previous=loaded.map_or(fresh,|p|p.pigment);
    // A finite refill remains even at maximum wetness. Keep the reservoir in floats so tiny
    // distance-based changes cannot get stuck at an 8-bit rounding threshold.
    let retained=(-step*(0.06+(1.0-wetness)*2.2)).exp();
    let uptake=(1.0-(-pickup*step*6.0).exp())*channel(under,24);
    let surface=rgb_ryb([channel(under,16),channel(under,8),channel(under,0)]);
    let pigment=std::array::from_fn(|i|{let charged=fresh[i]+(previous[i]-fresh[i])*retained;charged+(surface[i]-charged)*uptake});
    *loaded=Some(PaintLoad{pigment});
    let c=ryb_rgb(std::array::from_fn(|i|fresh[i]+(pigment[i]-fresh[i])*mix));
    pack(c[0],c[1],c[2],channel(base,24))
}

pub(super) fn loaded_color(load:PaintLoad,base:u32)->u32{let c=ryb_rgb(load.pigment);pack(c[0],c[1],c[2],channel(base,24))}

// A small contact field preserves local pigment variation without converting RYB per pixel.
pub(super) struct ContactField{pub(super) colors:[u32;81]}
impl ContactField{
    pub(super) fn new(pixels:&Tiles,width:usize,height:usize,x:f32,y:f32,radius:f32,color:u32,amount:f32)->Self{
        let colors=std::array::from_fn(|i|{
            let sx=(x+(i%9)as f32*radius/4.0-radius).floor()as i32;
            let sy=(y+(i/9)as f32*radius/4.0-radius).floor()as i32;
            let under=if sx>=0 && sy>=0 && sx<width as i32 && sy<height as i32{pixels[sy as usize*width+sx as usize]}else{0};
            wet_mix(color,under,amount)
        });Self{colors}
    }
    pub(super) fn at(&self,x:f32,y:f32)->u32{
        let x=((x+1.0)*4.0).clamp(0.0,8.0);let y=((y+1.0)*4.0).clamp(0.0,8.0);
        let ix=x.floor()as usize;let iy=y.floor()as usize;let fx=x-ix as f32;let fy=y-iy as f32;
        let a=self.colors[iy*9+ix];let b=self.colors[iy*9+(ix+1).min(8)];let c=self.colors[(iy+1).min(8)*9+ix];let d=self.colors[(iy+1).min(8)*9+(ix+1).min(8)];
        let v=|s|{let top=channel(a,s)*(1.0-fx)+channel(b,s)*fx;let bottom=channel(c,s)*(1.0-fx)+channel(d,s)*fx;top*(1.0-fy)+bottom*fy};
        pack(v(16),v(8),v(0),channel(a,24))
    }
}
