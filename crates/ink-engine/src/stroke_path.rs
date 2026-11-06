use crate::{finite, Brush, Sample};

pub fn diameter(brush: Brush, p: Sample) -> f32 {
    brush.size * (1.0 - brush.pressure_size
        + brush.pressure_size * finite(p.pressure, 1.0).clamp(0.0, 1.0).powf(0.7)).max(0.08)
}

/// Spacing for a brush whose image tip sets its own, as a fraction of the diameter (0 = default).
pub fn spacing_with(brush: Brush, p: Sample, tip_spacing: f32) -> f32 {
    if tip_spacing > 0.0 && brush.kind != 6 && brush.kind < 128 {
        return (diameter(brush, p) * tip_spacing).max(0.25);
    }
    if tip_spacing > 0.0 { return (brush.size * tip_spacing).max(0.65); }
    spacing(brush, p)
}

pub fn spacing(brush: Brush, p: Sample) -> f32 {
    let soft = matches!(crate::brush_shape(brush.kind), 2 | 4 | 8 | 9);
    if brush.kind==6 || brush.kind>=128 {return (brush.size * if soft {0.035} else {0.055}).max(0.65);}
    (diameter(brush, p) * if soft { 0.022 } else { 0.055 }).max(0.25)
}

pub fn deposition(brush: Brush, p: Sample) -> f32 {
    if brush.kind!=6 && brush.kind<128 && matches!(crate::brush_shape(brush.kind), 2 | 4 | 8 | 9) {
        spacing(brush, p) / (diameter(brush, p) * 0.035).max(0.25)
    } else { 1.0 }
}

pub fn coverage(alpha: f32, exponent: f32) -> f32 {
    if exponent==1.0{return alpha;}
    // Soft tips stay below 0.2 coverage: the cubic avoids a pow per covered pixel.
    if alpha <= 0.2 {
        exponent * alpha * (1.0 + (1.0 - exponent) * alpha * 0.5
            + (1.0 - exponent) * (2.0 - exponent) * alpha * alpha / 6.0)
    } else { 1.0 - (1.0 - alpha).powf(exponent) }
}

pub fn filter(previous: Sample, mut p: Sample, dt: f32, speed: f32, stabilization: f32, timed: bool) -> Sample {
    let tau = 42.0 * stabilization * stabilization / (1.0 + speed / 0.7);
    let position = if tau > 0.001 { 1.0 - (-dt / tau).exp() } else { 1.0 };
    p.x = previous.x + (p.x - previous.x) * position;
    p.y = previous.y + (p.y - previous.y) * position;
    let response = if timed { 1.0 - (-dt / 7.0).exp() } else { 1.0 };
    p.pressure = previous.pressure + (finite(p.pressure, 1.0).clamp(0.0, 1.0) - previous.pressure) * response;
    p.tilt = previous.tilt + (finite(p.tilt, 0.0).clamp(0.0, 1.57) - previous.tilt) * response;
    p
}

// A causal cubic uses the previous tangent without waiting for another pen event.
// Limit its handle at corners so a reversal cannot create a loop or long hook.
pub fn curve(a: Sample, b: Sample, previous_delta: [f32; 2]) -> Vec<Sample> {
    let delta = [b.x - a.x, b.y - a.y];
    let length = delta[0].hypot(delta[1]);
    let previous_length = previous_delta[0].hypot(previous_delta[1]);
    if length < 0.001 || previous_length < 0.001 { return vec![b]; }
    let dot = (delta[0] * previous_delta[0] + delta[1] * previous_delta[1]) / (length * previous_length);
    if dot < 0.25 || dot > 0.9999 { return vec![b]; }
    let handle = length.min(previous_length) / 3.0;
    let c = [a.x + previous_delta[0] / previous_length * handle,
             a.y + previous_delta[1] / previous_length * handle];
    let d = [b.x - delta[0] / 3.0, b.y - delta[1] / 3.0];
    let mut out = Vec::new();
    subdivide([[a.x, a.y], c, d, [b.x, b.y]], 0.0, 1.0, a, b, 0, &mut out);
    out
}

fn subdivide(points: [[f32; 2]; 4], t0: f32, t1: f32, a: Sample, b: Sample, depth: u8, out: &mut Vec<Sample>) {
    let [p, c, d, q] = points;
    let dx = q[0] - p[0]; let dy = q[1] - p[1];
    let length = dx.hypot(dy).max(0.001);
    let error = |r: [f32; 2]| ((r[0] - p[0]) * dy - (r[1] - p[1]) * dx).abs() / length;
    if depth == 8 || error(c).max(error(d)) <= 0.15 {
        out.push(Sample { x: q[0], y: q[1], pressure: a.pressure + (b.pressure - a.pressure) * t1,
            tilt: a.tilt + (b.tilt - a.tilt) * t1 });
        return;
    }
    let mid = |x: [f32; 2], y: [f32; 2]| [(x[0] + y[0]) * 0.5, (x[1] + y[1]) * 0.5];
    let pc = mid(p, c); let cd = mid(c, d); let dq = mid(d, q);
    let left = mid(pc, cd); let right = mid(cd, dq); let center = mid(left, right);
    let tm = (t0 + t1) * 0.5;
    subdivide([p, pc, left, center], t0, tm, a, b, depth + 1, out);
    subdivide([center, right, dq, q], tm, t1, a, b, depth + 1, out);
}
