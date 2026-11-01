//! Binary document container (replaces per-pixel JSON runs for layer pixels).
//!
//! Layout: MAGIC, u32 LE header length, header JSON (layer metadata, masks, exterior),
//! then one blob per layer: kind u8 (0 empty, 1 tiles, 2 alias u32 index).
//! Tiles are 256×256, row-major; each record is tag u8 (0 transparent, 1 solid u32,
//! 2 deflate: u32 length + raw little-endian pixels). Unchanged tiles reuse their
//! previous encoding through a content hash, so repeated saves only compress edits.
use std::collections::HashMap;
use std::io::{Read, Write};
use std::sync::Arc;

pub const MAGIC: &[u8; 8] = b"INKBIN1\n";
const TILE: usize = 256;

#[derive(Default)]
pub struct SaveCache {
    dims: (usize, usize),
    tiles: HashMap<(u32, u32), (u128, Arc<Vec<u8>>)>,
}

fn grid(width: usize, height: usize) -> (usize, usize) { (width.div_ceil(TILE), height.div_ceil(TILE)) }
fn tile_rect(index: usize, width: usize, height: usize) -> [usize; 4] {
    let columns = grid(width, height).0;
    let x = index % columns * TILE; let y = index / columns * TILE;
    [x, y, (x + TILE).min(width), (y + TILE).min(height)]
}
fn hash_rows<'a>(rows: impl Iterator<Item = &'a [u32]>) -> u128 {
    let (mut a, mut b) = (0x243F_6A88_85A3_08D3u64, 0x1319_8A2E_0370_7344u64);
    for row in rows { for &p in row {
        let v = p as u64;
        a = (a ^ v).wrapping_mul(0x9E37_79B9_7F4A_7C15); a ^= a >> 29;
        b = b.wrapping_add(v ^ 0xA076_1D64_78BD_642F).wrapping_mul(0xC2B2_AE3D_27D4_EB4F).rotate_left(31);
    }}
    ((a as u128) << 64) | b as u128
}
fn encode_tile<'a>(rows: impl Iterator<Item = &'a [u32]> + Clone) -> Vec<u8> {
    let mut all = rows.clone().flat_map(|r| r.iter());
    let first = *all.next().unwrap_or(&0);
    if rows.clone().all(|r| r.iter().all(|p| *p == first)) {
        if first == 0 { return vec![0]; }
        let mut out = vec![1]; out.extend_from_slice(&first.to_le_bytes()); return out;
    }
    let mut z = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::fast());
    for row in rows { z.write_all(bytemuck::cast_slice(row)).expect("in-memory write"); }
    let data = z.finish().expect("in-memory write");
    let mut out = Vec::with_capacity(data.len() + 5);
    out.push(2); out.extend_from_slice(&(data.len() as u32).to_le_bytes()); out.extend_from_slice(&data); out
}
fn threads(jobs: usize) -> usize {
    std::thread::available_parallelism().map_or(4, |n| n.get()).clamp(1, 8).min(jobs.max(1))
}
/// Runs `work` for every index in `0..count` across worker threads, preserving order.
fn parallel<T: Send>(count: usize, work: impl Fn(usize) -> T + Sync) -> Vec<T> {
    let n = threads(count);
    if n <= 1 { return (0..count).map(&work).collect(); }
    let mut parts: Vec<Vec<(usize, T)>> = std::thread::scope(|s| {
        let handles: Vec<_> = (0..n).map(|t| { let work = &work; s.spawn(move || (t..count).step_by(n).map(|i| (i, work(i))).collect::<Vec<_>>()) }).collect();
        handles.into_iter().map(|h| h.join().expect("save worker")).collect()
    });
    let mut out: Vec<Option<T>> = (0..count).map(|_| None).collect();
    for part in parts.iter_mut() { for (i, v) in part.drain(..) { out[i] = Some(v); } }
    out.into_iter().map(|v| v.expect("tile result")).collect()
}

/// Encodes one layer's tiles, reusing cached encodings whose content hash still matches.
pub fn encode_layer(pixels: &[u32], width: usize, height: usize, id: u32, old: &SaveCache, new: &mut SaveCache, out: &mut Vec<u8>) {
    let (columns, rows) = grid(width, height);
    let reuse = old.dims == (width, height);
    let results = parallel(columns * rows, |i| {
        let [x0, y0, x1, y1] = tile_rect(i, width, height);
        let rows = (y0..y1).map(move |y| &pixels[y * width + x0..y * width + x1]);
        let hash = hash_rows(rows.clone());
        if reuse { if let Some((h, bytes)) = old.tiles.get(&(id, i as u32)) { if *h == hash { return (hash, bytes.clone()); } } }
        (hash, Arc::new(encode_tile(rows)))
    });
    out.push(1);
    for (i, (hash, bytes)) in results.into_iter().enumerate() { out.extend_from_slice(&bytes); new.tiles.insert((id, i as u32), (hash, bytes)); }
}
pub fn begin(cache_dims: (usize, usize)) -> SaveCache { SaveCache { dims: cache_dims, tiles: HashMap::new() } }
pub fn write_header(out: &mut Vec<u8>, header: &[u8]) {
    out.extend_from_slice(MAGIC); out.extend_from_slice(&(header.len() as u32).to_le_bytes()); out.extend_from_slice(header);
}

