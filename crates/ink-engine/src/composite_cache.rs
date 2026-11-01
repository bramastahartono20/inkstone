use crate::{groups::PreparedComposite, Layer};
use std::{collections::HashMap, sync::{Arc,Weak}};

struct Signature{pixels:u64,mask:Option<Weak<Vec<u8>>>,id:u32,visible:bool,opacity:f32,blend:String,clipping:bool,mask_enabled:bool}
impl Signature{
    fn new(l:&Layer)->Self{Self{pixels:l.pixels.generation(),mask:l.mask.as_ref().map(Arc::downgrade),id:l.id,visible:l.is_visible(),opacity:l.opacity,blend:l.blend.clone(),clipping:l.clipping,mask_enabled:l.mask_enabled}}
    fn matches(&self,l:&Layer)->bool{
        self.id==l.id && self.visible==l.is_visible() && self.opacity==l.opacity && self.blend==l.blend
            && self.clipping==l.clipping && self.mask_enabled==l.mask_enabled
            && self.pixels==l.pixels.generation()
            && match(&self.mask,&l.mask){(None,None)=>true,(Some(a),Some(b))=>Weak::ptr_eq(a,&Arc::downgrade(b)),_=>false}
    }
}
struct Tile{pixels:Vec<u32>,used:u64}
#[derive(Default)]
pub(super) struct CompositeCache{signature:Vec<Signature>,dimensions:(usize,usize),background:u32,tiles:HashMap<usize,Tile>,tick:u64}
impl CompositeCache{
    pub(super) fn render(&mut self,width:usize,height:usize,rect:[usize;4],background:u32,below:&[Layer],rest:&[Layer])->Vec<u32>{
        if self.dimensions!=(width,height) || self.background!=background || self.signature.len()!=below.len()
            || !self.signature.iter().zip(below).all(|(s,l)|s.matches(l)){
            self.tiles.clear();self.signature=below.iter().map(Signature::new).collect();self.dimensions=(width,height);self.background=background;
        }
        let under=PreparedComposite::new(&[],below);let over=PreparedComposite::new(&[],rest);
        let [x0,y0,x1,y1]=rect;let w=x1-x0;let mut output=vec![0;w*(y1-y0)];let columns=width.div_ceil(64);
        for ty in y0/64..y1.div_ceil(64){for tx in x0/64..x1.div_ceil(64){
            let key=ty*columns+tx;self.tick=self.tick.wrapping_add(1);
            if !self.tiles.contains_key(&key){
                // At most 8 MiB, independent of artwork size or session duration.
                if self.tiles.len()>=512{if let Some(old)=self.tiles.iter().min_by_key(|(_,t)|t.used).map(|(k,_)|*k){self.tiles.remove(&old);}}
                let mut pixels=vec![0;64*64];
                for y in ty*64..((ty+1)*64).min(height){for x in tx*64..((tx+1)*64).min(width){let i=y*width+x;pixels[(y%64)*64+x%64]=under.color(background,|l|(l.pixels[i],l.mask_coverage(i)));}}
                self.tiles.insert(key,Tile{pixels,used:self.tick});
            }
            let tile=self.tiles.get_mut(&key).unwrap();tile.used=self.tick;
            for y in (ty*64).max(y0)..((ty+1)*64).min(y1){for x in (tx*64).max(x0)..((tx+1)*64).min(x1){
                let i=y*width+x;output[(y-y0)*w+x-x0]=over.color(tile.pixels[(y%64)*64+x%64],|l|(l.pixels[i],l.mask_coverage(i)));
            }}
        }}output
    }
}
