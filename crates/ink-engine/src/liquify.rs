use super::*;

pub(super) struct Liquify {layer:usize,before:crate::tiles::Tiles,last:Option<(f32,f32)>}
impl Engine {
    pub(super) fn finish_liquify(&mut self,cancel:bool){
        if let Some(edit)=self.liquify.take(){
            if cancel{self.state.layers[edit.layer].pixels=edit.before;self.dirty=true;}
            else{self.action_name="Liquify".into();self.pixel_change(edit.layer,edit.before.clone());}
        }
    }
    pub(super) fn liquify_command(&mut self,v:&Value)->Result<(),String>{
        match v["op"].as_str().unwrap_or(""){
            "liquifyBegin"=>{
                self.finish_liquify(false);self.ensure_edit_capacity()?;
                let layer=&self.state.layers[self.state.active];
                if layer.is_locked(){return Err("This layer is locked".into());}
                if layer.mask.is_some(){return Err("Apply the layer mask before Liquify".into());}
                self.liquify=Some(Liquify{layer:self.state.active,before:layer.pixels.clone(),last:None});
            },
            "liquifyCommit"=>self.finish_liquify(false),
            "liquifyCancel"=>self.finish_liquify(true),
            "liquifyDab"=>{
                let mode=v["mode"].as_str().unwrap_or("Push");
                if !matches!(mode,"Push"|"Pinch"|"Expand"|"Twirl right"|"Twirl left"|"Restore"){return Err("Unknown Liquify mode".into());}
                let points=v["points"].as_array().ok_or("Missing Liquify points")?;
                if points.len()>2048{return Err("Too many Liquify samples".into());}
                let radius=finite(v["size"].as_f64().unwrap_or(160.) as f32,160.).clamp(8.,600.)/2.;
                let strength=finite(v["strength"].as_f64().unwrap_or(0.5) as f32,0.5).clamp(0.,1.);
                let mut edit=self.liquify.take().ok_or("Open Liquify first")?;
                for p in points{
                    let x=p[0].as_f64().unwrap_or(f64::NAN) as f32;let y=p[1].as_f64().unwrap_or(f64::NAN) as f32;
                    if !x.is_finite() || !y.is_finite() || x.abs()>1000000. || y.abs()>1000000.{continue;}
                    let pressure=finite(p[2].as_f64().unwrap_or(1.) as f32,1.).clamp(0.,1.);
                    if v["start"].as_bool().unwrap_or(false) && edit.last.is_none(){edit.last=Some((x,y));if mode=="Push"{continue;}}
                    let previous=edit.last.unwrap_or((x,y));let dx=x-previous.0;let dy=y-previous.1;let distance=dx.hypot(dy);
                    let count=(distance/(radius*0.18).max(2.)).ceil().clamp(1.,96.) as usize;
                    for step in 1..=count{let t=step as f32/count as f32;self.liquify_dab(&edit,previous.0+dx*t,previous.1+dy*t,dx/count as f32,dy/count as f32,radius,strength*pressure,mode);}
                    edit.last=Some((x,y));
                }
                if v["end"].as_bool().unwrap_or(false){edit.last=None;}
                self.liquify=Some(edit);
            },_=>return Err("Unknown Liquify operation".into())
        };Ok(())
    }
    fn liquify_dab(&mut self,edit:&Liquify,cx:f32,cy:f32,dx:f32,dy:f32,radius:f32,strength:f32,mode:&str){
        let bound=|x:f32,max:usize|x.floor().clamp(0.,max as f32) as usize;
        let left=bound(cx-radius-2.,self.width);let top=bound(cy-radius-2.,self.height);
        let right=bound(cx+radius+3.,self.width);let bottom=bound(cy+radius+3.,self.height);
        if left>=right || top>=bottom || strength<=0.{return;}
        let margin=radius+dx.abs().max(dy.abs())+4.;
        let sx0=bound(cx-radius-margin,self.width);let sy0=bound(cy-radius-margin,self.height);
        let sx1=bound(cx+radius+margin+2.,self.width);let sy1=bound(cy+radius+margin+2.,self.height);
        let sw=sx1-sx0;let mut source=Vec::with_capacity(sw*(sy1-sy0));
        for y in sy0..sy1{source.extend(self.state.layers[edit.layer].pixels.row(y,sx0,sx1));}
        let sample=|x:f32,y:f32|{
            let x=x-0.5;let y=y-0.5;let ix=x.floor() as isize;let iy=y.floor() as isize;let fx=x-x.floor();let fy=y-y.floor();let mut rgba=[0.;4];
            for(xx,yy,weight)in[(ix,iy,(1.-fx)*(1.-fy)),(ix+1,iy,fx*(1.-fy)),(ix,iy+1,(1.-fx)*fy),(ix+1,iy+1,fx*fy)]{
                if xx<sx0 as isize || yy<sy0 as isize || xx>=sx1 as isize || yy>=sy1 as isize{continue;}
                let color=source[(yy as usize-sy0)*sw+xx as usize-sx0];let a=channel(color,24)*weight;rgba[3]+=a;for(k,shift)in[16,8,0].into_iter().enumerate(){rgba[k]+=channel(color,shift)*a;}
            };if rgba[3]>1e-6{pack(rgba[0]/rgba[3],rgba[1]/rgba[3],rgba[2]/rgba[3],rgba[3])}else{0}
        };
        let lock=self.state.layers[edit.layer].alpha_lock;
        let pixels=&mut self.state.layers[edit.layer].pixels;
        for y in top..bottom{for x in left..right{
            let vx=x as f32+0.5-cx;let vy=y as f32+0.5-cy;let distance=vx.hypot(vy);if distance>=radius{continue;}
            let i=y*self.width+x;if self.selection.as_ref().is_some_and(|s|!s[i]){continue;}
            let t=1.-distance/radius;let amount=t*t*(3.-2.*t)*strength*self.selection_alpha.as_ref().map_or(1.,|a|a[i] as f32/255.);
            let result=if mode=="Restore"{mix_rgba(pixels[i],edit.before[i],amount)}else{
                let(sx,sy)=match mode{
                    "Pinch"=>(cx+vx*(1.+amount*0.20),cy+vy*(1.+amount*0.20)),
                    "Expand"=>(cx+vx*(1.-amount*0.20),cy+vy*(1.-amount*0.20)),
                    "Twirl right"|"Twirl left"=>{let angle=amount*0.25*if mode=="Twirl right"{-1.}else{1.};(cx+vx*angle.cos()-vy*angle.sin(),cy+vx*angle.sin()+vy*angle.cos())},
                    _=>(x as f32+0.5-dx*amount,y as f32+0.5-dy*amount)
                };sample(sx,sy)
            };
            pixels[i]=if lock{(result&0xffffff)|(pixels[i]&0xff000000)}else{result};
        }}
        let rect=[left,top,right,bottom];let damage=self.damage.get_or_insert(rect);for n in 0..2{damage[n]=damage[n].min(rect[n]);damage[n+2]=damage[n+2].max(rect[n+2]);}
    }
}