pub enum Tile<'a> { Empty, Solid(u32), Deflate(&'a [u8]) }
pub struct TileRecord<'a> { raw: &'a [u8], tile: Tile<'a> }
pub enum Blob<'a> { Empty, Alias(usize), Tiles(Vec<TileRecord<'a>>) }

struct Cursor<'a> { data: &'a [u8], at: usize }
impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self.at.checked_add(n).filter(|e| *e <= self.data.len()).ok_or("Truncated document")?;
        let s = &self.data[self.at..end]; self.at = end; Ok(s)
    }
    fn u8(&mut self) -> Result<u8, String> { Ok(self.take(1)?[0]) }
    fn u32(&mut self) -> Result<u32, String> { Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap())) }
}

/// Splits a binary document into its header JSON and per-layer blobs without decompressing pixels.
pub fn parse(bytes: &[u8], width_height: impl FnOnce(&[u8]) -> Result<(usize, usize, usize), String>) -> Result<(&[u8], Vec<Blob<'_>>), String> {
    let mut c = Cursor { data: bytes, at: MAGIC.len() };
    let len = c.u32()? as usize; let header = c.take(len)?;
    let (width, height, layers) = width_height(header)?;
    let (columns, rows) = grid(width, height);
    let mut blobs = Vec::with_capacity(layers);
    for index in 0..layers {
        blobs.push(match c.u8()? {
            0 => Blob::Empty,
            2 => { let j = c.u32()? as usize; if j >= index { return Err("Invalid layer reference".into()); } Blob::Alias(j) },
            1 => {
                let mut tiles = Vec::with_capacity(columns * rows);
                for _ in 0..columns * rows {
                    let start = c.at;
                    let tile = match c.u8()? { 0 => Tile::Empty, 1 => Tile::Solid(c.u32()?), 2 => { let n = c.u32()? as usize; Tile::Deflate(c.take(n)?) }, _ => return Err("Invalid tile data".into()) };
                    tiles.push(TileRecord { raw: &bytes[start..c.at], tile });
                }
                Blob::Tiles(tiles)
            },
            _ => return Err("Invalid layer data".into()),
        });
    }
    if c.at != bytes.len() { return Err("Unexpected trailing document data".into()); }
    Ok((header, blobs))
}

/// Decompresses one layer and primes the save cache with the tiles it read.
pub fn decode_layer(tiles: &[TileRecord<'_>], width: usize, height: usize, id: u32, cache: &mut SaveCache) -> Result<Vec<u32>, String> {
    let decoded = parallel(tiles.len(), |i| -> Result<Option<Vec<u32>>, String> {
        let [x0, y0, x1, y1] = tile_rect(i, width, height);
        let count = (x1 - x0) * (y1 - y0);
        match tiles[i].tile {
            Tile::Empty => Ok(None),
            Tile::Solid(c) => Ok(Some(vec![c; count])),
            Tile::Deflate(data) => {
                let mut raw = Vec::with_capacity(count * 4);
                flate2::read::DeflateDecoder::new(data).take(count as u64 * 4 + 1).read_to_end(&mut raw).map_err(|e| e.to_string())?;
                if raw.len() != count * 4 { return Err("Invalid tile size".into()); }
                Ok(Some(raw.chunks_exact(4).map(|b| u32::from_le_bytes(b.try_into().unwrap())).collect()))
            }
        }
    });
    let mut pixels = vec![0u32; width * height];
    cache.dims = (width, height);
    for (i, tile) in decoded.into_iter().enumerate() {
        let [x0, y0, x1, y1] = tile_rect(i, width, height); let w = x1 - x0;
        if let Some(data) = tile? {
            for (row, y) in (y0..y1).enumerate() { pixels[y * width + x0..y * width + x1].copy_from_slice(&data[row * w..(row + 1) * w]); }
        }
        let hash = hash_rows((y0..y1).map(|y| &pixels[y * width + x0..y * width + x1]));
        cache.tiles.insert((id, i as u32), (hash, Arc::new(tiles[i].raw.to_vec())));
    }
    Ok(pixels)
}
