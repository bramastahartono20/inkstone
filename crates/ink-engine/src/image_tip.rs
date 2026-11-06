//! Image brush tips: a grayscale shape stamped at every dab and an optional grain texture
//! anchored to the canvas. White paints, black leaves the canvas untouched.
//!
//! Brush kind 43 uses the tip set with the `brushImage` command. Erase (107) and smudge (171)
//! use it the same way. Without a tip, kind 43 paints a hard round dab.

use serde_json::Value;

pub const KIND: u8 = 43;
const MAX_SHAPE: usize = 512;
const MAX_GRAIN: usize = 1024;

static NEXT_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

pub struct ImageTip {
    /// Unique per parsed tip, so the GPU knows when to upload again.
    pub id: u64,
    pub key: String,
    pub size: usize,
    pub shape: Vec<u8>,
    pub grain_size: usize,
    pub grain: Vec<u8>,
    /// Canvas pixels covered by one grain texel.
    pub grain_scale: f32,
    /// Distance between dabs as a fraction of the diameter. 0 uses the round-brush spacing.
    pub spacing: f32,
    /// How far the shape turns with the stroke direction, from -1 to 1.
    pub follow: f32,
    /// Fixed rotation in radians.
    pub rotation: f32,
    /// Random rotation per dab, from 0 (none) to 1 (any angle).
    pub jitter: f32,
    /// Random shrinking per dab, from 0 (none) to 1 (down to nothing).
    pub size_jitter: f32,
    /// Random fading per dab, from 0 (none) to 1 (down to clear).
    pub opacity_jitter: f32,
    /// Random sideways offset per dab, in diameters (0 to 3).
    pub scatter: f32,
    /// Rolling grain moves along the stroke like a ribbon, `grain_scale` brush sizes per tile.
    /// Otherwise the grain is fixed to the canvas, `grain_scale` canvas pixels per texel.
    pub rolling: bool,
    /// How much a fast stroke fades, from 0 (not at all) to 1 (clear at full speed).
    pub speed_opacity: f32,
    /// How much a fast stroke thins, from -1 (thickens) to 1 (thins).
    pub speed_size: f32,
    /// Taper lengths at the start and end of a stroke, in units of 8 × the brush size (at least 400 px).
    pub taper_start: f32,
    pub taper_end: f32,
    /// How far the tapered ends shrink and fade, from 0 (not at all) to 1 (to nothing).
    pub taper_size: f32,
    pub taper_opacity: f32,
    /// Pen tilt beyond `tilt_angle` (a fraction of 90°) grows the dab by up to 2.5 × and fades it.
    pub tilt_size: f32,
    pub tilt_opacity: f32,
    pub tilt_angle: f32,
    /// How the stroke combines with the paint under it: 0 Normal, 1 Multiply, 2 Screen, 3 Add.
    pub blend: u8,
    /// Pressure responses for size and opacity.
    pub size_curve: Curve,
    pub opacity_curve: Curve,
}

/// A pressure response: piecewise linear through points sorted by x, from (0, 0) to (1, 1) by default.
#[derive(Clone, Default)]
pub struct Curve(Vec<[f32; 2]>);

impl Curve {
    fn parse(v: &Value) -> Self {
        let mut points: Vec<[f32; 2]> = v.as_array().map_or(vec![], |a| a.iter().take(32).filter_map(|p| {
            let (x, y) = (p[0].as_f64()? as f32, p[1].as_f64()? as f32);
            (x.is_finite() && y.is_finite()).then(|| [x.clamp(0.0, 1.0), y.clamp(0.0, 1.0)])
        }).collect());
        points.sort_by(|a, b| a[0].total_cmp(&b[0]));
        Curve(points)
    }
    pub fn at(&self, x: f32) -> f32 {
        let p = &self.0;
        if p.len() < 2 { return x; }
        if x <= p[0][0] { return p[0][1]; }
        for w in p.windows(2) {
            if x <= w[1][0] {
                let t = (x - w[0][0]) / (w[1][0] - w[0][0]).max(1e-6);
                return w[0][1] + (w[1][1] - w[0][1]) * t;
            }
        }
        p[p.len() - 1][1]
    }
}

/// How one dab varies: sideways offset in diameters, size factor and opacity factor.
pub struct Jitter { pub offset: f32, pub size: f32, pub opacity: f32 }

fn base64(text: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let (mut buffer, mut bits) = (0u32, 0u32);
    for c in text.bytes() {
        let value = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' | b'\n' | b'\r' | b' ' => continue,
            _ => return Err("Brush image is not valid base64".into()),
        } as u32;
        buffer = (buffer << 6) | value;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
        }
    }
    Ok(out)
}

