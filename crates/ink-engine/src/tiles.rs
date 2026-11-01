//! Layer pixel storage in 64 × 64 tiles.
//!
//! Blank tiles are not allocated, so a sparse layer on a large canvas costs only the tiles it
//! paints. Cloning shares tile buffers, which makes an undo snapshot cheap: a later write copies
//! only the tile it touches.
//!
//! `generation` identifies the content. Every mutable access assigns a fresh generation, and
//! clones share the generation of the content they copied. Caches can therefore compare
//! generations instead of buffer addresses.

use std::ops::{Index, IndexMut};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

pub(crate) const TILE: usize = 64;
const TILE_LEN: usize = TILE * TILE;
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);
static ZERO: u32 = 0;

fn fresh_generation() -> u64 {
    NEXT_GENERATION.fetch_add(1, Ordering::Relaxed)
}

#[derive(Clone)]
pub struct Tiles {
    width: usize,
    height: usize,
    columns: usize,
    tiles: Vec<Option<Arc<Vec<u32>>>>,
    allocated: usize,
    generation: u64,
}

impl Tiles {
    /// Fully transparent canvas; allocates nothing.
    pub fn blank(width: usize, height: usize) -> Self {
        let columns = width.div_ceil(TILE);
        Self {
            width,
            height,
            columns,
            tiles: vec![None; columns * height.div_ceil(TILE)],
            allocated: 0,
            // Every blank canvas has the same content, so they share generation 0.
            generation: 0,
        }
    }

    /// Stores the non-transparent tiles of a row-major canvas.
    pub fn from_flat(pixels: &[u32], width: usize, height: usize) -> Self {
        let mut out = Self::blank(width, height);
        for ty in 0..height.div_ceil(TILE) {
            for tx in 0..out.columns {
                let (x0, y0) = (tx * TILE, ty * TILE);
                let (w, h) = (TILE.min(width - x0), TILE.min(height - y0));
                let mut tile = vec![0u32; TILE_LEN];
                let mut any = false;
                for row in 0..h {
                    let src = (y0 + row) * width + x0;
                    tile[row * TILE..row * TILE + w].copy_from_slice(&pixels[src..src + w]);
                    any |= pixels[src..src + w].iter().any(|&p| p != 0);
                }
                if any {
                    out.tiles[ty * out.columns + tx] = Some(Arc::new(tile));
                    out.allocated += 1;
                }
            }
        }
        if out.allocated > 0 {
            out.generation = fresh_generation();
        }
        out
    }

    pub fn width(&self) -> usize {
        self.width
    }

    pub fn height(&self) -> usize {
        self.height
    }

    /// Identifies this content; equal generations mean equal pixels.
    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// True when every pixel is transparent (no tile is allocated).
    pub fn is_blank(&self) -> bool {
        self.allocated == 0
    }

    /// Bytes held by allocated tiles, which is what the canvas memory budget counts.
    pub fn allocated_bytes(&self) -> usize {
        self.allocated * TILE_LEN * 4
    }

    /// Row-major copy of the whole canvas. Used by cold paths such as saving and export.
    pub fn to_vec(&self) -> Vec<u32> {
        let mut out = vec![0u32; self.width * self.height];
        for (index, tile) in self.tiles.iter().enumerate() {
            if let Some(tile) = tile {
                let (x0, y0) = ((index % self.columns) * TILE, (index / self.columns) * TILE);
                let (w, h) = (TILE.min(self.width - x0), TILE.min(self.height - y0));
                for row in 0..h {
                    let dst = (y0 + row) * self.width + x0;
                    out[dst..dst + w].copy_from_slice(&tile[row * TILE..row * TILE + w]);
                }
            }
        }
        out
    }

    /// Pixels `x0..x1` of row `y`.
    pub fn row(&self, y: usize, x0: usize, x1: usize) -> Vec<u32> {
        (x0..x1).map(|x| self[y * self.width + x]).collect()
    }

    /// Writes `values` into row `y` starting at `x0`, copying only the tiles it touches.
    pub fn set_row(&mut self, y: usize, x0: usize, values: &[u32]) {
        for (i, &v) in values.iter().enumerate() {
            let index = y * self.width + x0 + i;
            self[index] = v;
        }
    }

    /// Number of pixels (width × height), like the old flat buffer's length.
    pub fn len(&self) -> usize {
        self.width * self.height
    }

    /// Sets every pixel to `value`.
    pub fn fill(&mut self, value: u32) {
        if value == 0 {
            self.fill_zero();
        } else {
            for i in 0..self.len() {
                self[i] = value;
            }
        }
    }

    /// Clears every pixel by dropping all tiles.
    pub fn fill_zero(&mut self) {
        self.tiles.iter_mut().for_each(|t| *t = None);
        self.allocated = 0;
        self.generation = 0;
    }

