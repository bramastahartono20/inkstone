use super::*;
use std::collections::BTreeMap;
const TILE:usize=128;
pub(super) fn key(x:i32,y:i32)->i64{((x as i64)<<32)|(y as u32 as i64)}
pub(super) fn coords(key:i64)->(i32,i32){((key>>32)as i32,key as i32)}
pub(super) fn encode(tiles:&BTreeMap<i64,Arc<Vec<u32>>>)->BTreeMap<i64,Vec<(u32,u32)>>{tiles.iter().filter(|(_,v)|v.iter().any(|p|p>>24!=0)).map(|(k,v)|(*k,encode_runs(v))).collect()}
pub(super) fn decode(tiles:BTreeMap<i64,Vec<(u32,u32)>>)->Result<BTreeMap<i64,Arc<Vec<u32>>>,String>{
    if tiles.len()>256{return Err("Too many off-canvas tiles".into());}
    tiles.into_iter().map(|(k,r)|{let(x,y)=coords(k);if !(-16..80).contains(&x)||!(-16..80).contains(&y){return Err("Off-canvas tile outside supported bounds".into());}Ok((k,Arc::new(decode_runs(&r,TILE*TILE)?)))}).collect()
}
impl Engine{
    pub(super) fn exterior_patch(&mut self)->Option<Vec<i32>>{
        if !self.exterior_dirty && self.exterior_changed_tiles.is_empty(){return None;}
        let full=self.exterior_dirty;self.exterior_dirty=false;
        let mut keys=std::mem::take(&mut self.exterior_changed_tiles);
        if full{keys.clear();for l in &self.state.layers{if l.is_visible(){keys.extend(l.exterior.keys().copied());}}}
        let mut out=vec![0x45585431,full as i32,keys.len() as i32];
        for key in keys{
            let(tx,ty)=coords(key);out.extend([tx*128,ty*128]);
            let layers:Vec<_>=self.state.layers.iter().filter(|l|l.is_visible() && l.exterior.contains_key(&key)).cloned().collect();
            for y in 0..64{for x in 0..64{let idx=y*2*TILE+x*2;out.push(self.composite_grouped(&layers,0,|l|(l.exterior.get(&key).map_or(0,|v|v[idx]),1.0)) as i32);}}
        }Some(out)
    }
    pub(super) fn exterior_preview(&mut self)->Value{
        let Some(patch)=self.exterior_patch()else{return Value::Null;};
        let tiles:Vec<_>=patch[3..].chunks_exact(4098).map(|tile|json!({"x":tile[0],"y":tile[1],"pixels":tile[2..].iter().map(|p|*p as u32).collect::<Vec<_>>()})).collect();
        json!({"tiles":tiles,"full":patch[1]!=0})
    }
}

impl Layer{
    pub(super) fn pixel_at(&self,x:i32,y:i32,width:usize,height:usize)->u32{
        if x>=0 && y>=0 && x<width as i32 && y<height as i32{return self.pixels[y as usize*width+x as usize];}
        self.exterior.get(&key(x.div_euclid(128),y.div_euclid(128))).map_or(0,|v|v[y.rem_euclid(128) as usize*128+x.rem_euclid(128) as usize])
    }
}

impl Layer{
    pub(super) fn remapped(&self,width:usize,height:usize,empty:&Arc<Vec<u32>>,map:impl Fn(i32,i32)->(i32,i32))->Result<Layer,String>{
        let mut out=self.clone();out.pixels=empty.clone();out.exterior.clear();out.mask=self.mask.as_ref().map(|_|Arc::new(vec![255;width*height]));
        let mut write=|x:i32,y:i32,color:u32|->Result<(),String>{
            if color>>24==0{return Ok(());}let(x,y)=map(x,y);
            if x< -2048 || y< -2048 || x>=width as i32+2048 || y>=height as i32+2048{return Err("Movement exceeds off-canvas storage bounds".into());}
            if x>=0 && y>=0 && x<width as i32 && y<height as i32{Arc::make_mut(&mut out.pixels)[y as usize*width+x as usize]=color;}
            else{let tile=out.exterior.entry(key(x.div_euclid(128),y.div_euclid(128))).or_insert_with(||Arc::new(vec![0;128*128]));Arc::make_mut(tile)[y.rem_euclid(128) as usize*128+x.rem_euclid(128) as usize]=color;if out.exterior.len()>256{return Err("Movement exceeds off-canvas storage capacity".into());}}Ok(())
        };
        if !Arc::ptr_eq(&self.pixels,empty){for(y,row)in self.pixels.chunks(width).enumerate(){for(x,pixel)in row.iter().enumerate(){write(x as i32,y as i32,*pixel)?;}}}
        for(k,tile)in &self.exterior{let(tx,ty)=coords(*k);for(y,row)in tile.chunks(128).enumerate(){for(x,pixel)in row.iter().enumerate(){write(tx*128+x as i32,ty*128+y as i32,*pixel)?;}}}
        if let(Some(before),Some(after))=(&self.mask,out.mask.as_mut()){let mask=Arc::make_mut(after);for y in 0..height{for x in 0..width{let(xx,yy)=map(x as i32,y as i32);if xx>=0 && yy>=0 && xx<width as i32 && yy<height as i32{mask[yy as usize*width+xx as usize]=before[y*width+x];}}}}
        Ok(out)
    }
}
