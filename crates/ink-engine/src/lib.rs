#![recursion_limit = "256"]
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::sync::Arc;
use tiles::Tiles;
mod exterior;
mod transform;
mod groups;
mod liquify;
mod brush_color;
mod tiles;
mod dab_paint;
mod brush_texture;
mod stroke_path;
mod composite_cache;
use groups::LayerGroup;
mod selection;
mod gpu;
mod psd;
mod masks;
mod history;
mod binfmt;
mod psd_import;

const MAX_PIXELS: usize = 16_777_216;
const MAX_LAYERS:usize=4096;
const PIXEL_BUDGET: usize = 67_108_864;
const HISTORY_BUDGET: usize = 96 * 1024 * 1024;

#[derive(Clone, Serialize, Deserialize)]
pub struct Layer {
    #[serde(default)] pub adjustment:Option<(String,f32)>,
    #[serde(default)] pub exterior: std::collections::BTreeMap<i64,Arc<Vec<u32>>>,
    #[serde(default)] pub group_id:Option<u32>,
    #[serde(default="default_true")] pub group_visible:bool,
    #[serde(default)] pub group_locked:bool,
    pub id: u32,
    pub name: String,
    pub visible: bool,
    pub opacity: f32,
    pub blend: String,
    pub alpha_lock: bool,
    pub locked: bool,
    pub clipping: bool,
    pub pixels: tiles::Tiles,
    pub mask: Option<Arc<Vec<u8>>>,
    pub mask_enabled: bool,
}

#[derive(Clone, Serialize, Deserialize)]
struct State {
    #[serde(default)] groups:Vec<LayerGroup>,
    layers: Vec<Layer>,
    active: usize,
    background: u32,
    background_visible: bool,
}

#[derive(Serialize, Deserialize)]
struct PixelTile{rect:[usize;4],before:Vec<u32>,after:Vec<u32>}

#[derive(Serialize, Deserialize)]
enum Change {
    #[serde(skip)]
    Archived(history::Entry),
    /// Being written to the history cache on the archive thread; still usable from memory.
    #[serde(skip)]
    Archiving(Arc<history::Archiving>),
    Mask {id:u32,rect:[usize;4],before:Vec<u8>,after:Vec<u8>},
    Pixels { id: u32, rect: [usize; 4], before: Vec<u32>, after: Vec<u32> },
    PixelTiles {id:u32,tiles:Vec<PixelTile>},
    Structure { before: State, after: State },
}

