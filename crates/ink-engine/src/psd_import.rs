use super::*;
use std::io::Read;
struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}
impl<'a> Reader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, at: 0 }
    }
    fn remaining(&self) -> usize {
        self.data.len() - self.at
    }
    fn bytes(&mut self, n: usize) -> Result<&'a [u8], String> {
        if n > self.remaining() {
            return Err("Truncated PSD data".into());
        }
        let a = self.at;
        self.at += n;
        Ok(&self.data[a..self.at])
    }
    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.bytes(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, String> {
        Ok(u16::from_be_bytes(self.bytes(2)?.try_into().unwrap()))
    }
    fn i16(&mut self) -> Result<i16, String> {
        Ok(self.u16()? as i16)
    }
    fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_be_bytes(self.bytes(4)?.try_into().unwrap()))
    }
    fn i32(&mut self) -> Result<i32, String> {
        Ok(self.u32()? as i32)
    }
    fn section(&mut self) -> Result<Self, String> {
        let n = self.u32()? as usize;
        Ok(Self::new(self.bytes(n)?))
    }
    fn rect(&mut self) -> Result<[i32; 4], String> {
        Ok([self.i32()?, self.i32()?, self.i32()?, self.i32()?])
    }
}
fn dimensions(r: [i32; 4]) -> Result<(usize, usize), String> {
    let w = r[3] as i64 - r[1] as i64;
    let h = r[2] as i64 - r[0] as i64;
    if w < 0 || h < 0 || w > 8192 || h > 8192 || w * h > MAX_PIXELS as i64 {
        return Err("PSD layer bounds exceed the pixel limit".into());
    }
    Ok((w as usize, h as usize))
}
fn unpack(block: &[u8], w: usize, h: usize) -> Result<Vec<u8>, String> {
    let count = w.checked_mul(h).ok_or("PSD channel overflow")?;
    if count > MAX_PIXELS * 4 {
        return Err("PSD channel too large".into());
    }
    let mut r = Reader::new(block);
    let compression = r.u16()?;
    match compression {
        0 => {
            if r.remaining() != count {
                return Err("Invalid raw PSD channel length".into());
            }
            Ok(r.bytes(count)?.to_vec())
        }
        1 => {
            let mut lengths = Vec::with_capacity(h);
            for _ in 0..h {
                lengths.push(r.u16()? as usize);
            }
            let mut out = Vec::with_capacity(count);
            for length in lengths {
                let mut row = Reader::new(r.bytes(length)?);
                let start = out.len();
                while row.remaining() > 0 {
                    let n = row.u8()? as i8;
                    match n {
                        0..=127 => {
                            let size = n as usize + 1;
                            if out.len() - start + size > w {
                                return Err("PSD RLE row overflow".into());
                            }
                            out.extend_from_slice(row.bytes(size)?);
                        }
                        -127..=-1 => {
                            let size = (1 - n as i16) as usize;
                            if out.len() - start + size > w {
                                return Err("PSD RLE run overflow".into());
                            }
                            let v = row.u8()?;
                            out.resize(out.len() + size, v);
                        }
                        _ => {}
                    }
                }
                if out.len() - start != w {
                    return Err("Incomplete PSD RLE row".into());
                }
            }
            if r.remaining() != 0 {
                return Err("Unexpected PSD RLE data".into());
            }
            Ok(out)
        }
        2 | 3 => {
            let decoder = flate2::read::ZlibDecoder::new(r.bytes(r.remaining())?);
            let mut out = Vec::new();
            decoder
                .take(count as u64 + 1)
                .read_to_end(&mut out)
                .map_err(|_| "Invalid ZIP-compressed PSD channel")?;
            if out.len() != count {
                return Err("Invalid ZIP PSD output length".into());
            }
            if compression == 3 {
                for row in out.chunks_exact_mut(w.max(1)) {
                    for x in 1..row.len() {
                        row[x] = row[x].wrapping_add(row[x - 1]);
                    }
                }
            }
            Ok(out)
        }
        _ => Err("Unsupported PSD compression".into()),
    }
}
struct Record {
    rect: [i32; 4],
    channels: Vec<(i16, usize)>,
    name: String,
    opacity: f32,
    visible: bool,
    clipping: bool,
    alpha_lock: bool,
    locked: bool,
    blend: String,
    mask: Option<([i32; 4], u8, u8)>,
    own_background: bool,
}
fn mode(key: &[u8]) -> Result<&'static str, String> {
    Ok(match key {
        b"norm" => "Normal",
        b"mul " => "Multiply",
        b"idiv" => "Colour Burn",
        b"lbrn" => "Linear Burn",
        b"div " => "Colour Dodge",
        b"fsub" => "Subtract",
        b"smud" => "Exclusion",
        b"hLit" => "Hard Light",
        b"scrn" => "Screen",
        b"over" => "Overlay",
        b"lddg" => "Add",
        b"dkCl" => "Darker Colour",
        b"dark" => "Darken",
        b"lite" => "Lighten",
        b"diff" => "Difference",
        _ => {
            return Err(format!(
                "PSD blend mode '{}' is not supported",
                String::from_utf8_lossy(key)
            ))
        }
    })
}
fn warn(warnings: &mut Vec<String>, s: &str) {
    if !warnings.iter().any(|v| v == s) {
        warnings.push(s.into());
    }
}
impl Engine {
    pub fn read_psd(data: &[u8]) -> Result<(Self, Vec<String>), String> {
        if data.len() > 128 * 1024 * 1024 {
            return Err("PSD files must be smaller than 128 MiB".into());
        }
        let mut r = Reader::new(data);
        if r.bytes(4)? != b"8BPS" || r.u16()? != 1 {
            return Err("Only standard PSD files are supported; PSB is not supported".into());
        }
        if r.bytes(6)?.iter().any(|b| *b != 0) {
            return Err("Invalid PSD header".into());
        }
        let channel_count = r.u16()? as usize;
        let h = r.u32()? as usize;
        let w = r.u32()? as usize;
        if r.u16()? != 8 || r.u16()? != 3 {
            return Err("PSD import currently supports 8-bit RGB documents only".into());
        }
        if !(3..=56).contains(&channel_count) {
            return Err("Invalid PSD channel count".into());
        }
        let mut e = Engine::new(w, h)?;
        e.state.background_visible = false;
        let mut warnings = vec![];
        r.section()?;
        let mut resources = r.section()?;
        while resources.remaining() > 0 {
            if resources.bytes(4)? != b"8BIM" {
                return Err("Invalid PSD resource".into());
            }
            let id = resources.u16()?;
            let name = resources.u8()? as usize;
            resources.bytes(name)?;
            if (name + 1) % 2 != 0 {
                resources.bytes(1)?;
            }
            let len = resources.u32()? as usize;
            let bytes = resources.bytes(len)?;
            if len % 2 != 0 {
                resources.bytes(1)?;
            }
            if id == 1005 && len >= 4 {
                e.dpi = (u32::from_be_bytes(bytes[..4].try_into().unwrap()) >> 16).clamp(1, 2400);
            }
            if id == 1039
                && bytes != include_bytes!("srgb.icc")
                && !bytes.windows(4).any(|v| v == b"sRGB")
                && !bytes
                    .windows(8)
                    .any(|v| v == b"\0s\0R\0G\0B" || v == b"s\0R\0G\0B\0")
            {
                warn(
                    &mut warnings,
                    "The embedded color profile was not converted; RGB values are treated as sRGB.",
                );
            }
        }
        let mut layer_mask = r.section()?;
        let mut records = vec![];
        if layer_mask.remaining() > 0 {
            let mut info = layer_mask.section()?;
            if info.remaining() > 0 {
                let count = info.i16()?.unsigned_abs() as usize;
                if count > 65 {
                    return Err("PSD has too many layers".into());
                }
                for _ in 0..count {
                    let rect = info.rect()?;
                    dimensions(rect)?;
                    let count = info.u16()? as usize;
                    if count > 56 {
                        return Err("Invalid PSD layer channels".into());
                    }
                    let mut channels = vec![];
                    let mut ids = std::collections::HashSet::new();
                    for _ in 0..count {
                        let id = info.i16()?;
                        let len = info.u32()? as usize;
                        if !ids.insert(id) {
                            return Err("Duplicate PSD channel".into());
                        }
                        channels.push((id, len));
                    }
                    if info.bytes(4)? != b"8BIM" {
                        return Err("Invalid PSD layer record".into());
                    }
                    let blend = mode(info.bytes(4)?)?.to_string();
                    let opacity = info.u8()? as f32 / 255.0;
                    let clipping = info.u8()? != 0;
                    let flags = info.u8()?;
                    info.u8()?;
                    let mut extra = info.section()?;
                    let mut mask_data = extra.section()?;
                    let mut mask = None;
                    if mask_data.remaining() > 0 {
                        let bounds = mask_data.rect()?;
                        dimensions(bounds)?;
                        let default = mask_data.u8()?;
                        let flags = mask_data.u8()?;
                        if flags & 16 != 0 {
                            warn(&mut warnings,"Mask density/feather parameters are not applied; the stored mask pixels are retained.");
                        }
                        mask = Some((bounds, default, flags));
                    }
                    extra.section()?;
                    let name_len = extra.u8()? as usize;
                    let mut name = String::from_utf8_lossy(extra.bytes(name_len)?).into_owned();
                    extra.bytes((4 - (name_len + 1) % 4) % 4)?;
                    let mut locked = false;
                    let mut alpha_lock = flags & 1 != 0;
                    let mut own_background = false;
                    while extra.remaining() >= 12 {
                        let signature = extra.bytes(4)?;
                        if signature != b"8BIM" {
                            break;
                        }
                        let key = extra.bytes(4)?;
                        let len = extra.u32()? as usize;
                        let bytes = extra.bytes(len)?;
                        if len % 2 != 0 {
                            extra.bytes(1)?;
                        }
                        match key{
                            b"luni"=>{let mut u=Reader::new(bytes);let n=u.u32()? as usize;if n>10000 || n>u.remaining()/2{return Err("Invalid PSD Unicode layer name".into());}let mut units=vec![];for _ in 0..n{units.push(u.u16()?);}name=String::from_utf16_lossy(&units);},
                            b"lyid" if len==4=>own_background=bytes==u32::MAX.to_be_bytes(),
                            b"lspf" if len==4=>{let value=u32::from_be_bytes(bytes.try_into().unwrap());locked=value&6!=0;alpha_lock|=value&1!=0;},
                            b"lsct"|b"lsdk" if len>=4=>{if u32::from_be_bytes(bytes[..4].try_into().unwrap())!=0{return Err("Ungroup PSD layer groups in the source editor before importing".into());}},
                            b"TySh"|b"SoLd"|b"SoLE"=>warn(&mut warnings,"Text and smart objects are imported as their stored raster pixels, without editable text or smart-object data."),
                            b"levl"|b"curv"|b"hue2"|b"brit"|b"blnc"|b"vibA"|b"lfx2"|b"lrFX"|b"vmsk"|b"vsms"=>return Err("Rasterize PSD adjustments, effects and vector masks in the source editor before importing".into()),_=>{}
                        }
                    }
                    records.push(Record {
                        rect,
                        channels,
                        name: name.chars().take(100).collect(),
                        opacity,
                        visible: flags & 2 == 0,
                        clipping,
                        alpha_lock,
                        locked,
                        blend,
                        mask,
                        own_background,
                    });
                }
                let mut layers = vec![];
                let mut allocated = 0usize;
                for record in records {
                    let (lw, lh) = dimensions(record.rect)?;
                    let size = w * h;
                    let estimate = size * 4
                        + if record.channels.iter().any(|(id, _)| *id == -2) {
                            size
                        } else {
                            0
                        };
                    if !record.own_background
                        && (allocated + estimate > PIXEL_BUDGET * 4 || layers.len() >= 64)
                    {
                        return Err("PSD exceeds the layer memory budget".into());
                    }
                    let mut pixels = vec![0u32; size];
                    let mut mask = None;
                    let alpha_present = record.channels.iter().any(|(id, _)| *id == -1);
                    let rgb_present = record.channels.iter().any(|(id, _)| *id == 0);
                    if !alpha_present && rgb_present {
                        for y in 0..lh {
                            for x in 0..lw {
                                let xx = record.rect[1] as i64 + x as i64;
                                let yy = record.rect[0] as i64 + y as i64;
                                if xx >= 0 && yy >= 0 && xx < w as i64 && yy < h as i64 {
                                    pixels[yy as usize * w + xx as usize] = 0xff000000;
                                }
                            }
                        }
                    }
                    for (id, len) in record.channels {
                        let bytes = info.bytes(len)?;
                        if !matches!(id, -2..=2) {
                            continue;
                        }
                        if id == -2 {
                            let (mut bounds, default, flags) =
                                record.mask.ok_or("PSD mask channel has no bounds")?;
                            let (mw, mh) = dimensions(bounds)?;
                            let values = unpack(bytes, mw, mh)?;
                            if flags & 1 != 0 {
                                bounds[0] = bounds[0]
                                    .checked_add(record.rect[0])
                                    .ok_or("PSD mask position overflow")?;
                                bounds[1] = bounds[1]
                                    .checked_add(record.rect[1])
                                    .ok_or("PSD mask position overflow")?;
                            }
                            let mut out = vec![
                                if flags & 4 != 0 {
                                    255 - default
                                } else {
                                    default
                                };
                                size
                            ];
                            for y in 0..mh {
                                for x in 0..mw {
                                    let xx = bounds[1] as i64 + x as i64;
                                    let yy = bounds[0] as i64 + y as i64;
                                    if xx >= 0 && yy >= 0 && xx < w as i64 && yy < h as i64 {
                                        let v = values[y * mw + x];
                                        out[yy as usize * w + xx as usize] =
                                            if flags & 4 != 0 { 255 - v } else { v };
                                    }
                                }
                            }
                            mask = Some(Arc::new(out));
                        } else {
                            let values = unpack(bytes, lw, lh)?;
                            let shift = match id {
                                -1 => 24,
                                0 => 16,
                                1 => 8,
                                _ => 0,
                            };
                            for y in 0..lh {
                                for x in 0..lw {
                                    let xx = record.rect[1] as i64 + x as i64;
                                    let yy = record.rect[0] as i64 + y as i64;
                                    if xx >= 0 && yy >= 0 && xx < w as i64 && yy < h as i64 {
                                        let p = &mut pixels[yy as usize * w + xx as usize];
                                        *p = (*p & !(255 << shift))
                                            | ((values[y * lw + x] as u32) << shift);
                                    }
                                }
                            }
                        }
                    }
                    if record.own_background
                        && record.name == "Background colour"
                        && record.opacity == 1.0
                        && record.blend == "Normal"
                        && mask.is_none()
                        && pixels.iter().all(|p| *p == pixels[0])
                    {
                        e.state.background = pixels[0];
                        e.state.background_visible = record.visible;
                        continue;
                    }
                    allocated += size * 4 + mask.as_ref().map_or(0, |m| m.len());
                    if allocated > PIXEL_BUDGET * 4 || layers.len() >= 64 {
                        return Err("PSD exceeds the layer memory budget".into());
                    }
                    let id = layers.len() as u32 + 1;
                    layers.push(Layer {exterior:Default::default(),group_id:None,group_visible:true,group_locked:false,
                        id,
                        name: record.name,
                        visible: record.visible,
                        opacity: record.opacity,
                        blend: record.blend,
                        alpha_lock: record.alpha_lock,
                        locked: record.locked,
                        clipping: record.clipping,
                        mask_enabled: record.mask.is_none_or(|(_, _, flags)| flags & 2 == 0),
                        mask,
                        pixels: Arc::new(pixels),
                    });
                }
                if !layers.is_empty() {
                    layers[0].clipping = false;
                    e.state.layers = layers;
                    e.state.active = e.state.layers.len() - 1;
                    e.next_id = e.state.layers.len() as u32 + 1;
                    return Ok((e, warnings));
                }
            }
        }
        if channel_count > 4 {
            return Err("Flattened PSD files with additional channels are not supported".into());
        }
        let merged = unpack(r.bytes(r.remaining())?, w, h * channel_count)?;
        let pixels = Arc::make_mut(&mut e.state.layers[0].pixels);
        for (i, p) in pixels.iter_mut().enumerate() {
            let a = if channel_count == 4 {
                merged[3 * w * h + i] as u32
            } else {
                255
            };
            let mut c = 0;
            for (k, shift) in [16, 8, 0].into_iter().enumerate() {
                let v = merged[k * w * h + i] as u32;
                let v = if a == 0 {
                    0
                } else {
                    ((v as i32 - (255 - a) as i32).max(0) as u32 * 255 / a).min(255)
                };
                c |= v << shift;
            }
            *p = c | (a << 24);
        }
        warn(
            &mut warnings,
            "This PSD has no editable layer records; its merged image was imported as one layer.",
        );
        Ok((e, warnings))
    }
}
