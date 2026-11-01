use super::{Change, Layer, State, MAX_PIXELS};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, fs::{self, File, OpenOptions}, io::{BufReader, BufWriter, Read, Write}, path::{Path, PathBuf}, sync::{Arc, atomic::{AtomicU64, Ordering}}};

static NEXT_FILE: AtomicU64 = AtomicU64::new(1);
pub(super) struct Entry { path: PathBuf }

/// An undo step on its way to disk. The drawing thread keeps using `change` until the archive
/// thread stores its result; dropping the last reference to a finished entry removes the file.
pub(super) struct Archiving { change: Change, result: std::sync::Mutex<Option<Result<Entry, String>>> }
impl Archiving {
    pub(super) fn new(change: Change) -> Self { Self { change, result: std::sync::Mutex::new(None) } }
    pub(super) fn change(&self) -> &Change { &self.change }
    pub(super) fn run(&self, directory: &Path) {
        let result = Entry::write(&self.change, directory);
        *self.result.lock().unwrap() = Some(result);
    }
    pub(super) fn take_result(&self) -> Option<Result<Entry, String>> { self.result.lock().unwrap().take() }
}
impl Drop for Entry { fn drop(&mut self) { let _ = fs::remove_file(&self.path); } }
#[derive(Serialize,Deserialize)]
struct StoredLayer { exterior:Vec<(i64,usize)>, metadata:Layer, pixels:usize, mask:Option<usize>, #[serde(default)] dims:[usize;2] }
#[derive(Serialize,Deserialize)]
struct StoredState {groups:Vec<super::LayerGroup>, layers:Vec<StoredLayer>, active:usize, background:u32, background_visible:bool }
#[derive(Serialize,Deserialize)]
enum Header {
    Pixels {id:u32,rect:[usize;4]},
    PixelTiles{id:u32,rects:Vec<[usize;4]>},
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
        let mut pixel_ids:HashMap<(bool,usize),usize>=HashMap::new();let mut mask_ids=HashMap::new();
        let mut state=|s:&State|StoredState{groups:s.groups.clone(),active:s.active,background:s.background,background_visible:s.background_visible,layers:s.layers.iter().map(|l|{
            let pixel=*pixel_ids.entry((false,l.pixels.generation() as usize)).or_insert_with(||{pixels.push(Arc::new(l.pixels.to_vec()));pixels.len()-1});
            let mask=l.mask.as_ref().map(|m|*mask_ids.entry(Arc::as_ptr(m)).or_insert_with(||{masks.push(m.clone());masks.len()-1}));
            let exterior=l.exterior.iter().map(|(key,tile)|(*key,*pixel_ids.entry((true,Arc::as_ptr(tile) as usize)).or_insert_with(||{pixels.push(tile.clone());pixels.len()-1}))).collect();
            let mut metadata=l.clone();metadata.pixels=super::Tiles::blank(0,0);metadata.mask=None;metadata.exterior.clear();
            StoredLayer{exterior,metadata,pixels:pixel,mask,dims:[l.pixels.width(),l.pixels.height()]}
        }).collect()};
        let header=match change{
            Change::Pixels{id,rect,..}=>Header::Pixels{id:*id,rect:*rect},
            Change::PixelTiles{id,tiles}=>Header::PixelTiles{id:*id,rects:tiles.iter().map(|t|t.rect).collect()},
            Change::Mask{id,rect,..}=>Header::Mask{id:*id,rect:*rect},
            Change::Structure{before,after}=>Header::Structure{before:state(before),after:state(after)},
            Change::Archived(_) | Change::Archiving(_)=>return Err("History already archived".into()),
        };
        write_block(&mut zipped,&serde_json::to_vec(&header).map_err(|e|e.to_string())?)?;
        match change {
            Change::Pixels{before,after,..}=>{write_block(&mut zipped,bytemuck::cast_slice(before))?;write_block(&mut zipped,bytemuck::cast_slice(after))?;},
            Change::PixelTiles{tiles,..}=>{for t in tiles{write_block(&mut zipped,bytemuck::cast_slice(&t.before))?;write_block(&mut zipped,bytemuck::cast_slice(&t.after))?;}},
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
            Header::PixelTiles{id,rects}=>{
                if rects.len()>16384{return Err("Invalid history tile count".into());}
                let mut tiles=Vec::with_capacity(rects.len());let mut total=0;
                for rect in rects{
                    if rect[0]>=rect[2] || rect[1]>=rect[3] || rect[2]>8192 || rect[3]>8192{return Err("Invalid history tile bounds".into());}
                    let area=(rect[2]-rect[0])*(rect[3]-rect[1]);total+=area;
                    if total>MAX_PIXELS{return Err("Invalid history tile area".into());}
                    let mut pixels=||->Result<Vec<u32>,String>{let bytes=read_block(&mut reader,area*4)?;if bytes.len()!=area*4{return Err("Invalid history tile pixels".into());}Ok(bytes.chunks_exact(4).map(|c|u32::from_ne_bytes(c.try_into().unwrap())).collect())};
                    tiles.push(super::PixelTile{rect,before:pixels()?,after:pixels()?});
                }
                Change::PixelTiles{id,tiles}
            },
            Header::Mask{id,rect}=>Change::Mask{id,rect,before:read_block(&mut reader,MAX_PIXELS)?,after:read_block(&mut reader,MAX_PIXELS)?},
            Header::Structure{before,after}=>{
                let mut count=[0;8];reader.read_exact(&mut count).map_err(|e|e.to_string())?;
                let n=u64::from_le_bytes(count) as usize;if n>(before.layers.len()+after.layers.len())*257{return Err("Invalid history layers".into());}
                let mut pixels=vec![];for _ in 0..n{pixels.push(Arc::new(read_pixels(&mut reader)?));}
                reader.read_exact(&mut count).map_err(|e|e.to_string())?;
                let n=u64::from_le_bytes(count) as usize;if n>(before.layers.len()+after.layers.len())*257{return Err("Invalid history masks".into());}
                let mut masks=vec![];for _ in 0..n{masks.push(Arc::new(read_block(&mut reader,MAX_PIXELS)?));}
                // Layers that shared a buffer before the save still share tiles after the load.
                let mut built:HashMap<usize,super::Tiles>=HashMap::new();
                let mut restore=|s:StoredState|->Result<State,String>{Ok(State{groups:s.groups,active:s.active,background:s.background,background_visible:s.background_visible,layers:s.layers.into_iter().map(|l|{
                    let mut layer=l.metadata;layer.exterior=l.exterior.into_iter().map(|(k,i)|Ok((k,pixels.get(i).ok_or("Missing off-canvas history tile")?.clone()))).collect::<Result<_,String>>()?;let flat=pixels.get(l.pixels).ok_or("Missing history pixels")?;layer.pixels=built.entry(l.pixels).or_insert_with(||super::Tiles::from_flat(flat.as_slice(),l.dims[0],l.dims[1])).clone();
                    layer.mask=match l.mask{Some(i)=>Some(masks.get(i).ok_or("Missing history mask")?.clone()),None=>None};Ok(layer)
                }).collect::<Result<Vec<_>,String>>()?})};
                Change::Structure{before:restore(before)?,after:restore(after)?}
            }
        })
    }
}
