use super::{Change, Layer, State, MAX_PIXELS};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, fs::{self, File, OpenOptions}, io::{BufReader, BufWriter, Read, Write}, path::{Path, PathBuf}, sync::{Arc, atomic::{AtomicU64, Ordering}}};

static NEXT_FILE: AtomicU64 = AtomicU64::new(1);
pub(super) struct Entry { path: PathBuf }
impl Drop for Entry { fn drop(&mut self) { let _ = fs::remove_file(&self.path); } }
#[derive(Serialize,Deserialize)]
struct StoredLayer { exterior:Vec<(i64,usize)>, metadata:Layer, pixels:usize, mask:Option<usize> }
#[derive(Serialize,Deserialize)]
struct StoredState {groups:Vec<super::LayerGroup>, layers:Vec<StoredLayer>, active:usize, background:u32, background_visible:bool }
#[derive(Serialize,Deserialize)]
enum Header {
    Pixels {id:u32,rect:[usize;4]},
    Mask {id:u32,rect:[usize;4]},
    Structure {before:StoredState,after:StoredState},
}
fn write_block(w:&mut impl Write, data:&[u8])->Result<(),String>{
    w.write_all(&(data.len() as u64).to_le_bytes()).and_then(|_|w.write_all(data)).map_err(|e|e.to_string())
}
fn read_block(r:&mut impl Read, max:usize)->Result<Vec<u8>,String>{
    let mut size=[0;8];r.read_exact(&mut size).map_err(|e|e.to_string())?;
    let size=u64::from_le_bytes(size);if size>max as u64{return Err("Invalid history buffer size".into());}
    let mut bytes=vec![0;size as usize];r.read_exact(&mut bytes).map_err(|e|e.to_string())?;Ok(bytes)
}
fn read_pixels(r:&mut impl Read)->Result<Vec<u32>,String>{
    let bytes=read_block(r,MAX_PIXELS*4)?;
    if bytes.len()%4!=0{return Err("Invalid history pixels".into());}
    Ok(bytes.chunks_exact(4).map(|c|u32::from_ne_bytes(c.try_into().unwrap())).collect())
}
impl Entry {
    #[cfg(test)]
    pub(super) fn remove_for_test(&self){std::fs::remove_file(&self.path).unwrap();}
    pub(super) fn write(change: &Change, directory: &Path) -> Result<Self, String> {
        fs::create_dir_all(directory).map_err(|e| e.to_string())?;
        let path=directory.join(format!("ink-history-{}-{}.gz",std::process::id(),NEXT_FILE.fetch_add(1,Ordering::Relaxed)));
        let file=OpenOptions::new().write(true).create_new(true).open(&path).map_err(|e|e.to_string())?;
        let entry=Self{path};
        let mut zipped=flate2::write::GzEncoder::new(BufWriter::new(file),flate2::Compression::fast());
        let mut pixels:Vec<Arc<Vec<u32>>>=vec![];let mut masks:Vec<Arc<Vec<u8>>>=vec![];
        let mut pixel_ids=HashMap::new();let mut mask_ids=HashMap::new();
        let mut state=|s:&State|StoredState{groups:s.groups.clone(),active:s.active,background:s.background,background_visible:s.background_visible,layers:s.layers.iter().map(|l|{
            let pixel=*pixel_ids.entry(Arc::as_ptr(&l.pixels)).or_insert_with(||{pixels.push(l.pixels.clone());pixels.len()-1});
            let mask=l.mask.as_ref().map(|m|*mask_ids.entry(Arc::as_ptr(m)).or_insert_with(||{masks.push(m.clone());masks.len()-1}));
            let exterior=l.exterior.iter().map(|(key,tile)|(*key,*pixel_ids.entry(Arc::as_ptr(tile)).or_insert_with(||{pixels.push(tile.clone());pixels.len()-1}))).collect();
            let mut metadata=l.clone();metadata.pixels=Arc::new(vec![]);metadata.mask=None;metadata.exterior.clear();
            StoredLayer{exterior,metadata,pixels:pixel,mask}
        }).collect()};
        let header=match change{
            Change::Pixels{id,rect,..}=>Header::Pixels{id:*id,rect:*rect},
            Change::Mask{id,rect,..}=>Header::Mask{id:*id,rect:*rect},
            Change::Structure{before,after}=>Header::Structure{before:state(before),after:state(after)},
            Change::Archived(_)=>return Err("History already archived".into()),
        };
        write_block(&mut zipped,&serde_json::to_vec(&header).map_err(|e|e.to_string())?)?;
        match change {
            Change::Pixels{before,after,..}=>{write_block(&mut zipped,bytemuck::cast_slice(before))?;write_block(&mut zipped,bytemuck::cast_slice(after))?;},
            Change::Mask{before,after,..}=>{write_block(&mut zipped,before)?;write_block(&mut zipped,after)?;},
            Change::Structure{..}=>{
                zipped.write_all(&(pixels.len() as u64).to_le_bytes()).map_err(|e|e.to_string())?;
                for p in pixels{write_block(&mut zipped,bytemuck::cast_slice(p.as_ref()))?;}
                zipped.write_all(&(masks.len() as u64).to_le_bytes()).map_err(|e|e.to_string())?;
                for m in masks{write_block(&mut zipped,&m)?;}
            },_=>unreachable!(),
        }
        zipped.finish().map_err(|e|e.to_string())?.flush().map_err(|e|e.to_string())?;Ok(entry)
    }
    pub(super) fn read(&self)->Result<Change,String>{
        let mut reader=flate2::read::GzDecoder::new(BufReader::new(File::open(&self.path).map_err(|e|e.to_string())?));
        let header:Header=serde_json::from_slice(&read_block(&mut reader,16*1024*1024)?).map_err(|e|e.to_string())?;
        Ok(match header{
            Header::Pixels{id,rect}=>Change::Pixels{id,rect,before:read_pixels(&mut reader)?,after:read_pixels(&mut reader)?},
            Header::Mask{id,rect}=>Change::Mask{id,rect,before:read_block(&mut reader,MAX_PIXELS)?,after:read_block(&mut reader,MAX_PIXELS)?},
            Header::Structure{before,after}=>{
                let mut count=[0;8];reader.read_exact(&mut count).map_err(|e|e.to_string())?;
                let n=u64::from_le_bytes(count) as usize;if n>(before.layers.len()+after.layers.len())*257{return Err("Invalid history layers".into());}
                let mut pixels=vec![];for _ in 0..n{pixels.push(Arc::new(read_pixels(&mut reader)?));}
                reader.read_exact(&mut count).map_err(|e|e.to_string())?;
                let n=u64::from_le_bytes(count) as usize;if n>(before.layers.len()+after.layers.len())*257{return Err("Invalid history masks".into());}
                let mut masks=vec![];for _ in 0..n{masks.push(Arc::new(read_block(&mut reader,MAX_PIXELS)?));}
                let restore=|s:StoredState|->Result<State,String>{Ok(State{groups:s.groups,active:s.active,background:s.background,background_visible:s.background_visible,layers:s.layers.into_iter().map(|l|{
                    let mut layer=l.metadata;layer.exterior=l.exterior.into_iter().map(|(k,i)|Ok((k,pixels.get(i).ok_or("Missing off-canvas history tile")?.clone()))).collect::<Result<_,String>>()?;layer.pixels=pixels.get(l.pixels).ok_or("Missing history pixels")?.clone();
                    layer.mask=match l.mask{Some(i)=>Some(masks.get(i).ok_or("Missing history mask")?.clone()),None=>None};Ok(layer)
                }).collect::<Result<Vec<_>,String>>()?})};
                Change::Structure{before:restore(before)?,after:restore(after)?}
            }
        })
    }
}