fn square(v: &Value, data: &str, size: &str, max: usize) -> Result<(usize, Vec<u8>), String> {
    let Some(text) = v[data].as_str() else { return Ok((0, vec![])) };
    let n = v[size].as_u64().unwrap_or(0) as usize;
    if n == 0 || n > max {
        return Err(format!("Brush image must be 1 to {max} pixels wide"));
    }
    let bytes = base64(text)?;
    if bytes.len() != n * n {
        return Err("Brush image size does not match its pixels".into());
    }
    Ok((n, bytes))
}

fn bilinear(values: &[u8], n: usize, x: f32, y: f32, wrap: bool) -> f32 {
    let (fx, fy) = (x.floor(), y.floor());
    let (tx, ty) = (x - fx, y - fy);
    let at = |ix: i32, iy: i32| -> f32 {
        let (ix, iy) = if wrap {
            (ix.rem_euclid(n as i32), iy.rem_euclid(n as i32))
        } else {
            (ix.clamp(0, n as i32 - 1), iy.clamp(0, n as i32 - 1))
        };
        values[iy as usize * n + ix as usize] as f32 / 255.0
    };
    let (ix, iy) = (fx as i32, fy as i32);
    let top = at(ix, iy) + (at(ix + 1, iy) - at(ix, iy)) * tx;
    let bottom = at(ix, iy + 1) + (at(ix + 1, iy + 1) - at(ix, iy + 1)) * tx;
    top + (bottom - top) * ty
}

impl ImageTip {
    pub fn parse(v: &Value) -> Result<Self, String> {
        let n = |key: &str, default: f64, min: f32, max: f32| {
            let value = v[key].as_f64().unwrap_or(default) as f32;
            if value.is_finite() { value.clamp(min, max) } else { default as f32 }
        };
        let (size, shape) = square(v, "shape", "size", MAX_SHAPE)?;
        if size == 0 {
            return Err("Brush image needs a shape".into());
        }
        let (grain_size, grain) = square(v, "grain", "grainSize", MAX_GRAIN)?;
        Ok(Self {
            id: NEXT_ID.fetch_add(1, std::sync::atomic::Ordering::Relaxed),
            key: v["key"].as_str().unwrap_or("").chars().take(200).collect(),
            size,
            shape,
            grain_size,
            grain,
            grain_scale: n("grainScale", 1.0, 0.05, 16.0),
            spacing: n("spacing", 0.0, 0.0, 4.0),
            follow: n("follow", 0.0, -1.0, 1.0),
            rotation: n("rotation", 0.0, -7.0, 7.0),
            jitter: n("jitter", 0.0, 0.0, 1.0),
            size_jitter: n("sizeJitter", 0.0, 0.0, 1.0),
            opacity_jitter: n("opacityJitter", 0.0, 0.0, 1.0),
            scatter: n("scatter", 0.0, 0.0, 3.0),
            rolling: v["rolling"].as_bool().unwrap_or(false),
            speed_opacity: n("speedOpacity", 0.0, 0.0, 1.0),
            speed_size: n("speedSize", 0.0, -1.0, 1.0),
            taper_start: n("taperStart", 0.0, 0.0, 1.0),
            taper_end: n("taperEnd", 0.0, 0.0, 1.0),
            taper_size: n("taperSize", 0.0, 0.0, 1.0),
            taper_opacity: n("taperOpacity", 0.0, 0.0, 1.0),
            tilt_size: n("tiltSize", 0.0, 0.0, 1.0),
            tilt_opacity: n("tiltOpacity", 0.0, 0.0, 1.0),
            tilt_angle: n("tiltAngle", 0.0, 0.0, 0.9),
            blend: v["blend"].as_u64().unwrap_or(0).min(3) as u8,
            size_curve: Curve::parse(&v["sizeCurve"]),
            opacity_curve: Curve::parse(&v["opacityCurve"]),
        })
    }

    /// Dab angle for a stroke heading along `direction`, `distance` pixels from its start.
    pub fn angle(&self, direction: [f32; 2], distance: f32) -> f32 {
        let random = if self.jitter > 0.0 {
            (crate::noise(distance.to_bits(), 0x5eed, 613) - 0.5) * std::f32::consts::TAU * self.jitter
        } else {
            0.0
        };
        self.rotation + self.follow * direction[1].atan2(direction[0]) + random
    }

    /// Random variation of the dab `distance` pixels along the stroke. The same dab always varies the
    /// same way, so the CPU and GPU agree and replays match.
    pub fn jitter(&self, distance: f32) -> Jitter {
        let r = |seed| crate::noise(distance.to_bits(), 0x5eed, seed);
        Jitter {
            offset: if self.scatter > 0.0 { (r(271) - 0.5) * self.scatter } else { 0.0 },
            size: 1.0 - self.size_jitter * r(389) * 0.95,
            opacity: 1.0 - self.opacity_jitter * r(457),
        }
    }

    /// Whether the stroke's end tapers, which needs the stroke replayed once its length is known.
    pub fn tapers_end(&self) -> bool { self.taper_end > 0.0 && (self.taper_size > 0.0 || self.taper_opacity > 0.0) }

