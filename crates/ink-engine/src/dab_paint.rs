//! Per-pixel dab arithmetic, shared by the serial stamp loop and the parallel band path.
//!
//! Every pixel of a dab depends only on its own coordinates and its own destination value, so
//! the same code gives identical pixels whether it runs on one thread or on row bands.

use super::*;
use std::sync::atomic::AtomicUsize;

/// Dabs covering at least this many pixels are split across row bands. Tests lower or raise it.
pub(super) static PARALLEL_MIN_PIXELS: AtomicUsize = AtomicUsize::new(16_384);

pub(super) struct DabPaint<'a> {
    pub(super) p: Sample,
    pub(super) radius: f32,
    pub(super) brush: Brush,
    /// The dab colour after effects; the colour used for compositing when no effect applies.
    pub(super) color: u32,
    /// The stroke's base colour, which the colour-cycle effects start from.
    pub(super) stroke_color: u32,
    pub(super) shape: u8,
    pub(super) strands: bool,
    pub(super) direction: [f32; 2],
    pub(super) seed: u32,
    pub(super) effects: &'a brush_color::BrushColor,
    pub(super) stamp_distance: f32,
    pub(super) carried_color: u32,
    pub(super) lock: bool,
    pub(super) strength: f32,
    pub(super) deposition: f32,
    pub(super) selected: Option<&'a [bool]>,
    pub(super) selection_alpha: Option<&'a [u8]>,
    pub(super) contact: Option<&'a brush_color::ContactField>,
}

/// Coverage of one pixel before it is blended into the destination.
pub(super) struct Shade {
    pub(super) dx: f32,
    pub(super) dy: f32,
    pub(super) d: f32,
    pub(super) edge: f32,
    pub(super) texture: f32,
    pub(super) color: u32,
    pub(super) alpha: f32,
}

impl DabPaint<'_> {
    /// Coverage at pixel (x, y). `inside` pixels are checked against the selection. `under` is the
    /// layer's current value at `idx` (the wet-mix reference on the strand path).
    pub(super) fn shade(&self, x: i32, y: i32, idx: usize, inside: bool, under: u32) -> Option<Shade> {
        if inside && self.selected.is_some_and(|s| !s[idx]) {
            return None;
        }
        let (radius, shape, strands) = (self.radius, self.shape, self.strands);
        let (dir, effects) = (self.direction, self.effects);
        let dx = x as f32 + 0.5 - self.p.x;
        let dy = y as f32 + 0.5 - self.p.y;
        let d = (dx * dx + dy * dy).sqrt() / radius.max(0.1);
        if d > 1.0 + 0.7 / radius {
            return None;
        }
        let edge = ((1.0 - d) * radius + 0.5).clamp(0.0, 1.0);
        let tip_x = if strands || matches!(shape, 11 | 42) { -dir[1] * dx + dir[0] * dy } else { dx };
        let tip_y = if strands || matches!(shape, 11 | 42) { dir[0] * dx + dir[1] * dy } else { dy };
        let texture = match shape {
            1 => { let n = noise(x as u32, y as u32, 91); 0.16 + n * 0.58 },
            2 => ((-4.0 * d * d).exp() - (-4.0f32).exp()).max(0.0) / (1.0 - (-4.0f32).exp()) * 0.12,
            8 | 9 => { let hard = if shape == 8 { 0.35 } else { 0.7 }; let t = ((1.0 - d) / (1.0 - hard)).clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) * 0.18 },
            10 => if noise(x as u32, y as u32, self.seed) > 0.965 { 0.65 * (1.0 - d).max(0.0) } else { 0.0 },
            4 | 11 | 32..=42 => brush_texture::sample(shape, x as f32, y as f32, tip_x, tip_y, radius, d),
            12 => if tip_y.abs() < radius * 0.48 { 0.2 } else { 0.0 },
            13 => noise(x as u32, y as u32, 19).powf(2.0) * 0.38,
            14 => (0.1 + noise(x as u32, y as u32, 73) * 0.3) * (1.0 - d * d).max(0.0),
            3 => noise(x as u32, y as u32, self.seed / 15).powf(1.8) * 0.7,
            16..=31 => { let hard = (shape - 16) as f32 / 15.0 * 0.95; let t = ((1.0 - d) / (1.0 - hard)).clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) },
            6 => 0.11,
            7 => if (if strands { tip_y } else { dx }).abs() < radius * 0.32 { 0.85 } else { 0.0 },
            _ => 1.0,
        };
        let texture = if strands && effects.layout == 2 {
            let lateral = (-dir[1] * dx + dir[0] * dy) / radius;
            texture * (0.52 + 0.48 * (lateral * 37.0).sin().abs())
        } else {
            texture
        };
        let color = if strands {
            let lateral = (-dir[1] * dx + dir[0] * dy) / radius;
            effects.at(self.stroke_color, self.stamp_distance, lateral)
        } else {
            self.color
        };
        // Wet mixing of the dab with the paint already on the layer, when the colour is not carried.
        let color = if strands && effects.mix > 0.0 {
            if effects.pickup > 0.0 || effects.wetness > 0.0 {
                brush_color::wet_mix(color, self.carried_color, effects.mix)
            } else {
                brush_color::wet_mix(color, under, effects.mix)
            }
        } else {
            color
        };
        let color = self.contact.map_or(color, |field| field.at(dx / radius.max(0.1), dy / radius.max(0.1)));
        let kind = self.brush.kind;
        let hardness = if (64..=127).contains(&kind) && self.color & 255 < 255 {
            let hard = (self.color & 255) as f32 / 255.0 * 0.95;
            let t = ((1.0 - d) / (1.0 - hard)).clamp(0.0, 1.0);
            t * t * (3.0 - 2.0 * t)
        } else {
            1.0
        };
        let wet_edge = if kind < 64 && effects.mix > 0.0 { 1.0 - effects.wetness * 0.45 * d.clamp(0.0, 1.0).powi(2) } else { 1.0 };
        let coverage = (self.strength * edge * texture * wet_edge * hardness).clamp(0.0, 1.0);
        let alpha = stroke_path::coverage(coverage, self.deposition)
            * self.selection_alpha.map_or(1.0, |a| a[idx] as f32 / 255.0);
        Some(Shade { dx, dy, d, edge, texture, color, alpha })
    }

    /// New value for a pixel with destination `dst`, or None when the dab leaves it unchanged.
    pub(super) fn blend(&self, shade: &Shade, dst: u32) -> Option<u32> {
        let (kind, color, alpha) = (self.brush.kind, shade.color, shade.alpha);
        if kind == 5 || (16..=31).contains(&kind) || (64..=127).contains(&kind) {
            if self.lock {
                return None;
            }
            let a = channel(dst, 24) * (1.0 - alpha);
            Some((dst & 0x00ffffff) | (((a * 255.0).round() as u32) << 24))
        } else if self.lock {
            if dst >> 24 != 0 {
                let c = composite(dst | 0xff000000, color | 0xff000000, alpha, "Normal");
                Some((c & 0x00ffffff) | (dst & 0xff000000))
            } else {
                None
            }
        } else {
            Some(composite(dst, color, alpha, "Normal"))
        }
    }

    /// Full per-pixel result for pixels inside the canvas.
    pub(super) fn pixel(&self, x: i32, y: i32, idx: usize, dst: u32) -> Option<u32> {
        let shade = self.shade(x, y, idx, true, dst)?;
        self.blend(&shade, dst)
    }
}