    /// Pixel values in row-major order.
    pub fn iter(&self) -> impl Iterator<Item = u32> + '_ {
        (0..self.len()).map(move |i| self[i])
    }

    /// Makes `self` share `other`'s tiles and generation. Used to restore snapshots.
    pub fn assign_from(&mut self, other: &Tiles) {
        *self = other.clone();
    }

    /// Whether the tile at (tx, ty) holds any allocated pixels.
    pub fn tile_is_allocated(&self, tx: usize, ty: usize) -> bool {
        self.tiles[ty * self.columns + tx].is_some()
    }

    /// Row-major copy of `rect` = [x0, y0, x1, y1] (exclusive end).
    pub fn rect(&self, rect: [usize; 4]) -> Vec<u32> {
        let mut out = Vec::with_capacity((rect[2] - rect[0]) * (rect[3] - rect[1]));
        for y in rect[1]..rect[3] {
            out.extend((rect[0]..rect[2]).map(|x| self[y * self.width + x]));
        }
        out
    }

    /// Allocates and uniquely owns every tile overlapping `rect`, so a parallel writer can
    /// take disjoint tile rows with `tile_rows_mut`. Bumps the generation.
    pub fn prepare_write(&mut self, rect: [usize; 4]) {
        self.generation = fresh_generation();
        let x1 = rect[2].min(self.width);
        let y1 = rect[3].min(self.height);
        if rect[0] >= x1 || rect[1] >= y1 {
            return;
        }
        for ty in rect[1] / TILE..=(y1 - 1) / TILE {
            for tx in rect[0] / TILE..=(x1 - 1) / TILE {
                let slot = &mut self.tiles[ty * self.columns + tx];
                if slot.is_none() {
                    *slot = Some(Arc::new(vec![0u32; TILE_LEN]));
                    self.allocated += 1;
                }
                Arc::make_mut(slot.as_mut().unwrap());
            }
        }
    }

    /// Tile slots split into bands of `band_tile_rows` tile rows. Call `prepare_write` first.
    /// Each band is `(first_tile_row, slots)`; a slot's pixels are `Arc::get_mut` unique.
    pub fn tile_rows_mut(&mut self, band_tile_rows: usize) -> (usize, Vec<(usize, &mut [Option<Arc<Vec<u32>>>])>) {
        let columns = self.columns;
        let bands = self
            .tiles
            .chunks_mut(band_tile_rows * columns)
            .enumerate()
            .map(|(band, slots)| (band * band_tile_rows, slots))
            .collect();
        (columns, bands)
    }

    fn locate(&self, index: usize) -> (usize, usize) {
        let (x, y) = (index % self.width, index / self.width);
        ((y / TILE) * self.columns + x / TILE, (y % TILE) * TILE + x % TILE)
    }
}

impl Index<usize> for Tiles {
    type Output = u32;
    fn index(&self, index: usize) -> &u32 {
        let (t, off) = self.locate(index);
        match &self.tiles[t] {
            Some(tile) => &tile[off],
            None => &ZERO,
        }
    }
}

impl IndexMut<usize> for Tiles {
    fn index_mut(&mut self, index: usize) -> &mut u32 {
        let (t, off) = self.locate(index);
        self.generation = fresh_generation();
        if self.tiles[t].is_none() {
            self.tiles[t] = Some(Arc::new(vec![0u32; TILE_LEN]));
            self.allocated += 1;
        }
        &mut Arc::make_mut(self.tiles[t].as_mut().unwrap())[off]
    }
}

impl std::fmt::Debug for Tiles {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Tiles({}x{}, {} tiles allocated)", self.width, self.height, self.allocated)
    }
}

impl PartialEq<Vec<u32>> for Tiles {
    fn eq(&self, other: &Vec<u32>) -> bool {
        self.len() == other.len() && self.iter().zip(other.iter()).all(|(a, b)| a == *b)
    }
}

impl From<Tiles> for Vec<u32> {
    fn from(tiles: Tiles) -> Vec<u32> {
        tiles.to_vec()
    }
}

impl PartialEq for Tiles {
    fn eq(&self, other: &Self) -> bool {
        self.width == other.width && self.height == other.height && self.to_vec() == other.to_vec()
    }
}

#[derive(serde::Serialize, serde::Deserialize)]
struct Repr {
    width: usize,
    height: usize,
    data: Vec<u32>,
}

impl serde::Serialize for Tiles {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        Repr { width: self.width, height: self.height, data: self.to_vec() }.serialize(serializer)
    }
}

impl<'de> serde::Deserialize<'de> for Tiles {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let repr = Repr::deserialize(deserializer)?;
        if repr.data.len() != repr.width * repr.height {
            return Err(serde::de::Error::custom("layer pixel count does not match its canvas"));
        }
        Ok(Tiles::from_flat(&repr.data, repr.width, repr.height))
    }
}
