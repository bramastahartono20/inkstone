use super::*;
use std::collections::{VecDeque};

#[derive(Clone)]
pub(super) struct Clipboard{rect:[usize;4],pixels:Arc<Vec<u32>>}
impl Engine{
    pub(super) fn selection_coverage(&self,i:usize)->u8{self.selection_alpha.as_ref().map_or_else(||if self.selection.as_ref().is_none_or(|s|s[i]){255}else{0},|a|a[i])}
    pub(super) fn set_selection(&mut self,alpha:Option<Vec<u8>>){
        self.selection=alpha.as_ref().map(|a|a.iter().map(|v|*v>0).collect());
        self.selection_base=alpha.clone();self.selection_alpha=alpha;self.selection_version+=1;self.dirty=true;
    }
    pub(super) fn combine_selection(&mut self,mut alpha:Vec<u8>,mode:&str){
        if let Some(old)=&self.selection_alpha{for(i,v)in alpha.iter_mut().enumerate(){*v=match mode{"Add"=>old[i].max(*v),"Remove"=>old[i].saturating_sub(*v),_=>*v};}}
        else if mode=="Remove"{for v in &mut alpha{*v=255-*v;}}
        self.set_selection(Some(alpha));
    }
    pub(super) fn selection_command(&mut self,v:&Value)->Result<Value,String>{
        let op=v["op"].as_str().unwrap_or("");
        match op{
            "selectionPreview"=>{
                let scale=(512.0/self.width.max(self.height) as f32).min(1.0);let w=(self.width as f32*scale).ceil() as usize;let h=(self.height as f32*scale).ceil() as usize;
                let mut alpha=vec![];if self.selection.is_some(){for y in 0..h{for x in 0..w{alpha.push(self.selection_coverage((y*self.height/h)*self.width+x*self.width/w));}}}
                return Ok(json!({"width":w,"height":h,"runs":encode_mask_runs(&alpha),"version":self.selection_version}));
            },
            "selectionAutomatic"=>{
                let x=v["x"].as_u64().unwrap_or(0) as usize;let y=v["y"].as_u64().unwrap_or(0) as usize;
                if x>=self.width || y>=self.height{return Err("Tap inside the canvas".into());}
                let threshold=finite(v["threshold"].as_f64().unwrap_or(0.12) as f32,0.12).clamp(0.0,1.0);
                let layers=self.compositing_layers();let target=self.color_at_layers(x,y,true,&layers);let mut alpha=vec![0u8;self.width*self.height];let mut seen=vec![false;alpha.len()];let mut todo=VecDeque::from([y*self.width+x]);
                while let Some(i)=todo.pop_front(){if seen[i]{continue;}seen[i]=true;let x=i%self.width;let y=i/self.width;if color_distance(self.color_at_layers(x,y,true,&layers),target)>threshold{continue;}alpha[i]=255;
                    if x>0{todo.push_back(i-1);}if x+1<self.width{todo.push_back(i+1);}if y>0{todo.push_back(i-self.width);}if y+1<self.height{todo.push_back(i+self.width);}
                }
                self.combine_selection(alpha,v["mode"].as_str().unwrap_or("Replace"));
            },
            "selectionFeather"=>{
                let base=self.selection_base.as_ref().ok_or("Make a selection first")?;
                let radius=v["radius"].as_u64().unwrap_or(0).min(100) as usize;
                let mut alpha=base.clone();
                if radius>0{for horizontal in [true,false]{let mut next=vec![0u8;alpha.len()];let (major,minor)=if horizontal{(self.height,self.width)}else{(self.width,self.height)};
                    for a in 0..major{let index=|b|if horizontal{a*self.width+b}else{b*self.width+a};let mut sum:u32=(0..=radius.min(minor-1)).map(|b|alpha[index(b)] as u32).sum();
                        for b in 0..minor{next[index(b)]=(sum/(radius*2+1) as u32) as u8;if b>=radius{sum-=alpha[index(b-radius)] as u32;}if b+radius+1<minor{sum+=alpha[index(b+radius+1)] as u32;}}
                    }alpha=next;
                }}
                self.selection=Some(alpha.iter().map(|a|*a>0).collect());self.selection_alpha=Some(alpha);self.selection_version+=1;
            },
            "selectionSave"=>{
                let name=v["name"].as_str().unwrap_or("").trim().chars().take(60).collect::<String>();if name.is_empty(){return Err("Name the selection".into());}
                let alpha=self.selection_alpha.as_ref().ok_or("Make a selection first")?;let runs=encode_mask_runs(alpha);
                let bytes=self.saved_selections.iter().filter(|(n,_)|**n!=name).map(|(_,r)|r.len()*8).sum::<usize>()+runs.len()*8;
                if bytes>32*1024*1024{return Err("Saved selections exceed the document memory budget".into());}
                self.saved_selections.insert(name,runs);self.dirty=true;
            },
            "selectionLoad"=>{let name=v["name"].as_str().unwrap_or("");let runs=self.saved_selections.get(name).ok_or("Selection not found")?;let values=decode_mask_runs(runs,self.width*self.height)?;self.set_selection(Some(values));},
            "selectionDelete"=>{self.saved_selections.remove(v["name"].as_str().unwrap_or(""));},
            "selectionCopy"|"selectionCut"|"selectionCopyPaste"=>{
                let r=self.transform_bounds(true,self.state.active);if r[2]==0{return Err("No painted pixels in the selection".into());}
                let mut pixels=Vec::with_capacity((r[2]-r[0])*(r[3]-r[1]));for y in r[1]..r[3]{for x in r[0]..r[2]{let i=y*self.width+x;let c=self.state.layers[self.state.active].masked_pixel(i);pixels.push((c&0xffffff)|((((c>>24)*self.selection_coverage(i) as u32+127)/255)<<24));}}
                self.clipboard=Some(Clipboard{rect:r,pixels:Arc::new(pixels)});
                if op=="selectionCut"{self.clear_selected()?;}if op=="selectionCopyPaste"{self.paste_selection()?;}
            },
            "selectionPaste"=>self.paste_selection()?,
            "selectionClear"=>{if self.selection.is_none(){return Err("Make a selection first".into());}self.clear_selected()?;},
            "selectionFill"=>{
                if self.selection.is_none(){return Err("Make a selection first".into());}self.ensure_edit_capacity()?;let before=self.state.layers[self.state.active].pixels.as_ref().clone();let color=v["color"].as_u64().unwrap_or(0xff202020) as u32;let lock=self.state.layers[self.state.active].alpha_lock;
                let mut out=before.clone();for(i,p)in out.iter_mut().enumerate(){let coverage=self.selection_coverage(i) as f32/255.0;let ink=if lock{(color&0xffffff)|(*p&0xff000000)}else{color};*p=mix_rgba(*p,ink,coverage);}
                self.state.layers[self.state.active].pixels=Arc::new(out);self.pixel_change(self.state.active,before);
            },
            _=>return Err("Unknown selection operation".into()),
        }Ok(self.info())
    }
    fn clear_selected(&mut self)->Result<(),String>{
        let before=self.state.layers[self.state.active].pixels.as_ref().clone();let mut out=before.clone();
        for(i,p)in out.iter_mut().enumerate(){*p=(*p&0xffffff)|(((((*p>>24)*(255-self.selection_coverage(i) as u32))+127)/255)<<24);}
        self.state.layers[self.state.active].pixels=Arc::new(out);self.pixel_change(self.state.active,before);Ok(())
    }
    fn paste_selection(&mut self)->Result<(),String>{
        if self.next_id==u32::MAX{return Err("Layer identifiers exhausted".into());}
        if !self.can_add(){return Err("Not enough memory to paste a layer".into());}let copy=self.clipboard.as_ref().ok_or("Copy a selection first")?;let r=copy.rect;let mut pixels=vec![0;self.width*self.height];
        let sw=r[2]-r[0];let sh=r[3]-r[1];let scale=(self.width as f32/sw as f32).min(self.height as f32/sh as f32).min(1.);
        let dw=(sw as f32*scale).round().max(1.) as usize;let dh=(sh as f32*scale).round().max(1.) as usize;
        let (dx,dy)=if r[2]<=self.width && r[3]<=self.height{(r[0],r[1])}else{((self.width-dw)/2,(self.height-dh)/2)};
        for y in 0..dh{for x in 0..dw{pixels[(dy+y)*self.width+dx+x]=copy.pixels[(y*sh/dh)*sw+x*sw/dw];}}

        self.structure(|e|{let id=e.next_id;e.next_id+=1;let mut layer=e.state.layers[e.state.active].clone();layer.id=id;layer.name="Pasted selection".into();layer.locked=false;layer.alpha_lock=false;layer.clipping=false;layer.mask=None;layer.mask_enabled=true;layer.opacity=1.;layer.blend="Normal".into();layer.pixels=Arc::new(pixels);layer.exterior.clear();e.state.active+=1;e.state.layers.insert(e.state.active,layer);});self.set_selection(None);Ok(())
    }
}

fn color_distance(a:u32,b:u32)->f32{[0,8,16,24].into_iter().map(|shift|(channel(a,shift)-channel(b,shift)).abs()).fold(0.,f32::max)}