impl Change {
    fn bytes(&self) -> usize {
        match self {
            Self::Archived(_) | Self::Archiving(_) => 0,
            Self::Mask {before,after,..} => before.len()+after.len(),
            Self::Pixels { before, after, .. } => (before.len() + after.len()) * 4,
            Self::PixelTiles{tiles,..}=>tiles.iter().map(|t|(t.before.len()+t.after.len())*4).sum(),
            Self::Structure { before, after } => (before.layers.len() + after.layers.len()) * 256
                + before.layers.iter().map(|l| l.pixels.len() * 4+l.mask.as_ref().map_or(0,|m|m.len())).sum::<usize>()
                + after.layers.iter().map(|l| l.pixels.len() * 4+l.mask.as_ref().map_or(0,|m|m.len())).sum::<usize>(),
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct Sample { pub x: f32, pub y: f32, pub pressure: f32, pub tilt: f32 }

#[derive(Clone, Copy)]
pub struct Brush {
    pub kind: u8,
    pub color: u32,
    pub size: f32,
    pub opacity: f32,
    pub flow: f32,
    pub stabilization: f32,
    pub pressure_size: f32,
    pub pressure_opacity: f32,
    pub fade_length: f32,
}

impl Default for Brush {
    fn default() -> Self {
        Self { kind: 0, color: 0xff202020, size: 16.0, opacity: 1.0, flow: 1.0,
            stabilization: 0.12, pressure_size: 0.8, pressure_opacity: 0.0, fade_length:0.0 }
    }
}

fn brush_shape(kind:u8)->u8 {
    if kind>=128 {kind&127} else if (64..=127).contains(&kind) {kind-64} else if kind==6 {2} else {kind}
}
fn dab_spacing(brush: Brush) -> f32 {
    stroke_path::spacing(brush, Sample{x:0.0,y:0.0,pressure:1.0,tilt:0.0})
}

#[derive(Clone,Copy)]
struct RecordedStamp {direction:[f32;2],sample:Sample,brush:Brush,distance:f32}
struct Stroke {path_delta:[f32;2], deferred_start:bool, exterior_before:State, exterior_changed:bool,loaded_paint:Option<brush_color::PaintLoad>,mix_distance:f32,direction:[f32;2], effects:brush_color::BrushColor, mask_before:Option<Arc<Vec<u8>>>, rect: [usize; 4], before: tiles::Tiles, layer: usize, last: Sample, filtered: Sample, brush: Brush, seed: u32, smudge: Option<Vec<u32>>, pending: Vec<gpu::Dab>, gpu_paint: bool, distance_to_dab: f32, distance:f32, stamp_distance:f32,records:Vec<RecordedStamp>, replay_end:Option<f32>, input_time:Option<f32>, input_position:(f32,f32), speed:f32, moving:bool }

pub struct Engine {
    composite_cache:composite_cache::CompositeCache,
    exterior_dirty:bool,
    exterior_changed_tiles:std::collections::BTreeSet<i64>,
    gpu: Option<gpu::Gpu>,
    renderer_requested: String,
    renderer_error: Option<String>,
    pub width: usize,
    pub height: usize,
    dpi: u32,
    state: State,
    next_id: u32,
    mask_edit: bool,
    symmetry: String,
    brush_color:brush_color::BrushColor,
    input_error: Option<String>,
    history_directory: std::path::PathBuf,
    history_error: Option<String>,
    /// Hands old undo steps to a background thread, so stroke ends never wait on compression or disk.
    archiver: Option<std::sync::mpsc::Sender<(Arc<history::Archiving>,std::path::PathBuf)>>,
    undo: Vec<Change>,
    undo_names:Vec<String>,redo_names:Vec<String>,action_name:String,history_notice:String,
    redo: Vec<Change>,
    stroke: Option<Stroke>,
    liquify:Option<liquify::Liquify>,
    flood_preview: Option<(usize, Tiles)>,
    selection: Option<Vec<bool>>,
    selection_alpha:Option<Vec<u8>>,
    selection_base:Option<Vec<u8>>,
    selection_version:u64,
    saved_selections:std::collections::BTreeMap<String,Vec<(u32,u32)>>,
    clipboard:Option<selection::Clipboard>,
    pub dirty: bool,
    damage: Option<[usize; 4]>,
    save_cache: binfmt::SaveCache,
    /// Layer whose adjustment slider drag is being merged into the newest undo step.
    adjust_coalesce: Option<u32>,
}

fn finite(v: f32, fallback: f32) -> f32 { if v.is_finite() { v } else { fallback } }
/// Whether the GPU brush can reproduce these colour effects. Paint Mixing works on the GPU when the
/// dab colour comes from the pre-stroke snapshot (pickup or wetness) and the colour is Solid;
/// mixing straight from live pixels, or with multicolour strands, stays on the CPU.
fn gpu_mixes(kind:u8,effects:&brush_color::BrushColor)->bool{
    effects.mix==0.0 || kind>=64 || kind==5 || kind==6
        || ((effects.mode=="Solid" || effects.mode.is_empty()) && (effects.pickup>0.0 || effects.wetness>0.0))
}
fn channel(c: u32, shift: u32) -> f32 { ((c >> shift) & 255) as f32 / 255.0 }
fn pack(r: f32, g: f32, b: f32, a: f32) -> u32 {
    let c = |v: f32| (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u32;
    (c(a) << 24) | (c(r) << 16) | (c(g) << 8) | c(b)
}

pub fn composite(dst: u32, src: u32, opacity: f32, mode: &str) -> u32 {
    let sa = channel(src, 24) * opacity;
    if sa <= 0.0 { return dst; }
    if mode == "Normal" && sa >= 1.0 { return src; }
    let da = channel(dst, 24);
    let a = sa + da * (1.0 - sa);
    if mode=="Normal" {
        if da==0.0{
            if sa<0.0001{let w=sa/0.0001;return pack(channel(src,16)*w,channel(src,8)*w,channel(src,0)*w,sa);}
            return (src&0x00ffffff)|(((sa*255.0+0.5) as u32)<<24);
        }
        let weight=sa/a.max(0.0001);
        return pack(channel(dst,16)+(channel(src,16)-channel(dst,16))*weight,
            channel(dst,8)+(channel(src,8)-channel(dst,8))*weight,
            channel(dst,0)+(channel(src,0)-channel(dst,0))*weight,a);
    }
    let mut out = [0.0; 3];
    let darker_source = mode=="Darker Colour" && [16,8,0].iter().map(|shift|(src>>shift)&255).sum::<u32>() < [16,8,0].iter().map(|shift|(dst>>shift)&255).sum::<u32>();
    for (i, shift) in [16, 8, 0].iter().enumerate() {
        let s = channel(src, *shift); let d = channel(dst, *shift);
        let b = match mode {
            "Multiply" => s * d,
            "Colour Burn" => if s<=0.0{0.0}else{1.0-((1.0-d)/s).min(1.0)},
            "Linear Burn" => (d+s-1.0).max(0.0),
            "Colour Dodge" => if s>=1.0{1.0}else{(d/(1.0-s)).min(1.0)},
            "Subtract" => (d-s).max(0.0),
            "Exclusion" => d+s-2.0*d*s,
            "Hard Light" => if s<0.5{2.0*s*d}else{1.0-2.0*(1.0-s)*(1.0-d)},
            "Screen" => 1.0 - (1.0 - s) * (1.0 - d),
            "Overlay" => if d < 0.5 { 2.0 * s * d } else { 1.0 - 2.0 * (1.0 - s) * (1.0 - d) },
            "Add" => (s + d).min(1.0),
            "Darker Colour" => if darker_source{s}else{d},
            "Darken" => s.min(d), "Lighten" => s.max(d), "Difference" => (s - d).abs(),
            _ => s,
        };
        out[i] = (sa * (1.0 - da) * s + sa * da * b + (1.0 - sa) * da * d) / a.max(0.0001);
    }
    pack(out[0], out[1], out[2], a)
}

impl Engine {
    pub fn new(width: usize, height: usize) -> Result<Self, String> {
        let pixels = width.checked_mul(height).ok_or("Canvas dimensions overflow")?;
        if width < 16 || height < 16 || width > 8192 || height > 8192 || pixels > MAX_PIXELS {
            return Err("Canvas must be at least 16 × 16 and at most 16 megapixels".into());
        }
        Ok(Self {composite_cache:Default::default(),exterior_changed_tiles:Default::default(),exterior_dirty:true,gpu: None, renderer_requested: "cpu".into(), renderer_error: None, width, height, dpi:300, state: State { groups:vec![],layers: vec![Layer {adjustment:None,group_id:None,group_visible:true,group_locked:false,
            id: 1, name: "Layer 1".into(), visible: true, opacity: 1.0, blend: "Normal".into(),
            alpha_lock: false, locked:false, clipping: false, mask:None,mask_enabled:true,pixels: tiles::Tiles::blank(width,height),exterior:Default::default(),
        }], active: 0, background: 0xfffaf9f6, background_visible: true }, next_id: 2,mask_edit:false,brush_color:brush_color::BrushColor::default(),symmetry:"Off".into(),
            input_error:None,history_directory:std::env::temp_dir(),history_error:None,archiver:None,undo: vec![], redo: vec![],undo_names:vec![],redo_names:vec![],action_name:"Edit artwork".into(),history_notice:String::new(), stroke: None,liquify:None, flood_preview: None, selection: None,selection_alpha:None,selection_base:None,selection_version:0,saved_selections:std::collections::BTreeMap::new(),clipboard:None, dirty: true, damage: None, save_cache: Default::default(), adjust_coalesce: None })
    }

    fn record(&mut self, change: Change) {
        self.adjust_coalesce=None;
        self.redo.clear();self.redo_names.clear();
        self.undo_names.push(self.action_name.clone());
        self.undo.push(change);
        if self.undo.len() > 64 { self.undo.remove(0);self.undo_names.remove(0); }
        self.compact_history(HISTORY_BUDGET,true);
        self.dirty = true;
    }

    /// Archives synchronously: when this returns, every step over budget is on disk.
    #[cfg(test)]
    fn compact_history_to(&mut self,budget:usize) {self.compact_history(budget,false);}

    /// Swaps finished background archives in, which frees their in-memory pixels.
    fn settle_history(&mut self) {
        for change in self.undo.iter_mut().chain(self.redo.iter_mut()) {
            let Change::Archiving(job)=change else{continue};
            match job.take_result() {
                Some(Ok(entry))=>*change=Change::Archived(entry),
                // The step stays usable from memory; stop archiving until the cache works again.
                Some(Err(error))=>{self.history_error=Some(format!("Could not cache undo history: {error}"));self.archiver=None;},
                None=>{},
            }
        }
    }

    fn archiver(&mut self)->Option<std::sync::mpsc::Sender<(Arc<history::Archiving>,std::path::PathBuf)>> {
        if self.archiver.is_none() && self.history_error.is_none() {
            let (tx,rx)=std::sync::mpsc::channel::<(Arc<history::Archiving>,std::path::PathBuf)>();
            let spawned=std::thread::Builder::new().name("ink-history".into()).spawn(move||{for(job,directory)in rx{job.run(&directory);}});
            if spawned.is_ok(){self.archiver=Some(tx);}
        }
        self.archiver.clone()
    }

    fn compact_history(&mut self,budget:usize,background:bool) {
        self.settle_history();
        let archiver=if background{match self.archiver(){Some(a)=>Some(a),None=>return}}else{None};
        // Count each shared bitmap once, excluding buffers already owned by the live canvas.
        let mut seen=std::collections::HashSet::new();
        for l in &self.state.layers {seen.insert(l.pixels.generation() as usize);if let Some(m)=&l.mask{seen.insert(Arc::as_ptr(m) as usize);}}
        let mut costs=Vec::new();
        for change in self.undo.iter().chain(self.redo.iter()) {
            let cost=match change {
                Change::Structure{before,after}=>before.layers.iter().chain(after.layers.iter()).map(|l|{
                    let pixels=if seen.insert(l.pixels.generation() as usize){l.pixels.allocated_bytes()}else{0};
                    pixels+l.exterior.values().map(|t|if seen.insert(Arc::as_ptr(t) as usize){t.len()*4}else{0}).sum::<usize>()+l.mask.as_ref().map_or(0,|m|if seen.insert(Arc::as_ptr(m) as usize){m.len()}else{0})
                }).sum(),
                _=>change.bytes(),
            };costs.push(cost);
        }
        let mut bytes:usize=costs.iter().sum();
        for (change,cost) in self.undo.iter_mut().chain(self.redo.iter_mut()).zip(costs) {
            if bytes<=budget {break;}
            if matches!(change,Change::Archived(_)|Change::Archiving(_)) || cost==0 {continue;}
            if let Some(archiver)=&archiver {
                let placeholder=Change::Pixels{id:0,rect:[0;4],before:vec![],after:vec![]};
                let job=Arc::new(history::Archiving::new(std::mem::replace(change,placeholder)));
                *change=Change::Archiving(job.clone());
                if archiver.send((job,self.history_directory.clone())).is_err(){break;}
                bytes-=cost;continue;
            }
            match history::Entry::write(change,&self.history_directory) {
                Ok(entry)=>{bytes-=cost;*change=Change::Archived(entry);},
                Err(error)=>{self.history_error=Some(format!("Could not cache undo history: {error}"));break;}
            }
        }
    }

    fn structure<F: FnOnce(&mut Self)>(&mut self, f: F) {
        self.end();
        let before = self.state.clone(); f(self);
        self.record(Change::Structure { before, after: self.state.clone() });
    }

    fn pixel_change(&mut self, layer: usize, before: impl Into<Vec<u32>>) {
        self.pixel_change_rect(layer, before.into(), [0,0,self.width,self.height]);
    }

    /// `before` holds the full canvas; only `search` is compared and recorded.
    fn pixel_change_rect(&mut self, layer: usize, before: impl Into<Vec<u32>>, search: [usize;4]) {
        let before:Vec<u32>=before.into();let before=&before[..];let w=self.width;
        let local:Vec<u32>=(search[1]..search[3]).flat_map(|y|before[y*w+search[0]..y*w+search[2]].iter().copied()).collect();
        self.pixel_change_core(layer,&local,search);
    }

    /// Records a finished stroke against its captured pre-stroke pixels.
    fn pixel_change_stroke(&mut self, layer: usize, before: &tiles::Tiles, search: [usize;4]) {
        let local=before.rect(search);
        self.pixel_change_core(layer,&local,search);
    }

    /// `before` is the pre-change pixels of `search`, row-major with the search width.
    fn pixel_change_core(&mut self, layer: usize, before: &[u32], search: [usize;4]) {
        let sw=search[2]-search[0];
        let after = &self.state.layers[layer].pixels;
        let mut rect = [self.width, self.height, 0, 0];
        let columns=self.width.div_ceil(64);
        let mut touched=vec![false;columns*self.height.div_ceil(64)];
        for y in search[1]..search[3] { for x in search[0]..search[2] {
            let i = y * self.width + x;
            if before[(y-search[1])*sw+(x-search[0])] != after[i] {
                touched[y/64*columns+x/64]=true;
                rect[0] = rect[0].min(x); rect[1] = rect[1].min(y);
                rect[2] = rect[2].max(x + 1); rect[3] = rect[3].max(y + 1);
            }
        }
        }
        if rect[2] == 0 { return; }
        let mut tile_rects=Vec::new();
        for (i,changed)in touched.into_iter().enumerate(){if changed{
            let x=i%columns*64;let y=i/columns*64;
            tile_rects.push([x.max(rect[0]),y.max(rect[1]),(x+64).min(rect[2]),(y+64).min(rect[3])]);
        }}
        let tiled_pixels:usize=tile_rects.iter().map(|r|(r[2]-r[0])*(r[3]-r[1])).sum();
        if tiled_pixels*4<(rect[2]-rect[0])*(rect[3]-rect[1])*3{
            let tiles=tile_rects.into_iter().map(|r|{
                let size=(r[2]-r[0])*(r[3]-r[1]);let mut old=Vec::with_capacity(size);let mut new=Vec::with_capacity(size);
                for y in r[1]..r[3]{let brow=(y-search[1])*sw+(r[0]-search[0]);old.extend_from_slice(&before[brow..brow+r[2]-r[0]]);new.extend(after.row(y,r[0],r[2]));}
                PixelTile{rect:r,before:old,after:new}
            }).collect();
            self.record(Change::PixelTiles{id:self.state.layers[layer].id,tiles});return;
        }
        let mut old = vec![]; let mut new = vec![];
        for y in rect[1]..rect[3] {
            let brow=(y-search[1])*sw+(rect[0]-search[0]);
            old.extend_from_slice(&before[brow..brow+rect[2]-rect[0]]);
            new.extend(after.row(y,rect[0],rect[2]));
        }
        self.record(Change::Pixels { id: self.state.layers[layer].id, rect, before: old, after: new });
    }

    fn apply_change(&mut self, c: &Change, forward: bool) {
        match c {
            Change::Archived(_) => unreachable!("History is loaded before applying"),
            Change::Archiving(job) => {let job=job.clone();self.apply_change(job.change(),forward);return;},
            Change::Mask{id,rect,before,after} => {if let Some(l)=self.state.layers.iter_mut().find(|l|l.id==*id){if let Some(mask)=l.mask.as_mut(){let mask=Arc::make_mut(mask);let src=if forward{after}else{before};let w=rect[2]-rect[0];for(row,y)in(rect[1]..rect[3]).enumerate(){mask[y*self.width+rect[0]..y*self.width+rect[2]].copy_from_slice(&src[row*w..(row+1)*w]);}}}},
            Change::Structure { before, after } => self.state = if forward { after.clone() } else { before.clone() },
            Change::PixelTiles{id,tiles}=>{
                if let Some(l)=self.state.layers.iter_mut().find(|l|l.id==*id){
                    let pixels=&mut l.pixels;
                    for t in tiles{let r=t.rect;let w=r[2]-r[0];let src=if forward{&t.after}else{&t.before};
                        for(row,y)in(r[1]..r[3]).enumerate(){pixels.set_row(y,r[0],&src[row*w..(row+1)*w]);}
                    }
                }
            },
            Change::Pixels { id, rect, before, after } => {
                if let Some(l) = self.state.layers.iter_mut().find(|l| l.id == *id) {
                    let src = if forward { after } else { before };
                    let pixels = &mut l.pixels;
                    let w = rect[2] - rect[0];
                    for (row, y) in (rect[1]..rect[3]).enumerate() {
                        pixels.set_row(y, rect[0], &src[row * w..(row + 1) * w]);
                    }
                }
            }
        }
        self.dirty = true;self.exterior_dirty=true;
    }

    fn history_step(&mut self,forward:bool)->bool {
        self.end();self.history_error=None;self.adjust_coalesce=None;self.settle_history();
        let item=if forward{self.redo.pop()}else{self.undo.pop()};
        let Some(change)=item else{self.history_notice=if forward{"Nothing to redo"}else{"Nothing to undo"}.into();return false;};
        let loaded=match &change {Change::Archived(entry)=>Some(entry.read()),_=>None};
        if let Some(Err(error))=&loaded {
            self.history_error=Some(format!("Could not read undo history: {error}"));
            if forward{self.redo.push(change);}else{self.undo.push(change);}return false;
        }
        self.apply_change(loaded.as_ref().map(|r|r.as_ref().unwrap()).unwrap_or(&change),forward);
        let name=if forward{self.redo_names.pop()}else{self.undo_names.pop()}.unwrap_or_else(||"Edit artwork".into());
        self.history_notice=format!("{}: {}",if forward{"Redo"}else{"Undo"},name);
        if forward{self.undo.push(change);self.undo_names.push(name);}else{self.redo.push(change);self.redo_names.push(name);}true
    }
    pub fn undo(&mut self)->bool {self.history_step(false)}
    pub fn redo(&mut self)->bool {self.history_step(true)}

    fn normalize_brush(mut brush:Brush)->Brush {
        brush.size = finite(brush.size, 16.0).clamp(0.5, 600.0);
        brush.opacity = finite(brush.opacity, 1.0).clamp(0.0, 1.0);
        brush.flow = finite(brush.flow, 1.0).clamp(0.01, 1.0);
        brush.stabilization = finite(brush.stabilization, 0.0).clamp(0.0, 0.95);
        brush.pressure_size = finite(brush.pressure_size, 0.8).clamp(0.0, 1.0);
        brush.pressure_opacity = finite(brush.pressure_opacity, 0.0).clamp(0.0, 1.0);
        if brush.kind == 6 || brush.kind>=128 { brush.color = 0; }
        brush.fade_length=finite(brush.fade_length,0.0).clamp(0.0,10000.0);
        brush
    }
    pub fn update_brush(&mut self,brush:Brush){
        let brush=Self::normalize_brush(brush);
        if let Some(stroke)=&self.stroke {if stroke.brush.kind!=brush.kind {self.flush_gpu();}}
        if let Some(stroke)=&mut self.stroke {
            if stroke.brush.kind!=brush.kind {stroke.smudge=None;}
            stroke.brush=brush;stroke.distance_to_dab=stroke.distance_to_dab.min(dab_spacing(brush));
        }
    }
    pub fn begin(&mut self, p: Sample, brush: Brush) {
        self.begin_input(p, brush, None);
    }
    pub fn begin_timed(&mut self, p: Sample, brush: Brush, time: f32) {
        self.begin_input(p, brush, time.is_finite().then_some(time));
    }
    fn begin_input(&mut self, mut p: Sample, mut brush: Brush, time: Option<f32>) {
        self.end();self.cancel_flood();
        self.input_error=None;
        if let Err(error)=self.ensure_edit_capacity(){self.input_error=Some(error);return;}
        if self.state.layers[self.state.active].is_locked() { return; }
        if !p.x.is_finite() || !p.y.is_finite() { return; }
        self.action_name=if brush.kind>=128 || brush.kind==6{"Smudge stroke"}else if brush.kind>=64{"Erase stroke"}else{"Brush stroke"}.into();
        brush=Self::normalize_brush(brush);
        p.pressure=finite(p.pressure,1.0).clamp(0.0,1.0);p.tilt=finite(p.tilt,0.0).clamp(0.0,1.57);
        let mask_before=if self.mask_edit {self.state.layers[self.state.active].mask.clone()}else{None};
        if mask_before.is_some() && (brush.kind==6 || brush.kind>=128){return;}
        let mut exterior_before=self.state.clone();exterior_before.layers[self.state.active].pixels=Tiles::blank(self.width,self.height);
        let mut stroke = Stroke {path_delta:[0.0;2],deferred_start:time.is_some(),exterior_before,exterior_changed:false,loaded_paint:None,mix_distance:0.0,direction:[1.0,0.0],effects:self.brush_color.clone(),mask_before:mask_before.clone(), rect: [self.width,self.height,0,0], before: self.state.layers[self.state.active].pixels.clone(),
            layer: self.state.active, last: p, filtered: p, brush, seed: 0x736561, smudge: None, pending: Vec::new(), gpu_paint: brush.size>=32.0 && mask_before.is_none() && gpu_mixes(brush.kind,&self.brush_color), distance_to_dab: stroke_path::spacing(brush,p), distance:0.0,stamp_distance:0.0,records:vec![],replay_end:None,input_time:time,input_position:(p.x,p.y),speed:0.0,moving:false };
        if stroke.effects.mix>0.0 && brush.kind<64 && brush.kind!=5 && brush.kind!=6 && !self.mask_edit && stroke.effects.mode=="Solid"
            && self.state.layers[stroke.layer].exterior.values().all(|tile|tile.iter().all(|c|*c>>24==0 || (*c&0xffffff)==(brush.color&0xffffff)))
            && ((self.state.layers[stroke.layer].pixels).is_blank() || self.state.layers[stroke.layer].pixels.iter().all(|c|c>>24==0 || (c&0xffffff)==(brush.color&0xffffff))){
            stroke.effects.mix=0.0;stroke.gpu_paint=brush.size>=32.0;
        }

        if let Some(gpu)=&mut self.gpu { gpu.forget_layer(self.state.layers[stroke.layer].id); }
        if stroke.gpu_paint { if let Some(gpu)=&mut self.gpu { gpu.selection(self.selection.as_deref(),self.selection_alpha.as_deref()); } }
        if brush.fade_length==0.0 && time.is_none(){self.stamp(p, &mut stroke);}
        self.stroke = Some(stroke);
    }

    pub fn set_sample_time(&mut self,time:f32){
        if time.is_finite(){if let Some(s)=self.stroke.as_mut(){s.input_time=Some(time);}}
    }
    pub fn replace_stroke(&mut self,points:&[Sample],brush:Brush){
        if points.is_empty() || points.iter().any(|p|!p.x.is_finite() || !p.y.is_finite()){return;}
        if let Some(mut s)=self.stroke.take(){
            // Restore only the previous preview's painted area; retain the original undo snapshot.
            s.pending.clear();
            let r=s.rect;
            if r[0]<r[2] && r[1]<r[3]{
                if let Some(before)=&s.mask_before{
                    if let Some(mask)=self.state.layers[s.layer].mask.as_mut(){let mask=Arc::make_mut(mask);for y in r[1]..r[3]{let at=y*self.width+r[0];mask[at..at+r[2]-r[0]].copy_from_slice(&before[at..at+r[2]-r[0]]);}}
                }else{
                    self.state.layers[s.layer].pixels=s.before.clone();
                }
                let damage=self.damage.get_or_insert(r);
                for i in 0..2{damage[i]=damage[i].min(r[i]);damage[i+2]=damage[i+2].max(r[i+2]);}
            }
            if s.exterior_changed{self.state.layers[s.layer].exterior=s.exterior_before.layers[s.layer].exterior.clone();self.exterior_dirty=true;}
            let p=points[0];
            s.last=p;s.filtered=p;s.path_delta=[0.0;2];s.deferred_start=false;
            s.brush=Self::normalize_brush(brush);s.brush.stabilization=0.0;
            s.gpu_paint=s.brush.size>=32.0 && s.mask_before.is_none() && gpu_mixes(s.brush.kind,&s.effects);
            s.rect=[self.width,self.height,0,0];s.exterior_changed=false;
            s.seed=0x736561;s.smudge=None;s.loaded_paint=None;s.mix_distance=0.0;
            s.distance=0.0;s.stamp_distance=0.0;s.direction=[1.0,0.0];
            s.distance_to_dab=stroke_path::spacing(s.brush,p);s.records.clear();s.replay_end=None;
            s.input_time=None;s.input_position=(p.x,p.y);s.speed=0.0;s.moving=false;
            if s.brush.fade_length==0.0{self.stamp(p,&mut s);}
            self.stroke=Some(s);
        }else{self.begin(points[0],Brush{stabilization:0.0,..brush});}
        for p in &points[1..]{self.sample(*p);}
    }
    pub fn sample_timed(&mut self,p:Sample,time:f32){
        if !p.x.is_finite() || !p.y.is_finite(){return;}
        let mut dt=16.667;
        if let Some(s)=self.stroke.as_mut(){
            if time.is_finite(){
                if let Some(previous)=s.input_time{
                    let elapsed=time-previous;
                    if elapsed<0.0{return;}
                    let distance=(p.x-s.input_position.0).hypot(p.y-s.input_position.1);
                    // Android can deliver distinct positions within the same timestamp tick.
                    if elapsed==0.0 && distance<=0.0001{return;}
                    dt=if elapsed>0.0{elapsed.clamp(0.25,64.0)}else{16.667};
                    let velocity=distance/if elapsed>0.0{elapsed}else{dt};
                    let response=1.0-(-dt/24.0).exp();
                    if !s.moving && velocity>0.0001{s.speed=velocity;}else{s.speed+=(velocity-s.speed)*response;}
                }
                s.input_time=Some(time);s.input_position=(p.x,p.y);
            }
        }
        self.sample_input(p,dt,true);
    }

    pub fn sample(&mut self,p:Sample){self.sample_input(p,16.667,false);}

    fn sample_input(&mut self,p:Sample,dt:f32,timed:bool){
        if !p.x.is_finite() || !p.y.is_finite(){return;}
        if let Some(mut stroke)=self.stroke.take(){
            let raw_distance=(p.x-stroke.filtered.x).hypot(p.y-stroke.filtered.y);
            let speed=if timed{stroke.speed}else{raw_distance/dt};
            let raw_pressure=finite(p.pressure,1.0).clamp(0.0,1.0);
            let mut p=stroke_path::filter(stroke.filtered,p,dt,speed,stroke.brush.stabilization,timed);
            let mut a=stroke.last;
            let distance=(p.x-a.x).hypot(p.y-a.y);
            if distance>0.0001{
                if stroke.deferred_start{
                    // Some digitizers report full pressure on DOWN before their first moving sample.
                    if a.pressure>raw_pressure+0.15{a.pressure=raw_pressure;p.pressure=raw_pressure;}
                    stroke.last=a;
                    stroke.distance_to_dab=stroke_path::spacing(stroke.brush,a);
                    stroke.deferred_start=false;
                }
                stroke.moving=true;
                stroke.direction=[(p.x-a.x)/distance,(p.y-a.y)/distance];
                if stroke.records.is_empty(){self.stamp(a,&mut stroke);}
                for point in stroke_path::curve(a,p,stroke.path_delta){self.stroke_segment(point,&mut stroke);}
                stroke.path_delta=[p.x-a.x,p.y-a.y];
            }
            stroke.filtered=p;
            self.stroke=Some(stroke);
        }
    }

    fn stroke_segment(&mut self,p:Sample,stroke:&mut Stroke){
        let a=stroke.last;
        let distance=(p.x-a.x).hypot(p.y-a.y);
        if distance<=0.0001{return;}
        stroke.direction=[(p.x-a.x)/distance,(p.y-a.y)/distance];
        let mut travel=stroke.distance_to_dab;
        let mut count=0;
        while travel<=distance && count<32768{
            let t=travel/distance;
            let point=Sample{x:a.x+(p.x-a.x)*t,y:a.y+(p.y-a.y)*t,
                pressure:a.pressure+(p.pressure-a.pressure)*t,tilt:a.tilt+(p.tilt-a.tilt)*t};
            stroke.stamp_distance=stroke.distance+travel;
            self.stamp(point,stroke);
            travel+=stroke_path::spacing(stroke.brush,point);count+=1;
        }
        stroke.distance+=distance;
        stroke.distance_to_dab=(travel-distance).max(0.0001);
        stroke.last=p;
    }

    fn stamp(&mut self,p:Sample,stroke:&mut Stroke){
        let original=stroke.brush;
        if stroke.replay_end.is_none() && original.fade_length>0.0 && stroke.input_time.is_some(){
            let fast=(stroke.speed/(original.size.max(1.0)*0.035)).clamp(0.0,2.0)/2.0;
            let coverage=0.995-0.60*fast;
            stroke.brush.opacity*=coverage;
            stroke.brush.fade_length*=0.65+1.35*fast;
        }
        if stroke.replay_end.is_none() && (original.fade_length>0.0 || stroke.records.is_empty()){stroke.records.push(RecordedStamp{direction:stroke.direction,sample:p,brush:stroke.brush,distance:stroke.stamp_distance});}
        let mut points=[p;4];let mut count=1;
        if stroke.brush.kind<128 && stroke.brush.kind!=6 {
            let x=self.width as f32;let y=self.height as f32;
            match self.symmetry.as_str(){
                "Vertical"=>{points[1]=Sample{x:x-p.x,..p};count=2;},
                "Horizontal"=>{points[1]=Sample{y:y-p.y,..p};count=2;},
                "Quadrant"=>{points[1]=Sample{x:x-p.x,..p};points[2]=Sample{y:y-p.y,..p};points[3]=Sample{x:x-p.x,y:y-p.y,..p};count=4;},
                "Radial 4"=>{let dx=p.x-x/2.0;let dy=p.y-y/2.0;for(i,(a,b))in [(-dy,dx),(-dx,-dy),(dy,-dx)].into_iter().enumerate(){points[i+1]=Sample{x:x/2.0+a,y:y/2.0+b,..p};}count=4;},
                _=>{}
            }
        }
        for i in 0..count{let point=points[i];if points[..i].iter().any(|q|(q.x-point.x).abs()<0.001 && (q.y-point.y).abs()<0.001){continue;}self.stamp_one(point,stroke);}
        stroke.brush=original;
    }
    fn stamp_one(&mut self, p: Sample, stroke: &mut Stroke) {
        if stroke.pending.len() >= gpu::MAX_DABS { self.flush_stroke(stroke); }
        let mut brush = stroke.brush;
        let multicolor=brush.kind<64 && brush.kind!=5 && brush.kind!=6 && !self.mask_edit && stroke.effects.mode!="Solid" && !stroke.effects.mode.is_empty();
        let strands=multicolor && stroke.effects.layout>0;
        if brush.kind<64 && brush.kind!=5 && brush.kind!=6 && !self.mask_edit{
            brush.color=stroke.effects.color(brush.color,stroke.stamp_distance);
            if stroke.effects.mix>0.0{
                if stroke.effects.pickup>0.0 || stroke.effects.wetness>0.0{
                    let under=brush_color::sample_paint(&stroke.before,self.width,self.height,p.x,p.y,brush.size*0.18);
                    let step=(stroke.stamp_distance-stroke.mix_distance).max(0.0)/(brush.size*0.5).max(1.0);stroke.mix_distance=stroke.stamp_distance;
                    brush.color=brush_color::transport(brush.color,&mut stroke.loaded_paint,under,stroke.effects.mix,stroke.effects.pickup,stroke.effects.wetness,step);
                }else{
                    let px=p.x.clamp(0.,(self.width-1)as f32)as usize;let py=p.y.clamp(0.,(self.height-1)as f32)as usize;
                    brush.color=brush_color::wet_mix(brush.color,self.state.layers[stroke.layer].pixels[py*self.width+px],stroke.effects.mix);
                }
            }
        }
        if brush.fade_length>0.0 {
            if let Some(end)=stroke.replay_end {
                let fade=((end-stroke.stamp_distance)/brush.fade_length.min(end.max(0.01))).clamp(0.0,1.0);
                if fade<=0.0{return;}
                // Compensate overlapping dabs so opacity, rather than width, fades at the tail.
                if stroke.input_time.is_some(){brush.opacity*=fade;}else{brush.opacity*=1.0-(1.0-fade).powf(dab_spacing(brush)/brush.size.max(1.0));}
            }
        }
        if brush.fade_length>0.0 && stroke.input_time.is_some() && stroke.moving{
            let lead_length=stroke.replay_end.map_or(brush.size*0.75,|end|(brush.size*0.75).min(end*0.2)).max(1.0);
            let t=(stroke.stamp_distance/lead_length).clamp(0.0,1.0);
            brush.opacity*=0.08+0.92*t*t*(3.0-2.0*t);
        }
        if brush.fade_length>0.0 && stroke.input_time.is_some(){brush.opacity=1.0-(1.0-brush.opacity.clamp(0.0,1.0)).powf(dab_spacing(brush)/brush.size.max(1.0));}
        let pressure = finite(p.pressure, 1.0).clamp(0.0, 1.0).powf(0.7);
        let mut radius = brush.size * 0.5 * (1.0 - brush.pressure_size + brush.pressure_size * pressure).max(0.08);
        if matches!(brush_shape(brush.kind), 1 | 3) { radius *= 1.0 + finite(p.tilt, 0.0).clamp(0.0, 1.57) * 0.7; }
        let x0 = ((p.x - radius - 1.0).floor().max(0.0) as usize).min(self.width);
        let y0 = ((p.y - radius - 1.0).floor().max(0.0) as usize).min(self.height);
        let x1 = ((p.x + radius + 1.0).ceil().max(0.0) as usize).min(self.width);
        let y1 = ((p.y + radius + 1.0).ceil().max(0.0) as usize).min(self.height);
        let crosses=p.x-radius<0.0 || p.y-radius<0.0 || p.x+radius>=self.width as f32 || p.y+radius>=self.height as f32;
        if crosses && stroke.gpu_paint {self.flush_stroke(stroke);stroke.gpu_paint=false;}
        if self.gpu.is_some() && stroke.gpu_paint {
            let smudging=brush.kind==6 || brush.kind>=128;
            let initial=smudging && stroke.smudge.is_none();
            if initial { stroke.smudge=Some(Vec::new()); }
            let mut palette=[0u32;12];for(i,c)in stroke.effects.palette.iter().enumerate(){palette[i]=*c;}
            let color_effect=if multicolor{[if stroke.effects.mode=="Palette"{2.0}else{1.0},stroke.effects.layout as f32,stroke.stamp_distance/stroke.effects.cycle.max(20.0),stroke.effects.palette.len() as f32]}else{[0.0;4]};
            // Paint Mixing: the dab colour was mixed above; the contact field and wet edge match stamp_one's CPU path.
            let mixing=brush.kind<64 && stroke.effects.mix>0.0;
            let mut contact=[[0u32;4];21];
            let has_contact=mixing && brush.kind!=5 && brush.kind!=6 && !self.mask_edit && stroke.effects.wetness>0.0;
            if has_contact{
                let field=brush_color::ContactField::new(&stroke.before,self.width,self.height,p.x,p.y,radius,brush.color,stroke.effects.mix*stroke.effects.wetness*0.65);
                for(i,c)in field.colors.iter().enumerate(){contact[i/4][i%4]=*c;}
            }
            let wet=[has_contact as u32 as f32,if mixing{stroke.effects.wetness*0.45}else{0.0},0.0,0.0];
            stroke.pending.push(gpu::Dab { brush:stroke.brush, sample:p, distance:stroke.stamp_distance, params:gpu::Params {
                meta:[self.width as u32,self.height as u32,brush.kind as u32,self.state.layers[stroke.layer].alpha_lock as u32],
                rect:[x0 as u32,y0 as u32,x1 as u32,y1 as u32],
                dab:[p.x,p.y,radius,brush.opacity*brush.flow*(1.0-brush.pressure_opacity+brush.pressure_opacity*pressure)],
                flags:[brush.color,stroke.seed,self.selection.is_some() as u32,initial as u32],region:[0;4],palette,color_effect,direction:[stroke.direction[0],stroke.direction[1],stroke_path::deposition(brush,p),0.0],wet,contact,
            }});
            if !initial { stroke.seed=stroke.seed.wrapping_add(1); }
            return;
        }
        let mut exterior_count=if crosses{self.state.layers.iter().map(|l|l.exterior.len()).sum::<usize>()}else{0};
        let selected = &self.selection;
        let selection_alpha=&self.selection_alpha;
        let contact=if !strands && brush.kind<64 && brush.kind!=5 && brush.kind!=6 && !self.mask_edit && stroke.effects.mix>0.0 && stroke.effects.wetness>0.0{
            Some(brush_color::ContactField::new(&stroke.before,self.width,self.height,p.x,p.y,radius,brush.color,stroke.effects.mix*stroke.effects.wetness*0.65))
        }else{None};
        let layer = &mut self.state.layers[stroke.layer];
        let lock = layer.alpha_lock;
        let color = brush.color;
        let carried_color=stroke.loaded_paint.map_or(color,|load|brush_color::loaded_color(load,color));
        let smudging=brush.kind==6 || brush.kind>=128;
        let shape=brush_shape(brush.kind);
        if smudging && stroke.smudge.is_none() {
            let mut tip=vec![0;32*32];
            for gy in 0..32 {for gx in 0..32 {
                let sx=(p.x+((gx as f32+0.5)/16.0-1.0)*radius).floor() as i32;
                let sy=(p.y+((gy as f32+0.5)/16.0-1.0)*radius).floor() as i32;
                tip[gy*32+gx]=layer.pixel_at(sx,sy,self.width,self.height);
            }}
            stroke.smudge=Some(tip);return;
        }
        // Pick up the destination before writing this dab, preserving direction and color variation.
        let pickup=if smudging {let mut tip=vec![0;1024];for gy in 0..32 {for gx in 0..32 {
            let sx=(p.x+((gx as f32+0.5)/16.0-1.0)*radius).floor() as i32;
            let sy=(p.y+((gy as f32+0.5)/16.0-1.0)*radius).floor() as i32;
            tip[gy*32+gx]=layer.pixel_at(sx,sy,self.width,self.height);
        }}Some(tip)}else{None};
        let mask_edit=stroke.mask_before.is_some();
        let mask=if mask_edit{Some(Arc::make_mut(layer.mask.as_mut().unwrap()))}else{None};
        let mut mask=mask;
        let mut pixels:Option<&mut tiles::Tiles>=if mask_edit{None}else{Some(&mut layer.pixels)};
        let strength = brush.opacity * brush.flow * (1.0 - brush.pressure_opacity + brush.pressure_opacity * pressure);
        let deposition=stroke_path::deposition(brush,p);
        let margin=2048i32;
        let bx0=(p.x-radius-1.0).floor().max(-margin as f32) as i32;let by0=(p.y-radius-1.0).floor().max(-margin as f32) as i32;
        let bx1=(p.x+radius+1.0).ceil().min(self.width as f32+margin as f32) as i32;let by1=(p.y+radius+1.0).ceil().min(self.height as f32+margin as f32) as i32;
        let paint=dab_paint::DabPaint{p,radius,brush,color,stroke_color:stroke.brush.color,shape,strands,direction:stroke.direction,seed:stroke.seed,effects:&stroke.effects,stamp_distance:stroke.stamp_distance,carried_color,lock,strength,deposition,selected:selected.as_deref(),selection_alpha:selection_alpha.as_deref(),contact:contact.as_ref()};
        let threads=std::thread::available_parallelism().map_or(1,|n|n.get()).min(8);
        let area=((bx1-bx0).max(0)*(by1-by0).max(0)) as usize;
        let on_canvas=bx0>=0 && by0>=0 && bx1<=self.width as i32 && by1<=self.height as i32;
        let parallel=!smudging && !mask_edit && on_canvas && threads>1 && area>=dab_paint::PARALLEL_MIN_PIXELS.load(std::sync::atomic::Ordering::Relaxed);
        if parallel {
            // Bands own whole tile rows and every tile is made unique first, so each thread writes
            // only its own tiles. Pixels are independent, so the result matches the serial loop.
            let w=self.width;let h=self.height;
            let target=pixels.take().unwrap();
            target.prepare_write([bx0 as usize,by0 as usize,bx1 as usize,by1 as usize]);
            let tile_rows=h.div_ceil(tiles::TILE);let band_tile_rows=tile_rows.div_ceil(threads.min(tile_rows));
            let (columns,bands)=target.tile_rows_mut(band_tile_rows);
            std::thread::scope(|scope|{
                for (first_tile_row,slots) in bands{
                    let paint=&paint;
                    scope.spawn(move||{
                        for (k,row_slots) in slots.chunks_mut(columns).enumerate(){
                            let ty=first_tile_row+k;let (y0,y1)=(ty*tiles::TILE,(ty+1)*tiles::TILE);
                            for (tx,slot) in row_slots.iter_mut().enumerate(){
                                let (x0,x1)=(tx*tiles::TILE,(tx+1)*tiles::TILE);
                                // Tiles outside the dab may still be shared with undo; leave them alone.
                                if x1<=bx0 as usize || x0>=bx1 as usize || y1<=by0 as usize || y0>=by1 as usize {continue;}
                                let Some(arc)=slot else {continue};
                                let tile=Arc::get_mut(arc).expect("prepared tile is unique");
                                for y in y0.max(by0 as usize)..y1.min(by1 as usize){
                                    for x in x0.max(bx0 as usize)..x1.min(bx1 as usize){
                                        let cell=&mut tile[(y-y0)*tiles::TILE+(x-x0)];
                                        if let Some(v)=paint.pixel(x as i32,y as i32,y*w+x,*cell){*cell=v;}
                                    }
                                }
                            }
                        }
                    });
                }
            });
        } else {
        for y in by0..by1 { for x in bx0..bx1 {
            let inside=x>=0 && y>=0 && x<self.width as i32 && y<self.height as i32;
            if !inside && (mask_edit || selected.is_some() || lock || layer.clipping){continue;}
            let idx=if inside{y as usize*self.width+x as usize}else{0};
            let under=pixels.as_ref().map_or(0,|values|values[idx]);
            let Some(shade)=paint.shade(x,y,idx,inside,under) else {continue};
            let alpha=shade.alpha;let color=shade.color;
            if let Some(mask)=mask.as_mut(){
                let target=if brush.kind==5 || (16..=31).contains(&brush.kind) || (64..=127).contains(&brush.kind){0.0}else{(channel(color,16)*0.2126+channel(color,8)*0.7152+channel(color,0)*0.0722)*255.0};
                mask[idx]=(mask[idx] as f32*(1.0-alpha)+target*alpha+0.5) as u8;continue;
            }
            let cell=if inside{&mut pixels.as_mut().unwrap()[idx]}else{
                let key=exterior::key(x.div_euclid(128),y.div_euclid(128));
                if !layer.exterior.contains_key(&key) && (alpha<=0.0 || smudging || brush.kind>=64){continue;}
                if !layer.exterior.contains_key(&key) && (layer.exterior.len()>=256 || exterior_count>=1024){self.input_error=Some("Off-canvas storage is full on this layer".into());continue;}
                stroke.exterior_changed=true;self.exterior_changed_tiles.insert(key);
                if !layer.exterior.contains_key(&key){exterior_count+=1;}
                let tile=layer.exterior.entry(key).or_insert_with(||Arc::new(vec![0;128*128]));
                &mut Arc::make_mut(tile)[y.rem_euclid(128) as usize*128+x.rem_euclid(128) as usize]
            };
            let dst = *cell;
            if smudging {
                let carried=sample_tip(stroke.smudge.as_ref().unwrap(),(shade.dx/radius+1.0)*16.0-0.5,(shade.dy/radius+1.0)*16.0-0.5);
                let mask=match shape {
                    2 | 4 => ((1.0-shade.d*shade.d).max(0.0)).powf(2.0),
                    8 | 9 => {let hard=if shape==8{0.35}else{0.7};let t=((1.0-shade.d)/(1.0-hard)).clamp(0.0,1.0);t*t*(3.0-2.0*t)},
                    7 | 10 | 11 | 12 | 13 | 14 | 32..=42 => shade.texture.min(1.0)*shade.edge,
                    _=>shade.edge,
                };
                let mixed=mix_rgba(dst,carried,(strength*mask*0.32*selection_alpha.as_ref().map_or(1.0,|a|a[idx] as f32/255.0)).clamp(0.0,1.0));
                *cell=if lock {(mixed&0xffffff)|(dst&0xff000000)}else{mixed};
            } else if let Some(v)=paint.blend(&shade,dst){
                *cell=v;
            }
        }}
        }
        if let (Some(tip),Some(pickup))=(&mut stroke.smudge,pickup){
            for (carried,local) in tip.iter_mut().zip(pickup){*carried=mix_rgba(*carried,local,0.08);}
        }
        stroke.seed = stroke.seed.wrapping_add(1);
        if x0 < x1 && y0 < y1 {
            stroke.rect[0]=stroke.rect[0].min(x0);stroke.rect[1]=stroke.rect[1].min(y0);
            stroke.rect[2]=stroke.rect[2].max(x1);stroke.rect[3]=stroke.rect[3].max(y1);
            let r = self.damage.get_or_insert([x0, y0, x1, y1]);
            r[0] = r[0].min(x0); r[1] = r[1].min(y0);
            r[2] = r[2].max(x1); r[3] = r[3].max(y1);
        }
    }

    pub fn set_renderer(&mut self, mode:&str)->Result<(),String> {
        if !matches!(mode,"auto"|"cpu") {return Err("Renderer must be auto or cpu".into());}
        self.end();self.renderer_requested=mode.into();self.renderer_error=None;
        if mode=="cpu" {self.gpu=None;}
        else if self.gpu.is_none() {
            match gpu::Gpu::new() {Ok(gpu)=>self.gpu=Some(gpu),Err(e)=>self.renderer_error=Some(e)}
        }
        self.dirty=true;Ok(())
    }
    pub fn flush_gpu(&mut self) {
        if let Some(mut stroke)=self.stroke.take() {self.flush_stroke(&mut stroke);self.stroke=Some(stroke);}
    }
    fn flush_stroke(&mut self,stroke:&mut Stroke) {
        if stroke.pending.is_empty(){return;}
        let pending=std::mem::take(&mut stroke.pending);
        let mut rect=[self.width,self.height,0,0];
        for dab in &pending {let r=dab.params.rect; if r[0]<r[2] && r[1]<r[3] {rect[0]=rect[0].min(r[0] as usize);rect[1]=rect[1].min(r[1] as usize);rect[2]=rect[2].max(r[2] as usize);rect[3]=rect[3].max(r[3] as usize);}}
        // An off-canvas smudge still picks up transparency and updates its carried tip.
        let dispatch_rect=if rect[0]>=rect[2] || rect[1]>=rect[3] {[0,0,1,1]}else{rect};
        let smudging=stroke.brush.kind==6 || stroke.brush.kind>=128;
        let result=self.gpu.as_mut().unwrap().paint(&mut self.state.layers[stroke.layer],&pending,dispatch_rect,smudging);
        match result {
            Ok(tip)=>{
                if smudging {stroke.smudge=tip;}
                if rect[0]<rect[2] && rect[1]<rect[3] {
                    for i in 0..2 {stroke.rect[i]=stroke.rect[i].min(rect[i]);stroke.rect[i+2]=stroke.rect[i+2].max(rect[i+2]);}
                    let damage=self.damage.get_or_insert(rect);
                    for i in 0..2 {damage[i]=damage[i].min(rect[i]);damage[i+2]=damage[i+2].max(rect[i+2]);}
                }
            },
            Err(e)=>{
                // The CPU mirror changes only after a successful GPU readback, so replay this batch exactly once.
                self.gpu=None;self.renderer_error=Some(e);stroke.seed=pending[0].params.flags[1];
                if pending[0].params.flags[3]!=0 {stroke.smudge=None;}
                let distance=stroke.stamp_distance;
                let brush=stroke.brush;
                let direction=stroke.direction;
                for dab in pending {stroke.direction=[dab.params.direction[0],dab.params.direction[1]];stroke.brush=dab.brush;stroke.stamp_distance=dab.distance;self.stamp_one(dab.sample,stroke);}
                stroke.direction=direction;stroke.brush=brush;
                stroke.stamp_distance=distance;
            }
        }
    }
    pub fn end(&mut self) {
        self.flush_gpu();
        if let Some(mut s) = self.stroke.take() {
            if s.input_time.is_some() && s.moving {
                let p=Sample{x:s.input_position.0,y:s.input_position.1,..s.last};
                for point in stroke_path::curve(s.last,p,s.path_delta){self.stroke_segment(point,&mut s);}
                self.flush_stroke(&mut s);
            }
            if s.records.is_empty(){self.stamp(s.last,&mut s);self.flush_stroke(&mut s);}
            if s.distance>0.01 && s.records.iter().any(|r|r.brush.fade_length>0.0) {
                if let Some(mask)=&s.mask_before{self.state.layers[s.layer].mask=Some(mask.clone());}
                else{self.state.layers[s.layer].pixels=s.before.clone();}
                self.state.layers[s.layer].exterior=s.exterior_before.layers[s.layer].exterior.clone();
                if s.exterior_changed{self.exterior_dirty=true;}
                s.replay_end=Some(s.distance);s.seed=0x736561;s.smudge=None;s.loaded_paint=None;s.mix_distance=0.0;
                let records=std::mem::take(&mut s.records);
                for record in records {
                    if s.brush.kind!=record.brush.kind{self.flush_stroke(&mut s);s.smudge=None;}
                    s.direction=record.direction;s.brush=record.brush;s.stamp_distance=record.distance;self.stamp(record.sample,&mut s);
                }
                self.flush_stroke(&mut s);
                if s.rect[0]<s.rect[2] && s.rect[1]<s.rect[3]{let damage=self.damage.get_or_insert(s.rect);for i in 0..2{damage[i]=damage[i].min(s.rect[i]);damage[i+2]=damage[i+2].max(s.rect[i+2]);}}
            }
            self.action_name=if s.brush.kind>=128 || s.brush.kind==6{"Smudge stroke"}else if s.brush.kind>=64{"Erase stroke"}else{"Brush stroke"}.into();
            // Recording undo changes no pixels; keep the pending stroke damage intact.
            let was_dirty = self.dirty;
            if let Some(before)=s.mask_before{
                self.record_mask(s.layer,&before,s.rect);
            }else if s.exterior_changed {
                let mut before=s.exterior_before;before.layers[s.layer].pixels=s.before.clone();
                self.record(Change::Structure{before,after:self.state.clone()});
            }else{self.pixel_change_stroke(s.layer,&s.before,s.rect);}
            self.dirty = was_dirty;
        }
    }
    pub fn cancel(&mut self) {
        if let Some(s) = self.stroke.take() { if let Some(mask)=s.mask_before{self.state.layers[s.layer].mask=Some(mask);}else{self.state.layers[s.layer].pixels=s.before;self.state.layers[s.layer].exterior=s.exterior_before.layers[s.layer].exterior.clone();} self.dirty = true;self.exterior_dirty=true; }
    }

    fn compositing_layers(&self)->Vec<Layer>{
        if !self.state.groups.is_empty() && self.state.layers.iter().any(|l|l.clipping){return self.state.layers.clone();}
        let mut layers=Vec::new();let mut empty_base=false;
        for l in &self.state.layers {
            if l.adjustment.is_none() && (l.pixels).is_blank(){if !l.clipping{empty_base=true;}continue;}
            if l.clipping && empty_base{continue;}
            if !l.clipping{empty_base=false;}
            layers.push(l.clone());
        }layers
    }
    pub fn color_at(&self, x: usize, y: usize, background: bool) -> u32 {
        self.color_at_layers(x,y,background,&self.compositing_layers())
    }
    fn color_at_layers(&self,x:usize,y:usize,background:bool,layers:&[Layer])->u32 {
        let idx = y.min(self.height - 1) * self.width + x.min(self.width - 1);
        let c=if background && self.state.background_visible{self.state.background}else{0};
        self.composite_grouped(layers,c,|l|(l.pixels[idx],l.mask_coverage(idx)))
    }

    pub fn render(&mut self, bytes: &mut [u8], background: bool) -> bool {
        self.flush_gpu();
        if bytes.len() < self.width * self.height * 4 { return false; }
        let layers=self.compositing_layers();
        let plan=groups::PreparedComposite::new(&self.state.groups,&layers);
        let bg=if background && self.state.background_visible{self.state.background}else{0};
        for y in 0..self.height { for x in 0..self.width {
            let idx=y*self.width+x;
            let c=plan.color(bg,|l|(l.pixels[idx],l.mask_coverage(idx)));
            let i = (y * self.width + x) * 4;
            let a = (c >> 24) & 255;
            // Android's RGBA_8888 bitmap buffer stores premultiplied channels.
            bytes[i] = (((c >> 16) & 255) * a / 255) as u8;
            bytes[i + 1] = (((c >> 8) & 255) * a / 255) as u8;
            bytes[i + 2] = ((c & 255) * a / 255) as u8;
            bytes[i + 3] = a as u8;
        }}
        true
    }

    /// Pixels the next `render_patch(force)` will return, so hosts can size a reused buffer.
    /// Call `flush_gpu` first; `render_patch` flushes pending GPU dabs before deciding its rect.
    pub fn patch_pixels(&self, force: bool) -> usize {
        let rect = if force || self.dirty { Some([0, 0, self.width, self.height]) } else { self.damage };
        rect.map_or(0, |[x0, y0, x1, y1]| (x1 - x0) * (y1 - y0))
    }

    pub fn render_patch(&mut self, force: bool) -> Option<Vec<i32>> {
        self.flush_gpu();
        let rect = if force || self.dirty { [0, 0, self.width, self.height] }
            else { self.damage? };
        let [x0, y0, x1, y1] = rect;
        let mut patch = Vec::with_capacity(4 + (x1 - x0) * (y1 - y0));
        patch.extend([x0 as i32, y0 as i32, (x1 - x0) as i32, (y1 - y0) as i32]);
        let layers=self.compositing_layers();
        let active_id=self.state.layers[self.state.active].id;
        let group_clipping=!groups::clipping_groups(&self.state.groups,&layers).is_empty();
        let cache_at=layers.iter().position(|l|l.id==active_id).filter(|i|!layers.iter().any(|l|l.adjustment.is_some()) && *i>=2 && !layers[*i].clipping && !group_clipping
            && !self.state.groups.iter().any(|g|g.blend!="Pass Through" || g.opacity<1.0)
            && (x1-x0)*(y1-y0)<=2_097_152);
        let prefer_cache=cache_at.is_some_and(|i|layers.len()-i<=4 && (x1-x0)*(y1-y0)<=262144);
        // Cached backdrops avoid a second GPU round trip after painting the active layer.
        let gpu_pixels=if !layers.iter().any(|l|l.adjustment.is_some()) && !group_clipping && !prefer_cache && !layers.is_empty() && !self.state.groups.iter().any(|g|g.blend!="Pass Through" || g.opacity<1.0) && !self.state.layers.iter().any(|l|l.mask.is_some() && l.mask_enabled) && (force || (x1-x0)*(y1-y0)*layers.len()>=262144) {
            self.gpu.as_mut().map(|gpu|gpu.composite(&layers,self.width,rect,if self.state.background_visible {self.state.background}else{0}))
        }else{None};
        match gpu_pixels {
            Some(Ok(pixels))=>patch.extend(pixels.into_iter().map(|c|c as i32)),
            result=>{
                if let Some(Err(e))=result {self.gpu=None;self.renderer_error=Some(e);}
                let bg=if self.state.background_visible{self.state.background}else{0};
                if let Some(i)=cache_at{
                    patch.extend(self.composite_cache.render(self.width,self.height,rect,bg,&layers[..i],&layers[i..]).into_iter().map(|c|c as i32));
                }else{
                    let plan=groups::PreparedComposite::new(&self.state.groups,&layers);
                    for y in y0..y1 {for x in x0..x1 {let idx=y*self.width+x;patch.push(plan.color(bg,|l|(l.pixels[idx],l.mask_coverage(idx))) as i32);}}
                }
            }
        }
        self.dirty = false; self.damage = None;
        Some(patch)
    }

    pub fn info(&self) -> Value {
        json!({"renderer":if self.gpu.is_some(){"gpu"}else{"cpu"},"rendererRequested":self.renderer_requested,
            "rendererDevice":self.gpu.as_ref().map(|g|g.description.as_str()),"rendererError":self.renderer_error,
            "gpuComposites":self.gpu.as_ref().map_or(0,|g|g.composites),
            "gpuBatches":self.gpu.as_ref().map_or(0,|g|g.batches),"gpuDabs":self.gpu.as_ref().map_or(0,|g|g.dabs),
            "width":self.width,"height":self.height,"active":self.state.active,"maskEditing":self.mask_edit && self.state.layers[self.state.active].mask.is_some(),"symmetry":self.symmetry,
            "historyNotice":self.history_notice,"undoAction":self.undo_names.last(),"redoAction":self.redo_names.last(),"inputError":self.input_error,"dpi":self.dpi,"undo":self.undo.len(),"redo":self.redo.len(),"background":self.state.background,
            "backgroundVisible":self.state.background_visible,"selection":self.selection.is_some(),"selectionVersion":self.selection_version,"savedSelections":self.saved_selections.keys().collect::<Vec<_>>(),"clipboard":self.clipboard.is_some(),
            "groups":self.state.groups,"layers":self.state.layers.iter().map(|l| json!({"id":l.id,"name":l.name,
                "groupId":l.group_id,"ownVisible":l.visible,"ownLocked":l.locked,"visible":l.is_visible(),"opacity":l.opacity,"blend":l.blend,
                "adjustment":l.adjustment,"locked":l.is_locked(),"alphaLock":l.alpha_lock,"clipping":l.clipping,"hasMask":l.mask.is_some(),"maskEnabled":l.mask_enabled})).collect::<Vec<_>>()})
    }

    fn layer_bytes(layers:&[Layer])->usize {
        let mut seen=std::collections::HashSet::new();
        layers.iter().map(|l|{
            let pixels=if seen.insert(l.pixels.generation() as usize){l.pixels.allocated_bytes()}else{0};
            pixels+l.exterior.values().map(|t|if seen.insert(Arc::as_ptr(t) as usize){t.len()*4}else{0}).sum::<usize>()+l.mask.as_ref().map_or(0,|m|if seen.insert(Arc::as_ptr(m) as usize){m.len()}else{0})
        }).sum()
    }
    fn ensure_edit_capacity(&self)->Result<(),String>{
        let active=&self.state.layers[self.state.active];
        let shared=self.state.layers.iter().enumerate().any(|(i,l)|i!=self.state.active && l.pixels.generation()==active.pixels.generation());
        if shared && Self::layer_bytes(&self.state.layers)+self.width*self.height*4>PIXEL_BUDGET*4{return Err("Canvas memory is full. Merge painted layers or use a smaller canvas to continue drawing.".into());}
        Ok(())
    }
    fn can_add(&self) -> bool { self.state.layers.len()<MAX_LAYERS && Self::layer_bytes(&self.state.layers)<=PIXEL_BUDGET*4 }


    pub fn command(&mut self, v: &Value) -> Result<Value, String> {
        let op = v["op"].as_str().ok_or("Missing operation")?;
        if op=="exteriorPreview"{return Ok(self.exterior_preview());}
        if !matches!(op,"info"|"pick"|"selectionPreview"|"brushEffects"){self.exterior_dirty=true;}
        if !op.starts_with("liquify") && !matches!(op,"info"|"pick"|"selectionPreview"|"exteriorPreview"){self.finish_liquify(false);}
        if !matches!(op,"info"|"pick"|"selectionPreview"|"exteriorPreview"){self.end();}else if op=="pick"{self.flush_gpu();}
        if self.flood_preview.is_some() && !matches!(op,"adjustPreview"|"adjustCommit"|"floodPreview"|"floodCommit"|"floodCancel"|"info"|"pick"|"selectionPreview"|"exteriorPreview") {
            self.cancel_flood();
        }
        let index = v["index"].as_u64().unwrap_or(self.state.active as u64) as usize;
        let n = |key: &str, default: f32| finite(v[key].as_f64().unwrap_or(default as f64) as f32, default);
        if op=="delete"{if index>=self.state.layers.len(){return Err("Layer no longer exists".into());}if self.state.layers[index].is_locked(){return Err("This layer is locked".into());}}
        if self.state.layers[self.state.active].is_locked() && matches!(op,"clear"|"fill"|"flood"|"floodPreview"|"adjust"|"adjustPreview"|"transform"|"transformMatrix"|"transformProjective"|"transformMesh"|"selectionClear"|"selectionFill"|"selectionCut"|"merge") {
            return Err("This layer is locked. Swipe left and choose Unlock to edit it.".into());
        }
        if op=="merge" && self.state.active>0 && self.state.layers[self.state.active-1].is_locked() {return Err("The layer below is locked".into());}
        if self.mask_edit && self.state.layers[self.state.active].mask.is_some() && matches!(op,"flood"|"floodPreview"|"adjust"|"adjustPreview"|"transform"|"transformMatrix"|"transformProjective"|"transformMesh"|"selectionClear"|"selectionFill"|"selectionCut"){return Err("Select Layer pixels before using fill, adjustments or Transform".into());}
        if matches!(op,"fill"|"flood"|"floodPreview"|"adjust"|"adjustPreview"|"transform"|"transformMatrix"|"transformProjective"|"transformMesh"){self.ensure_edit_capacity()?;}
        self.action_name=match op{
            "add"=>"Create layer","duplicate"=>"Duplicate layer","delete"=>"Delete layer","merge"=>"Merge layers","layer"=>"Change layer settings","reorder"|"moveLayer"=>"Move layer",
            "flood"|"floodCommit"=>"ColorDrop","fill"=>"Fill layer","clear"=>"Clear layer","background"=>"Change background","adjust"|"adjustCommit"=>"Image adjustment",
            "transform"|"transformMatrix"|"transformProjective"|"transformMesh"=>"Transform","mask"=>"Edit layer mask","group"=>if v["action"]=="move"{"Move layer group"}else{"Edit layer group"},
            "selectionFill"=>"Fill selection","selectionClear"=>"Clear selection","selectionCut"=>"Cut selection","selectionPaste"|"selectionCopyPaste"=>"Paste selection","liquifyCommit"=>"Liquify",_=>"Edit artwork"
        }.into();
        if op.starts_with("liquify"){self.liquify_command(v)?;return Ok(self.info());}
        if op.starts_with("selection"){return self.selection_command(v);}
        if op=="group"{return self.group_command(v);}
        match op {
            "setup" => {
                if !self.undo.is_empty() || self.state.layers.len()!=1 {return Err("Canvas setup is only available on a new canvas".into());}
                self.dpi=v["dpi"].as_u64().unwrap_or(300).clamp(1,2400) as u32;
                if let Some(c)=v["color"].as_u64(){self.state.background=c as u32|0xff000000;}
                if let Some(b)=v["visible"].as_bool(){self.state.background_visible=b;}
                self.dirty=true;
            },
            "brushEffects"=>{self.brush_color=brush_color::BrushColor::parse(v)?;},
            "renderer" => {self.set_renderer(v["mode"].as_str().unwrap_or("auto"))?;},
            "info" => {},
            "historyDirectory" => {let path=v["path"].as_str().ok_or("Missing history directory")?;std::fs::create_dir_all(path).map_err(|e|e.to_string())?;self.history_directory=path.into();},
            "undo" => { self.undo();if let Some(error)=self.history_error.take(){return Err(error);} }, "redo" => { self.redo();if let Some(error)=self.history_error.take(){return Err(error);} },
            "active" => { if index < self.state.layers.len() {if index!=self.state.active{self.mask_edit=false;} self.state.active = index; } },
            "nameLastUndo"=>{if let Some(name)=self.undo_names.last_mut(){*name=v["name"].as_str().unwrap_or("Shape").chars().take(100).collect();}},
            "moveLayer"=>self.move_layer(v)?,
            "mask"=>self.mask_command(v)?,
            "symmetry"=>{let mode=v["mode"].as_str().unwrap_or("Off");if !matches!(mode,"Off"|"Vertical"|"Horizontal"|"Quadrant"|"Radial 4"){return Err("Unsupported symmetry mode".into());}self.symmetry=mode.into();},
            "add" | "duplicate" => {
                if self.state.layers.len()>=MAX_LAYERS{return Err("Layer metadata limit reached".into());}
                if op=="duplicate" && index>=self.state.layers.len(){return Err("Layer no longer exists".into());}
                if op=="duplicate" && self.state.layers.iter().map(|l|l.exterior.len()).sum::<usize>()+self.state.layers[index].exterior.len()>1024{return Err("Duplicate exceeds off-canvas storage capacity".into());}
                if op=="add" && Self::layer_bytes(&self.state.layers)>PIXEL_BUDGET*4{return Err("Canvas memory budget is full".into());}
                if self.next_id==u32::MAX {return Err("Layer identifiers exhausted".into());}
                let previous_dirty=self.dirty;
                self.mask_edit=false;
                self.structure(|e| {
                    let id = e.next_id; e.next_id += 1;
                    let source=if op=="duplicate"{index}else{e.state.active};
                    let l = if op == "duplicate" {
                        let mut l = e.state.layers[source].clone(); l.id = id; l.name += " copy"; l
                    } else { Layer { adjustment:None,exterior:Default::default(),group_id:e.state.layers[e.state.active].group_id,group_visible:e.state.layers[e.state.active].group_visible,group_locked:e.state.layers[e.state.active].group_locked,id, name: format!("Layer {}", id), visible:true, opacity:1.0,
                        blend:"Normal".into(), alpha_lock:false, locked:false, clipping:false,
                        mask:None,mask_enabled:true,pixels:Tiles::blank(e.width,e.height) } };
                    e.state.active = source+1; e.state.layers.insert(e.state.active, l);
                });
                if op=="add"{self.dirty=previous_dirty;}
            },
            "delete" => {
                if self.state.layers.len() <= 1 { return Err("Keep at least one layer; use Clear instead".into()); }
                self.mask_edit=false;
                self.structure(|e| {
                    let active=e.state.layers[e.state.active].id;let deleted=e.state.layers[index].id;let next=e.state.layers.get(index+1).map(|l|l.id);
                    for g in &mut e.state.groups{if g.anchor_id==Some(deleted){g.anchor_id=next;}}
                    e.state.layers.remove(index);e.state.active=e.state.layers.iter().position(|l|l.id==active).unwrap_or(index.saturating_sub(1).min(e.state.layers.len()-1));e.state.layers[0].clipping=false;e.normalize_groups();
                });
            },
            "layer" => {
                if index >= self.state.layers.len() { return Err("Layer no longer exists".into()); }
                // Dragging an adjustment-layer slider sends many updates; keep them as one undo step.
                let slider=v.as_object().is_some_and(|o|o.keys().all(|k|matches!(k.as_str(),"op"|"index"|"adjustmentKind"|"amount"))) && v["adjustmentKind"].is_string();
                let id=self.state.layers[index].id;
                if slider && self.adjust_coalesce==Some(id) && self.state.layers[index].adjustment.is_some() && matches!(self.undo.last(),Some(Change::Structure{..})) {
                    let kind=v["adjustmentKind"].as_str().unwrap_or("");
                    if matches!(kind,"Brightness"|"Saturation"|"Invert"|"Grayscale"){self.state.layers[index].adjustment=Some((kind.into(),n("amount",0.0).clamp(-1.0,1.0)));}
                    let state=self.state.clone();
                    if let Some(Change::Structure{after,..})=self.undo.last_mut(){*after=state;}
                    self.dirty=true;
                    return Ok(self.info());
                }
                self.structure(|e| {
                    let l = &mut e.state.layers[index];
                    if let Some(kind)=v["adjustmentKind"].as_str(){if matches!(kind,"Brightness"|"Saturation"|"Invert"|"Grayscale"){l.adjustment=Some((kind.into(),n("amount",0.0).clamp(-1.0,1.0)));}}
                    if let Some(s) = v["name"].as_str() { l.name = s.chars().take(100).collect(); }
                    if let Some(b) = v["visible"].as_bool() { l.visible = b; }
                    if let Some(b) = v["locked"].as_bool(){l.locked=b;}
                    if let Some(b) = v["alphaLock"].as_bool() { l.alpha_lock = b; }
                    if let Some(b) = v["clipping"].as_bool() { l.clipping = b && index > 0; }
                    if v["opacity"].is_number() { l.opacity = n("opacity", 1.0).clamp(0.0, 1.0); }
                    if let Some(s) = v["blend"].as_str() { l.blend = s.to_string(); }
                });
                if slider {self.adjust_coalesce=Some(id);}
            },
            "reorder" => {
                let to = v["to"].as_u64().unwrap_or(index as u64) as usize;
                if index < self.state.layers.len() && to < self.state.layers.len() {
                    if self.state.layers[index].group_id!=self.state.layers[to].group_id{return Err("Move layers between folders with Layer group".into());}
                    self.structure(|e| { let l = e.state.layers.remove(index); e.state.layers.insert(to, l); e.state.active = to;
                        e.state.layers[0].clipping = false; });
                }
            },
            "background" => self.structure(|e| {
                if let Some(c) = v["color"].as_u64() { e.state.background = c as u32 | 0xff000000; }
                if let Some(b) = v["visible"].as_bool() { e.state.background_visible = b; }
            }),
            "flipCanvas" => {
                let horizontal=v["horizontal"].as_bool().unwrap_or(true);
                if Self::layer_bytes(&self.state.layers)*2>PIXEL_BUDGET*4{return Err("Not enough canvas memory to flip this artwork".into());}
                let mut cache=std::collections::HashMap::new();let mut layers=Vec::new();let mut memory=Self::layer_bytes(&self.state.layers);
                for l in &self.state.layers{
                    let key=(l.pixels.generation() as usize,l.mask.as_ref().map(|m|Arc::as_ptr(m) as usize),l.exterior.iter().map(|(k,v)|(*k,Arc::as_ptr(v) as usize)).collect::<Vec<_>>());
                    let geometry=if let Some(g)=cache.get(&key){Layer::clone(g)}else{
                        let extra=if l.adjustment.is_none() && (l.pixels).is_blank(){0}else{self.width*self.height*4}+l.mask.as_ref().map_or(0,|m|m.len())+(l.exterior.len()*4).min(256)*128*128*4;
                        if memory+extra>PIXEL_BUDGET*4{return Err("Not enough canvas memory to flip this artwork".into());}memory+=extra;
                        let mapped=l.remapped(self.width,self.height,|x,y|if horizontal{(self.width as i32-1-x,y)}else{(x,self.height as i32-1-y)})?;cache.insert(key,mapped.clone());mapped
                    };
                    let mut layer=l.clone();layer.pixels=geometry.pixels;layer.mask=geometry.mask;layer.exterior=geometry.exterior;layers.push(layer);
                }
                if layers.iter().map(|l|l.exterior.len()).sum::<usize>()>1024{return Err("Flip exceeds off-canvas storage capacity".into());}
                self.structure(|e|{e.state.layers=layers;e.set_selection(None);});
            },
            "clear" | "fill" if self.mask_edit && self.state.layers[self.state.active].mask.is_some()=>{
                let value=if op=="clear"{255}else{let c=v["color"].as_u64().unwrap_or(0xffffffff) as u32;((channel(c,16)*0.2126+channel(c,8)*0.7152+channel(c,0)*0.0722)*255.0+0.5) as u8};
                self.structure(|e|{let m=Arc::make_mut(e.state.layers[e.state.active].mask.as_mut().unwrap());for(i,p)in m.iter_mut().enumerate(){if e.selection.as_ref().is_none_or(|s|s[i]){*p=value;}}});
            },
            "clear" if self.selection.is_none() && !self.state.layers[self.state.active].exterior.is_empty()=>{
                self.structure(|e|{let l=&mut e.state.layers[e.state.active];l.exterior.clear();l.pixels=Tiles::blank(e.width,e.height);});
            },
            "clear" | "fill" => {
                let before = self.state.layers[self.state.active].pixels.clone();
                let l = &mut self.state.layers[self.state.active];
                let lock = l.alpha_lock; let mut pixels = l.pixels.to_vec();
                let color = v["color"].as_u64().unwrap_or(0xff202020) as u32;
                for (i, p) in pixels.iter_mut().enumerate() {
                    if self.selection.as_ref().is_some_and(|s| !s[i]) { continue; }
                    if op == "clear" { *p = 0; }
                    else if !lock { *p = color; }
                    else { *p = (color & 0x00ffffff) | (*p & 0xff000000); }
                }
                self.state.layers[self.state.active].pixels = Tiles::from_flat(&pixels,self.width,self.height);
                self.pixel_change(self.state.active, before);
            },
            "merge" => {
                let a = self.state.active;
                if a == 0 { return Err("There is no layer below this one".into()); }
                if self.state.layers[a].group_id!=self.state.layers[a-1].group_id{return Err("Move layers into the same group before merging".into());}
                let tiles=self.state.layers[a].exterior.keys().chain(self.state.layers[a-1].exterior.keys()).collect::<std::collections::HashSet<_>>().len();
                if tiles>256{return Err("Merged layer exceeds off-canvas storage capacity".into());}
                if self.state.layers[a].mask.is_some() || self.state.layers[a-1].mask.is_some(){return Err("Apply layer masks before merging".into());}
                if self.state.layers[a].clipping || self.state.layers[a - 1].clipping
                    || self.state.layers[a].blend != "Normal" || self.state.layers[a - 1].blend != "Normal"
                    || self.state.layers[a - 1].opacity != 1.0 {
                    return Err("Merge currently supports normal layers with an opaque lower layer setting".into());
                }
                self.structure(|e| {
                    let top = e.state.layers.remove(a);
                    let bottom = &mut e.state.layers[a - 1];
                    if !bottom.visible { bottom.pixels.fill_zero();bottom.exterior.clear(); }
                    if top.visible{for(key,tile)in &top.exterior{let dst=bottom.exterior.entry(*key).or_insert_with(||Arc::new(vec![0;128*128]));for(d,s)in Arc::make_mut(dst).iter_mut().zip(tile.iter()){*d=composite(*d,*s,top.opacity,"Normal");}}}
                    if top.visible { let mut flat=bottom.pixels.to_vec(); for (d, s) in flat.iter_mut().zip(top.pixels.iter()) {
                        *d = composite(*d, s, top.opacity, "Normal");
                    } bottom.pixels=Tiles::from_flat(&flat,e.width,e.height);}
                    bottom.visible = bottom.visible || top.visible; e.state.active = a - 1;
                });
            },
            "pick" => {
                let x = n("x", 0.0).max(0.0) as usize; let y = n("y", 0.0).max(0.0) as usize;
                return Ok(json!({"color":self.color_at(x, y, true)}));
            },
            "flood" => self.flood(n("x", 0.0) as i32, n("y", 0.0) as i32,
                v["color"].as_u64().unwrap_or(0xff202020) as u32, n("threshold", 0.12),true)?,
            "floodPreview" => {
                if self.flood_preview.is_none(){self.flood_preview=Some((self.state.active,self.state.layers[self.state.active].pixels.clone()));}
                let (layer,before)=self.flood_preview.as_ref().unwrap();
                self.state.layers[*layer].pixels=before.clone();
                self.flood(n("x",0.0) as i32,n("y",0.0) as i32,v["color"].as_u64().unwrap_or(0xff202020) as u32,n("threshold",0.12),false)?;
            },
            "floodCommit"|"adjustCommit" => {if let Some((layer,before))=self.flood_preview.take(){self.pixel_change_rect(layer,before,[0,0,self.width,self.height]);}},
            "floodCancel" => self.cancel_flood(),
            "deselect" => { self.set_selection(None); },
            "select" => {
                let points: Vec<(f32,f32)> = v["points"].as_array().ok_or("Missing selection points")?.iter()
                    .filter_map(|p| Some((p[0].as_f64()? as f32,p[1].as_f64()? as f32))).collect();
                if points.len() < 2 { return Err("Draw a selection on the canvas".into()); }
                let shape = v["shape"].as_str().unwrap_or("Rectangle");
                let (x0,y0) = points[0]; let (x1,y1) = *points.last().unwrap();
                let mut mask = vec![false; self.width*self.height];
                for y in 0..self.height { for x in 0..self.width {
                    let (px,py) = (x as f32+0.5,y as f32+0.5);
                    mask[y*self.width+x] = match shape {
                        "Freehand" => inside_polygon(px,py,&points),
                        "Ellipse" => {
                            let rx=(x1-x0).abs()*0.5; let ry=(y1-y0).abs()*0.5;
                            rx>0.0 && ry>0.0 && ((px-(x0+x1)*0.5)/rx).powi(2)+((py-(y0+y1)*0.5)/ry).powi(2)<=1.0
                        },
                        _ => px>=x0.min(x1) && px<=x0.max(x1) && py>=y0.min(y1) && py<=y0.max(y1),
                    };
                }}
                self.combine_selection(mask.into_iter().map(|v|if v{255}else{0}).collect(),v["mode"].as_str().unwrap_or("Replace"));
            },
            "invertSelection" => {let alpha=(0..self.width*self.height).map(|i|255-self.selection_coverage(i)).collect();self.set_selection(Some(alpha));},
            "adjust"|"adjustPreview" => {
                // One working copy per preview session: each tick rewrites it from the untouched base
                // instead of allocating two full-layer copies (the source of out-of-memory crashes).
                let preview=op=="adjustPreview";
                if preview && self.flood_preview.is_none(){self.flood_preview=Some((self.state.active,self.state.layers[self.state.active].pixels.clone()));}
                let base=if preview{self.flood_preview.as_ref().unwrap().1.clone()}else{self.state.layers[self.state.active].pixels.clone()};
                let before:Vec<u32>=base.to_vec();
                let kind=v["kind"].as_str().unwrap_or("Brightness"); let amount=n("amount",0.0);
                let mut working=before.clone();let pixels=&mut working;
                if kind == "Blur" {
                    let radius=(amount.abs()*12.0).ceil().clamp(1.0,16.0) as usize;
                    blur(pixels,self.width,self.height,radius);
                    if let Some(s)=&self.selection { for i in 0..pixels.len() { if !s[i] { pixels[i]=before[i]; } } }
                } else { for (i,p) in pixels.iter_mut().enumerate() {
                    if self.selection.as_ref().is_some_and(|s| !s[i]) { continue; }
                    let (r,g,b,a)=(channel(*p,16),channel(*p,8),channel(*p,0),channel(*p,24));
                    let gray=r*0.2126+g*0.7152+b*0.0722;
                    *p=match kind {
                        "Invert" => pack(1.0-r,1.0-g,1.0-b,a),
                        "Grayscale" => pack(gray,gray,gray,a),
                        "Saturation" => { let s=1.0+amount; pack(gray+(r-gray)*s,gray+(g-gray)*s,gray+(b-gray)*s,a) },
                        _ => pack(r+amount,g+amount,b+amount,a),
                    };
                }}
                if let Some(alpha)=&self.selection_alpha{for(i,p)in pixels.iter_mut().enumerate(){*p=mix_rgba(before[i],*p,alpha[i] as f32/255.);}}
                self.state.layers[self.state.active].pixels=Tiles::from_flat(&working,self.width,self.height);
                if preview{self.dirty=true;self.damage=None;}else{self.pixel_change_rect(self.state.active,before,[0,0,self.width,self.height]);}
            },
            "transformProjective"=>{let values=v["matrix"].as_array().filter(|a|a.len()==9).ok_or("Expected nine transform values")?;let mut m=[0.;9];for(i,n)in values.iter().enumerate(){m[i]=n.as_f64().ok_or("Invalid transform")? as f32;}self.transform_projective(m,v["filter"].as_str().unwrap_or("Bilinear"))?;},
            "transformMesh"=>{let points=v["points"].as_array().ok_or("Missing warp points")?.iter().map(|p|Ok((p[0].as_f64().ok_or("Invalid warp X")? as f32,p[1].as_f64().ok_or("Invalid warp Y")? as f32))).collect::<Result<Vec<_>,String>>()?;self.transform_mesh(v["columns"].as_u64().unwrap_or(3) as usize,v["rows"].as_u64().unwrap_or(3) as usize,&points,v["filter"].as_str().unwrap_or("Bilinear"))?;},
            "transformMatrix" => {
                let values=v["matrix"].as_array().filter(|a|a.len()==6).ok_or("Expected six affine values")?;
                let mut m=[0.0;6];for (i,value) in values.iter().enumerate(){m[i]=value.as_f64().ok_or("Invalid affine value")? as f32;}
                self.transform_affine(m)?;
            },
            "transform" => {if self.state.layers[self.state.active].mask.is_some() || !self.state.layers[self.state.active].exterior.is_empty(){let b=self.transform_extent(true,self.state.active);let x=(b[0]+b[2]) as f32/2.0;let y=(b[1]+b[3]) as f32/2.0;let(sn,cs)=n("angle",0.0).sin_cos();let a=cs*n("sx",1.0);let c=-sn*n("sy",1.0);let bb=sn*n("sx",1.0);let d=cs*n("sy",1.0);self.transform_affine([a,bb,c,d,x+n("dx",0.0)-a*x-c*y,y+n("dy",0.0)-bb*x-d*y])?;}else{self.transform(n("dx",0.0),n("dy",0.0),n("sx",1.0),n("sy",1.0),n("angle",0.0));}},
            _ => return Err(format!("Unknown operation: {}",op)),
        }
        Ok(self.info())
    }

    fn transform(&mut self, dx:f32, dy:f32, sx:f32, sy:f32, angle:f32) {
        if sx.abs()<0.01 || sy.abs()<0.01 { return; }
        let before=self.state.layers[self.state.active].pixels.clone();
        let mut out=before.clone();
        let sel=&self.selection;
        for i in 0..out.len() { if sel.as_ref().is_none_or(|s|s[i]) { out[i]=0; } }
        let mut bounds=[self.width,self.height,0,0];
        for y in 0..self.height {for x in 0..self.width {let i=y*self.width+x;
            if before[i]>>24!=0 && sel.as_ref().is_none_or(|s|s[i]) {bounds[0]=bounds[0].min(x);bounds[1]=bounds[1].min(y);bounds[2]=bounds[2].max(x+1);bounds[3]=bounds[3].max(y+1);}
        }}
        if bounds[2]==0 {return;}
        let (cx,cy)=((bounds[0]+bounds[2]) as f32*0.5,(bounds[1]+bounds[3]) as f32*0.5);
        let (sn,cs)=angle.sin_cos();
        for y in 0..self.height { for x in 0..self.width {
            let (px,py)=(x as f32-cx-dx,y as f32-cy-dy);
            let (ox,oy)=((cs*px+sn*py)/sx+cx,(-sn*px+cs*py)/sy+cy);
            if ox>=0.0 && oy>=0.0 && ox<self.width as f32 && oy<self.height as f32 {
                let src=oy as usize*self.width+ox as usize;
                if sel.as_ref().is_none_or(|s|s[src]) { let i=y*self.width+x; out[i]=composite(out[i],before[src],1.0,"Normal"); }
            }
        }}
        self.state.layers[self.state.active].pixels=out;
        self.set_selection(None);
        self.pixel_change(self.state.active,before);
    }

    fn cancel_flood(&mut self){if let Some((layer,before))=self.flood_preview.take(){self.state.layers[layer].pixels=before;self.dirty=true;}}
    fn flood(&mut self,x:i32,y:i32,color:u32,threshold:f32,record:bool)->Result<(),String> {
        if x<0 || y<0 || x>=self.width as i32 || y>=self.height as i32 { return Ok(()); }
        let start=y as usize*self.width+x as usize;
        let before=self.state.layers[self.state.active].pixels.clone();
        let target=before[start];
        let distance=|a:u32,b:u32| -> f32 {
            let aa=channel(a,24);let ba=channel(b,24);
            [16,8,0].iter().map(|s|(channel(a,*s)*aa-channel(b,*s)*ba).abs()).fold((aa-ba).abs(),f32::max)
        };
        let selection_alpha=&self.selection_alpha;
        let l=&mut self.state.layers[self.state.active]; let lock=l.alpha_lock;
        let pixels=&mut l.pixels; let mut seen=vec![false;pixels.len()]; let mut stack=vec![start];let mut fill_rect=[self.width,self.height,0,0];
        let matches=|i:usize| !self.selection.as_ref().is_some_and(|s|!s[i]) && distance(before[i],target)<=threshold.clamp(0.0,1.0);
        while let Some(i)=stack.pop() {
            if seen[i] || !matches(i){continue;}
            let row=i/self.width;let mut x=i%self.width;
            while x>0 && !seen[row*self.width+x-1] && matches(row*self.width+x-1){x-=1;}
            let mut above=false;let mut below=false;
            while x<self.width {
                let idx=row*self.width+x;if seen[idx]||!matches(idx){break;}
                seen[idx]=true;fill_rect[0]=fill_rect[0].min(x);fill_rect[1]=fill_rect[1].min(row);fill_rect[2]=fill_rect[2].max(x+1);fill_rect[3]=fill_rect[3].max(row+1);let ink=if lock {(color&0xffffff)|(before[idx]&0xff000000)}else{color};pixels[idx]=mix_rgba(before[idx],ink,selection_alpha.as_ref().map_or(1.,|a|a[idx] as f32/255.));
                if row>0 {let n=idx-self.width;let ok=!seen[n]&&matches(n);if ok&&!above{stack.push(n);}above=ok;}
                if row+1<self.height {let n=idx+self.width;let ok=!seen[n]&&matches(n);if ok&&!below{stack.push(n);}below=ok;}
                x+=1;
            }
        }
        // Replace the background contribution in a one-pixel antialiased fringe.
        // Read only the original pixels so cleanup cannot propagate through an outline.
        for y in fill_rect[1].saturating_sub(1)..(fill_rect[3]+1).min(self.height) { for x in fill_rect[0].saturating_sub(1)..(fill_rect[2]+1).min(self.width) {
            let i=y*self.width+x;
            if seen[i] || self.selection.as_ref().is_some_and(|s|!s[i]) { continue; }
            let adjacent=(y.saturating_sub(1)..=(y+1).min(self.height-1)).any(|yy|
                (x.saturating_sub(1)..=(x+1).min(self.width-1)).any(|xx|seen[yy*self.width+xx]));
            if !adjacent { continue; }
            let edge=before[i];let ea=channel(edge,24);
            let repaired=if channel(target,24)<0.001 && ea>0.0 && ea<0.999 {
                Some(composite(color,edge,1.0,"Normal"))
            } else if channel(target,24)>0.999 && ea>0.999 {
                let mut core=edge;let mut far=distance(edge,target);
                for yy in y.saturating_sub(2)..=(y+2).min(self.height-1) {for xx in x.saturating_sub(2)..=(x+2).min(self.width-1) {
                    let j=yy*self.width+xx;let d=distance(before[j],target);
                    if !seen[j] && d>far && channel(before[j],24)>0.999 {core=before[j];far=d;}
                }}
                let a=[channel(target,16),channel(target,8),channel(target,0)];
                let b=[channel(core,16),channel(core,8),channel(core,0)];
                let c=[channel(edge,16),channel(edge,8),channel(edge,0)];
                let norm=(0..3).map(|k|(b[k]-a[k]).powi(2)).sum::<f32>();
                let coverage=if norm>1e-6 {(0..3).map(|k|(c[k]-a[k])*(b[k]-a[k])).sum::<f32>()/norm}else{1.0};
                let error=(0..3).map(|k|(c[k]-(a[k]+(b[k]-a[k])*coverage)).abs()).fold(0.0,f32::max);
                if coverage>0.001 && coverage<0.999 && error<0.025 {Some(composite(color,core,coverage,"Normal"))}else{None}
            }else{None};
            if let Some(value)=repaired {let ink=if lock {(value&0xffffff)|(edge&0xff000000)}else{value};pixels[i]=mix_rgba(before[i],ink,selection_alpha.as_ref().map_or(1.,|a|a[i] as f32/255.));}
        }}
        if record {self.pixel_change(self.state.active,before.to_vec());}else{self.dirty=true;}
        Ok(())
    }

    pub fn import(&mut self,width:usize,height:usize,pixels:&[u32])->Result<(),String> {
        if width==0 || height==0 || width.checked_mul(height)!=Some(pixels.len()) || pixels.len()>MAX_PIXELS {return Err("Invalid image".into());}
        if !self.can_add() {return Err("Layer limit reached".into());}
        self.command(&json!({"op":"add"}))?;
        let before=self.state.layers[self.state.active].pixels.clone();
        let data=&mut self.state.layers[self.state.active].pixels;
        let scale=(self.width as f32/width as f32).min(self.height as f32/height as f32).min(1.0);
        let (dw,dh)=(((width as f32*scale).round() as usize).clamp(1,self.width),((height as f32*scale).round() as usize).clamp(1,self.height));
        let (dx,dy)=((self.width-dw)/2,(self.height-dh)/2);
        for y in 0..dh {for x in 0..dw {data[(y+dy)*self.width+x+dx]=pixels[((y as f32/scale) as usize).min(height-1)*width+((x as f32/scale) as usize).min(width-1)];}}
        self.state.layers[self.state.active].name="Inserted image".into();
        self.pixel_change(self.state.active,before); Ok(())
    }

    pub fn save(&mut self)->Result<Vec<u8>,String>{let mut bytes=Vec::new();self.write_document(&mut bytes)?;Ok(bytes)}
    /// Native document format: binary tiles with incremental re-encoding (see binfmt).
    pub fn save_compressed(&mut self)->Result<Vec<u8>,String>{
        self.flush_gpu();
        let sources:Vec<Tiles>=self.state.layers.iter().map(|l|match &self.flood_preview{Some((index,before)) if self.state.layers[*index].id==l.id=>before.clone(),_=>l.pixels.clone()}).collect();
        let mut header=Vec::new();self.write_document_with(&mut header,true)?;
        let mut out=Vec::with_capacity(header.len()+1024);binfmt::write_header(&mut out,&header);
        let mut cache=binfmt::begin((self.width,self.height));
        for (i,pixels) in sources.iter().enumerate(){
            if pixels.is_blank(){out.push(0);continue;}
            if let Some(j)=sources[..i].iter().position(|p|p.generation()==pixels.generation()){out.push(2);out.extend_from_slice(&(j as u32).to_le_bytes());continue;}
            binfmt::encode_layer(&pixels.to_vec(),self.width,self.height,self.state.layers[i].id,&self.save_cache,&mut cache,&mut out);
        }
        self.save_cache=cache;Ok(out)
    }
    fn write_document(&mut self,writer:impl std::io::Write)->Result<(),String>{self.flush_gpu();self.write_document_with(writer,false)}
    fn write_document_with(&self,writer:impl std::io::Write,placeholder:bool)->Result<(),String> {
        let blank=Tiles::blank(self.width,self.height);
        let doc=SavedDocument {groups:self.state.groups.clone(),selections:self.saved_selections.clone(),version:if self.state.groups.iter().any(|g|g.parent_id.is_some() || g.anchor_id.is_some()){5}else if self.state.layers.iter().any(|l|!l.exterior.is_empty()){4}else if !self.state.groups.is_empty(){3}else if self.state.layers.iter().any(|l|l.mask.is_some()){2}else{1},width:self.width,height:self.height,active:self.state.active,
            dpi:self.dpi,background:self.state.background,background_visible:self.state.background_visible,
            layers:self.state.layers.iter().map(|l| SavedLayerWrite {adjustment:l.adjustment.clone(),exterior:exterior::encode(&l.exterior),group_id:l.group_id,id:l.id,name:l.name.clone(),visible:l.visible,
                opacity:l.opacity,blend:l.blend.clone(),alpha_lock:l.alpha_lock,locked:l.locked,clipping:l.clipping,mask_enabled:l.mask_enabled,mask:l.mask.as_ref().map(|m|encode_mask_runs(m)),runs:PixelRuns(if placeholder{&blank}else if let Some((index,before))=&self.flood_preview {if self.state.layers[*index].id==l.id {before}else{&l.pixels}}else{&l.pixels})}).collect()};
        serde_json::to_writer(writer,&doc).map_err(|e|e.to_string())
    }
    pub fn load(bytes:&[u8])->Result<Self,String> {
        if bytes.starts_with(&[0x1f,0x8b]){use std::io::Read;let mut decoded=Vec::new();flate2::read::GzDecoder::new(bytes).take(128*1024*1024+1).read_to_end(&mut decoded).map_err(|e|e.to_string())?;if decoded.len()>128*1024*1024{return Err("Document is too large".into());}if decoded.starts_with(&[0x1f,0x8b]){return Err("Invalid nested document".into());}return Self::load(&decoded);}

        if bytes.len()>128*1024*1024 {return Err("Document is too large".into());}
        if bytes.starts_with(binfmt::MAGIC){
            let mut parsed=None;
            let (_,blobs)=binfmt::parse(bytes,|header|{let doc:SavedDocument=serde_json::from_slice(header).map_err(|_|"This is not a supported Inkstone document")?;let dims=(doc.width,doc.height,doc.layers.len());parsed=Some(doc);Ok(dims)})?;
            return Self::from_saved(parsed.unwrap(),Some(blobs));
        }
        let doc:SavedDocument=serde_json::from_slice(bytes).map_err(|_|"This is not a supported Inkstone document")?;
        Self::from_saved(doc,None)
    }
    fn from_saved(doc:SavedDocument,blobs:Option<Vec<binfmt::Blob<'_>>>)->Result<Self,String>{
        if !matches!(doc.version,1|2|3|4|5) {return Err("Unsupported document version".into());}
        doc.validate_decode_budget()?;
        if blobs.as_ref().is_some_and(|b|b.len()!=doc.layers.len()){return Err("Invalid layer data".into());}
        let mut e=Self::new(doc.width,doc.height)?;
        if doc.layers.is_empty() || doc.active>=doc.layers.len() {return Err("Invalid layer count".into());}
        let mut layers=vec![]; let mut ids=std::collections::HashSet::new();
        let mut pixel_cache:std::collections::HashMap<Vec<(u32,u32)>,Tiles>=std::collections::HashMap::new();
        let mut exterior_count=0usize;
        let mut save_cache=binfmt::SaveCache::default();
        for (index,l) in doc.layers.into_iter().enumerate() {
            exterior_count+=l.exterior.len();if exterior_count>1024{return Err("Off-canvas storage exceeds document budget".into());}
            if !ids.insert(l.id) || l.id==u32::MAX || !l.opacity.is_finite() {return Err("Invalid layer metadata".into());}
            let binary=blobs.as_ref().map(|b|&b[index]);
            let pixels=if let Some(blob)=binary {match blob{
                binfmt::Blob::Empty=>Tiles::blank(doc.width,doc.height),
                binfmt::Blob::Alias(j)=>layers.get(*j).map(|l:&Layer|l.pixels.clone()).ok_or("Invalid layer reference")?,
                binfmt::Blob::Tiles(tiles)=>{
                    if Self::layer_bytes(&layers)>PIXEL_BUDGET*4{return Err("Artwork exceeds the layer memory budget".into());}
                    let pixels=binfmt::decode_layer(tiles,doc.width,doc.height,l.id,&mut save_cache)?;
                    Tiles::from_flat(&pixels,doc.width,doc.height)
                }
            }}else if l.runs.iter().all(|r|r.1==0) {
                if l.runs.iter().any(|r|r.0==0) || l.runs.iter().map(|r|r.0 as usize).sum::<usize>()!=doc.width*doc.height{return Err("Invalid empty layer".into());}
                Tiles::blank(doc.width,doc.height)
            }else if let Some(pixels)=pixel_cache.get(&l.runs){pixels.clone()}else{
                if Self::layer_bytes(&layers)>PIXEL_BUDGET*4{return Err("Artwork exceeds the layer memory budget".into());}
                let pixels=Tiles::from_flat(&decode_runs(&l.runs,doc.width*doc.height)?,doc.width,doc.height);
                pixel_cache.insert(l.runs.clone(),pixels.clone());pixels
            };
            layers.push(Layer{adjustment:l.adjustment,exterior:exterior::decode(l.exterior)?,group_id:l.group_id,group_visible:true,group_locked:false,id:l.id,name:l.name.chars().take(100).collect(),visible:l.visible,opacity:l.opacity.clamp(0.0,1.0),
                blend:l.blend,alpha_lock:l.alpha_lock,locked:l.locked,clipping:l.clipping,mask_enabled:l.mask_enabled,mask:match l.mask{Some(runs)=>Some(Arc::new(decode_mask_runs(&runs,doc.width*doc.height)?)),None=>None},pixels});
        }
        if layers.iter().map(|l|l.exterior.len()).sum::<usize>()>1024{return Err("Off-canvas storage exceeds document budget".into());}
        if Self::layer_bytes(&layers)>PIXEL_BUDGET*4{return Err("Document masks exceed the layer memory budget".into());}
        if doc.selections.values().map(|r|r.len()*8).sum::<usize>()>32*1024*1024{return Err("Saved selections exceed document budget".into());}
        for runs in doc.selections.values(){validate_runs(runs,doc.width*doc.height,255)?;}
        e.saved_selections=doc.selections;
        e.dpi=doc.dpi.clamp(1,2400);
        e.next_id=layers.iter().map(|l|l.id).max().unwrap()+1;
        let mut group_ids=std::collections::HashSet::new();
        if doc.groups.len()>512 || doc.groups.iter().any(|g|g.id==0 || g.id==u32::MAX || !group_ids.insert(g.id) || g.name.len()>400 || !g.opacity.is_finite() || !(0.0..=1.0).contains(&g.opacity) || !matches!(g.blend.as_str(),"Pass Through"|"Normal"|"Multiply"|"Screen"|"Overlay"|"Darken"|"Lighten"|"Color Burn"|"Linear Burn"|"Color Dodge"|"Add"|"Soft Light"|"Hard Light"|"Difference"|"Exclusion"|"Subtract")){return Err("Invalid layer groups".into());}
        e.state=State{groups:doc.groups,layers,active:doc.active,background:doc.background,background_visible:doc.background_visible};
        for g in &e.state.groups{
            if g.parent_id.is_some_and(|id|!e.state.groups.iter().any(|p|p.id==id)){return Err("Invalid parent group".into());}
            let mut seen=std::collections::HashSet::new();let mut next=Some(g.id);
            while let Some(id)=next{if !seen.insert(id){return Err("Cyclic layer groups".into());}if seen.len()>32{return Err("Group nesting limit reached".into());}next=e.state.groups.iter().find(|g|g.id==id).and_then(|g|g.parent_id);}
        }
        e.normalize_groups();
        e.save_cache=save_cache;
        Ok(e)
    }
}

fn sample_tip(tip:&[u32],x:f32,y:f32)->u32 {
    let x=x.clamp(0.0,31.0);let y=y.clamp(0.0,31.0);let ix=x as usize;let iy=y as usize;
    let a=mix_rgba(tip[iy*32+ix],tip[iy*32+(ix+1).min(31)],x-ix as f32);
    let b=mix_rgba(tip[(iy+1).min(31)*32+ix],tip[(iy+1).min(31)*32+(ix+1).min(31)],x-ix as f32);
    mix_rgba(a,b,y-iy as f32)
}

fn mix_rgba(a:u32,b:u32,t:f32)->u32 {
    let aa=channel(a,24)*(1.0-t);let ba=channel(b,24)*t;let alpha=aa+ba;
    if alpha<0.00001{return 0;}
    pack((channel(a,16)*aa+channel(b,16)*ba)/alpha,(channel(a,8)*aa+channel(b,8)*ba)/alpha,
        (channel(a,0)*aa+channel(b,0)*ba)/alpha,alpha)
}

fn noise(x:u32,y:u32,seed:u32)->f32 {
    let mut n=x.wrapping_mul(374761393).wrapping_add(y.wrapping_mul(668265263)).wrapping_add(seed);
    n=(n^(n>>13)).wrapping_mul(1274126177); ((n^(n>>16))&65535) as f32/65535.0
}
fn inside_polygon(x:f32,y:f32,points:&[(f32,f32)])->bool {
    let mut inside=false; let mut j=points.len()-1;
    for i in 0..points.len() {let (xi,yi)=points[i];let(xj,yj)=points[j];
        if (yi>y)!=(yj>y) && x<(xj-xi)*(y-yi)/(yj-yi)+xi {inside=!inside;} j=i;}
    inside
}
fn blur(pixels:&mut [u32],w:usize,h:usize,r:usize) {
    let mut tmp=vec![0;pixels.len()];
    for pass in 0..2 {let (major,minor)=if pass==0 {(h,w)} else {(w,h)};
        for a in 0..major {let mut sums=[0.0;4];let index=|b:usize|if pass==0 {a*w+b} else {b*w+a};
            for b in 0..=r.min(minor-1) {let c=pixels[index(b)];let alpha=channel(c,24);for k in 0..3 {sums[k]+=channel(c,[16,8,0][k])*alpha;}sums[3]+=alpha;}
            for b in 0..minor {
                let count=(b+r+1).min(minor)-b.saturating_sub(r);let alpha=sums[3]/count as f32;
                tmp[index(b)]=pack(sums[0]/sums[3].max(0.0001),sums[1]/sums[3].max(0.0001),sums[2]/sums[3].max(0.0001),alpha);
                if b>=r {let c=pixels[index(b-r)];let alpha=channel(c,24);for k in 0..3 {sums[k]-=channel(c,[16,8,0][k])*alpha;}sums[3]-=alpha;}
                if b+r+1<minor {let c=pixels[index(b+r+1)];let alpha=channel(c,24);for k in 0..3 {sums[k]+=channel(c,[16,8,0][k])*alpha;}sums[3]+=alpha;}
            }
        } pixels.copy_from_slice(&tmp);
    }
}
fn default_dpi()->u32{300}
#[derive(Serialize,Deserialize)]
struct SavedDocument<L=SavedLayer> {#[serde(default,skip_serializing_if="Vec::is_empty")]groups:Vec<LayerGroup>,#[serde(default,skip_serializing_if="std::collections::BTreeMap::is_empty")]selections:std::collections::BTreeMap<String,Vec<(u32,u32)>>,#[serde(default="default_dpi")]dpi:u32,version:u32,width:usize,height:usize,active:usize,background:u32,background_visible:bool,layers:Vec<L>}
fn default_true()->bool{true}
#[derive(Serialize,Deserialize)]
struct SavedLayer {#[serde(default)]adjustment:Option<(String,f32)>,#[serde(default,skip_serializing_if="std::collections::BTreeMap::is_empty")]exterior:std::collections::BTreeMap<i64,Vec<(u32,u32)>>,#[serde(default,skip_serializing_if="Option::is_none")]group_id:Option<u32>,#[serde(default)]mask:Option<Vec<(u32,u32)>>,#[serde(default="default_true")]mask_enabled:bool,#[serde(default)]locked:bool,id:u32,name:String,visible:bool,opacity:f32,blend:String,alpha_lock:bool,clipping:bool,runs:Vec<(u32,u32)>}
fn encode_mask_runs(values:&[u8])->Vec<(u32,u32)>{let mut out=vec![];for &v in values{let c=v as u32;if let Some((n,p))=out.last_mut(){if *p==c{*n+=1;continue;}}out.push((1,c));}out}
fn encode_runs(pixels:&[u32])->Vec<(u32,u32)> {
    let mut runs=vec![];for &p in pixels {if let Some((n,c))=runs.last_mut() {if *c==p {*n+=1;continue;}}runs.push((1,p));}runs
}
fn decode_runs(runs:&[(u32,u32)],count:usize)->Result<Vec<u32>,String> {
    let mut out=Vec::with_capacity(count);
    for &(n,c) in runs {if n==0 || n as usize>count-out.len() {return Err("Invalid pixel data".into());}out.resize(out.len()+n as usize,c);}
    if out.len()!=count {return Err("Incomplete pixel data".into());}Ok(out)
}

#[cfg(any(target_os="android", feature="host-jni"))]
mod android;

#[cfg(test)]
mod mixing_tests;

#[cfg(test)]
mod performance_tests;

#[cfg(test)]
mod stroke_path_tests;

#[cfg(test)]
mod tests;

#[cfg(test)]
mod gpu_tests;

#[cfg(test)]
mod bug_hunt_tests;

fn validate_runs(runs:&[(u32,u32)],count:usize,max:u32)->Result<(),String>{
    let mut total=0usize;
    for &(n,value)in runs{if n==0 || value>max{return Err("Invalid pixel data".into());}total=total.checked_add(n as usize).ok_or("Invalid pixel count")?;if total>count{return Err("Invalid pixel count".into());}}
    if total!=count{return Err("Incomplete pixel data".into());}Ok(())
}
fn decode_mask_runs(runs:&[(u32,u32)],count:usize)->Result<Vec<u8>,String>{
    validate_runs(runs,count,255)?;let mut out=Vec::with_capacity(count);for &(n,value)in runs{out.resize(out.len()+n as usize,value as u8);}Ok(out)
}
impl SavedDocument{
    fn validate_decode_budget(&self)->Result<(),String>{
        let count=self.width.checked_mul(self.height).ok_or("Canvas size overflow")?;
        if self.width<16 || self.height<16 || self.width>8192 || self.height>8192 || count>MAX_PIXELS{return Err("Invalid canvas dimensions".into());}
        if self.layers.is_empty() || self.layers.len()>MAX_LAYERS || self.active>=self.layers.len(){return Err("Invalid layer count".into());}
        if self.groups.len()>512{return Err("Too many layer groups".into());}
        let mut bytes=0usize;let mut has_empty=false;let mut pixels=std::collections::HashSet::new();let mut ids=std::collections::HashSet::new();let mut tiles=0;
        for layer in &self.layers{
            if layer.id==0 || layer.id==u32::MAX || !ids.insert(layer.id) || !layer.opacity.is_finite(){return Err("Invalid layer metadata".into());}
            validate_runs(&layer.runs,count,u32::MAX)?;
            let nonempty=layer.runs.iter().any(|(_,value)|*value!=0);
            if (nonempty && pixels.insert(&layer.runs)) || (!nonempty && !has_empty){bytes=bytes.checked_add(count*4).ok_or("Document memory budget exceeded")?;}
            if !nonempty{has_empty=true;}
            if let Some(mask)=&layer.mask{validate_runs(mask,count,255)?;bytes=bytes.checked_add(count).ok_or("Document memory budget exceeded")?;}
            tiles+=layer.exterior.len();if layer.exterior.len()>256 || tiles>1024{return Err("Off-canvas storage exceeds document budget".into());}
            bytes=bytes.checked_add(layer.exterior.len()*128*128*4).ok_or("Document memory budget exceeded")?;
            if bytes>PIXEL_BUDGET*4{return Err("Document exceeds decode memory budget".into());}
        }Ok(())
    }
}

#[cfg(test)]
mod group_clipping_tests;

#[cfg(test)]
mod brush_texture_tests;

#[cfg(test)]
mod save_format_tests;

#[derive(Serialize)]
struct SavedLayerWrite<'a> {#[serde(default)]adjustment:Option<(String,f32)>,#[serde(default,skip_serializing_if="std::collections::BTreeMap::is_empty")]exterior:std::collections::BTreeMap<i64,Vec<(u32,u32)>>,#[serde(default,skip_serializing_if="Option::is_none")]group_id:Option<u32>,#[serde(default)]mask:Option<Vec<(u32,u32)>>,#[serde(default="default_true")]mask_enabled:bool,#[serde(default)]locked:bool,id:u32,name:String,visible:bool,opacity:f32,blend:String,alpha_lock:bool,clipping:bool,runs:PixelRuns<'a>}
struct PixelRuns<'a>(&'a Tiles);
impl Serialize for PixelRuns<'_>{fn serialize<S:serde::Serializer>(&self,serializer:S)->Result<S::Ok,S::Error>{use serde::ser::SerializeSeq;let pixels=self.0;let len=pixels.len();let mut seq=serializer.serialize_seq(None)?;let mut i=0;while i<len{let color=pixels[i];let start=i;i+=1;while i<len && pixels[i]==color{i+=1;}seq.serialize_element(&((i-start) as u32,color))?;}seq.end()}}