    /// The layer blend mode the stroke uses, or None for Normal.
    pub fn blend_mode(&self) -> Option<&'static str> {
        match self.blend { 1 => Some("Multiply"), 2 => Some("Screen"), 3 => Some("Add"), _ => None }
    }

    /// Size and opacity factors from taper, pen tilt and speed for a dab `distance` pixels along a
    /// stroke of base `size`. `end` is the stroke length once known; `fast` is speed from 0 to 1.
    pub fn dynamics(&self, distance: f32, end: Option<f32>, size: f32, tilt: f32, fast: Option<f32>) -> (f32, f32) {
        let (mut grow, mut fade) = (1.0f32, 1.0f32);
        let length = |fraction: f32| fraction * (size * 8.0).max(400.0);
        let mut ends = 1.0f32;
        if self.taper_start > 0.0 { ends = ends.min((distance / length(self.taper_start).max(0.1)).clamp(0.0, 1.0)); }
        if let (Some(end), true) = (end, self.taper_end > 0.0) {
            // A short stroke shares its length between both tapers.
            let room = length(self.taper_end).min(end * 0.5).max(0.1);
            ends = ends.min(((end - distance) / room).clamp(0.0, 1.0));
        }
        let ends = ends * ends * (3.0 - 2.0 * ends);
        grow *= (1.0 - self.taper_size * (1.0 - ends)).max(0.04);
        fade *= 1.0 - self.taper_opacity * (1.0 - ends);
        if self.tilt_size > 0.0 || self.tilt_opacity > 0.0 {
            let start = self.tilt_angle * std::f32::consts::FRAC_PI_2;
            let lean = ((tilt - start) / (std::f32::consts::FRAC_PI_2 - start).max(0.01)).clamp(0.0, 1.0);
            grow *= 1.0 + 1.5 * self.tilt_size * lean;
            fade *= 1.0 - 0.8 * self.tilt_opacity * lean;
        }
        if let (Some(fast), true) = (fast, self.speed_size != 0.0) { grow *= (1.0 - 0.8 * self.speed_size * fast).clamp(0.2, 2.0); }
        (grow, fade)
    }

    /// Size of one grain texel in canvas pixels when the grain rolls, or 0 when it is fixed to the canvas.
    pub fn rolling_texel(&self, brush_size: f32) -> f32 {
        if self.rolling && self.grain_size > 0 { (brush_size * self.grain_scale / self.grain_size as f32).max(0.01) } else { 0.0 }
    }

    /// Grain position, in texels, of the pixel centred at (`x`, `y`) and offset `d` from its dab.
    /// Rolling grain runs along the stroke `direction`, `distance` being the dab's place on it and
    /// `shift` the dab's scatter away from the stroke, so scattered dabs still read one ribbon.
    #[allow(clippy::too_many_arguments)]
    pub fn grain_at(&self, x: f32, y: f32, d: [f32; 2], direction: [f32; 2], distance: f32, texel: f32, shift: f32) -> [f32; 2] {
        if texel > 0.0 {
            let along = direction[0] * d[0] + direction[1] * d[1];
            let across = -direction[1] * d[0] + direction[0] * d[1] + shift;
            [(distance + along) / texel, across / texel + self.grain_size as f32 * 0.5]
        } else {
            [x / self.grain_scale, y / self.grain_scale]
        }
    }

    /// Coverage of a pixel offset (`dx`, `dy`) from a dab of `radius`, at grain position `grain`.
    pub fn coverage(&self, dx: f32, dy: f32, radius: f32, angle: f32, grain: [f32; 2]) -> f32 {
        let (s, c) = angle.sin_cos();
        let radius = radius.max(0.1);
        let u = (c * dx + s * dy) / radius;
        let v = (-s * dx + c * dy) / radius;
        if u.abs() >= 1.0 || v.abs() >= 1.0 {
            return 0.0;
        }
        let n = self.size as f32;
        let shape = bilinear(&self.shape, self.size, (u + 1.0) * 0.5 * n - 0.5, (v + 1.0) * 0.5 * n - 0.5, false);
        if self.grain.is_empty() || shape <= 0.0 {
            return shape;
        }
        shape * bilinear(&self.grain, self.grain_size, grain[0] - 0.5, grain[1] - 0.5, true)
    }

    /// Layout read by paint.wgsl: size, grain size and grain scale, then one word per texel.
    pub fn gpu_words(&self) -> Vec<u32> {
        let mut words = Vec::with_capacity(4 + self.shape.len() + self.grain.len());
        words.extend([self.size as u32, self.grain_size as u32, self.grain_scale.to_bits(), 0]);
        words.extend(self.shape.iter().map(|v| *v as u32));
        words.extend(self.grain.iter().map(|v| *v as u32));
        words
    }
}
