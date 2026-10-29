use super::*;
use std::io::{self, Write};

struct ExportLayer<'a> {
    name: &'a str,
    pixels: Option<&'a [u32]>,
    solid: u32,
    rect: [usize; 4],
    opacity: f32,
    visible: bool,
    clipping: bool,
    alpha_lock: bool,
    locked: bool,
    blend: &'a str,
    id: u32,
    mask:Option<&'a [u8]>,
    mask_enabled:bool,
}
fn u16be(out: &mut impl Write, n: u16) -> io::Result<()> {
    out.write_all(&n.to_be_bytes())
}
fn u32be(out: &mut impl Write, n: u32) -> io::Result<()> {
    out.write_all(&n.to_be_bytes())
}
fn block(out: &mut impl Write, key: &[u8; 4], data: &[u8]) -> io::Result<()> {
    out.write_all(b"8BIM")?;
    out.write_all(key)?;
    u32be(out, data.len() as u32)?;
    out.write_all(data)?;
    if data.len() % 2 != 0 {
        out.write_all(&[0])?;
    }
    Ok(())
}
fn blend(mode: &str) -> &'static [u8; 4] {
    match mode {
        "Multiply" => b"mul ",
        "Colour Burn" => b"idiv",
        "Linear Burn" => b"lbrn",
        "Colour Dodge" => b"div ",
        "Subtract" => b"fsub",
        "Exclusion" => b"smud",
        "Hard Light" => b"hLit",
        "Screen" => b"scrn",
        "Overlay" => b"over",
        "Add" => b"lddg",
        "Darker Colour" => b"dkCl",
        "Darken" => b"dark",
        "Lighten" => b"lite",
        "Difference" => b"diff",
        _ => b"norm",
    }
}
impl Engine {
    pub fn write_psd(&mut self, out: &mut impl Write) -> io::Result<()> {
        self.flush_gpu();
        let mut source=self.state.layers.clone();
        if let Some((index,before))=&self.flood_preview{source[*index].pixels=before.clone();}
        let mut export_layers=Vec::new();let mut seen=std::collections::HashSet::new();
        for layer in &source{
            let group=self.group_path(layer.group_id).into_iter().find(|g|g.blend!="Pass Through" || g.opacity<1.0);
            if let Some(g)=group{
                if !seen.insert(g.id){continue;}
                let members:Vec<_>=source.iter().filter(|l|self.in_group(l.group_id,g.id)).cloned().collect();
                let mut pixels=vec![0;self.width*self.height];
                for (index,pixel) in pixels.iter_mut().enumerate(){*pixel=self.composite_grouped_except(&members,0,Some(g.id),|l|(l.pixels[index],l.mask_coverage(index)));}
                let mut combined=layer.clone();combined.name=g.name.clone();combined.pixels=Arc::new(pixels);combined.group_id=None;combined.group_visible=true;combined.group_locked=false;combined.visible=g.visible;combined.locked=g.locked;combined.opacity=g.opacity;combined.blend=if g.blend=="Pass Through"{"Normal".into()}else{g.blend.clone()};combined.clipping=false;combined.mask=None;combined.alpha_lock=false;
                export_layers.push(combined);
            }else{export_layers.push(layer.clone());}
        }
        let mut layers = Vec::new();
        // PSD records are ordered bottom-to-top, matching the document's pixel stack.
        layers.push(ExportLayer {
            name: "Background colour",
            pixels: None,
            solid: self.state.background,
            rect: [0, 0, self.width, self.height],
            opacity: 1.0,
            visible: self.state.background_visible,
            clipping: false,
            alpha_lock: false,
            locked: false,
            blend: "Normal",
            id: u32::MAX,mask:None,mask_enabled:true,
        });
        for layer in &export_layers {
            let pixels=layer.pixels.as_slice();
            let mut rect = [self.width, self.height, 0, 0];
            for y in 0..self.height {
                for x in 0..self.width {
                    if pixels[y * self.width + x] >> 24 != 0 {
                        rect[0] = rect[0].min(x);
                        rect[1] = rect[1].min(y);
                        rect[2] = rect[2].max(x + 1);
                        rect[3] = rect[3].max(y + 1);
                    }
                }
            }
            if rect[2] == 0 {
                rect = [0; 4];
            }
            layers.push(ExportLayer {
                name: &layer.name,
                pixels: Some(pixels),
                solid: 0,
                rect,
                opacity: layer.opacity,
                visible: layer.is_visible(),
                clipping: layer.clipping,
                alpha_lock: layer.alpha_lock,
                locked: layer.is_locked(),
                blend: &layer.blend,
                id: layer.id,mask:layer.mask.as_deref().map(|m|m.as_slice()),mask_enabled:layer.mask_enabled,
            });
        }
        let mut records = Vec::new();
        let mut channel_bytes = 0usize;
        for layer in &layers {
            let [x, y, x1, y1] = layer.rect;
            let count = (x1 - x) * (y1 - y);
            channel_bytes += 4 * (count + 2);
            for n in [y, x, y1, x1] {
                u32be(&mut records, n as u32)?;
            }
            u16be(&mut records, if layer.mask.is_some(){5}else{4})?;
            for id in [-1i16, 0, 1, 2] {
                u16be(&mut records, id as u16)?;
                u32be(&mut records, (count + 2) as u32)?;
            }
            if layer.mask.is_some(){u16be(&mut records,(-2i16) as u16)?;u32be(&mut records,(self.width*self.height+2) as u32)?;channel_bytes+=self.width*self.height+2;}
            records.write_all(b"8BIM")?;
            records.write_all(blend(layer.blend))?;
            records.write_all(&[
                (layer.opacity * 255.0 + 0.5) as u8,
                layer.clipping as u8,
                (layer.alpha_lock as u8) | if layer.visible { 0 } else { 2 },
                0,
            ])?;
            let mut extra = Vec::new();
            if layer.mask.is_some(){u32be(&mut extra,20)?;for n in [0,0,self.height,self.width]{u32be(&mut extra,n as u32)?;}extra.extend([255,if layer.mask_enabled{0}else{2},0,0]);}else{u32be(&mut extra,0)?;}
            u32be(&mut extra, 0)?;
            let name: Vec<u8> = layer
                .name
                .chars()
                .take(255)
                .map(|c| if c.is_ascii() { c as u8 } else { b'?' })
                .collect();
            extra.push(name.len() as u8);
            extra.extend(&name);
            while extra.len() % 4 != 0 {
                extra.push(0);
            }
            let utf16: Vec<u16> = layer.name.encode_utf16().collect();
            let mut unicode = Vec::new();
            u32be(&mut unicode, utf16.len() as u32)?;
            for c in utf16 {
                u16be(&mut unicode, c)?;
            }
            block(&mut extra, b"luni", &unicode)?;
            block(&mut extra, b"lyid", &layer.id.to_be_bytes())?;
            block(
                &mut extra,
                b"lspf",
                &(if layer.locked {
                    7u32
                } else {
                    layer.alpha_lock as u32
                })
                .to_be_bytes(),
            )?;
            u32be(&mut records, extra.len() as u32)?;
            records.extend(extra);
        }
        out.write_all(b"8BPS")?;
        u16be(out, 1)?;
        out.write_all(&[0; 6])?;
        u16be(out, 4)?;
        u32be(out, self.height as u32)?;
        u32be(out, self.width as u32)?;
        u16be(out, 8)?;
        u16be(out, 3)?;
        u32be(out, 0)?;
        let mut resources = Vec::new();
        resources.write_all(b"8BIM")?;
        u16be(&mut resources, 1005)?;
        u16be(&mut resources, 0)?;
        u32be(&mut resources, 16)?;
        for _ in 0..2 {
            u32be(&mut resources, self.dpi << 16)?;
            u16be(&mut resources, 1)?;
            u16be(&mut resources, 1)?;
        }
        let icc = include_bytes!("srgb.icc");
        resources.write_all(b"8BIM")?;
        u16be(&mut resources, 1039)?;
        u16be(&mut resources, 0)?;
        u32be(&mut resources, icc.len() as u32)?;
        resources.write_all(icc)?;
        if icc.len() % 2 != 0 {
            resources.write_all(&[0])?;
        }
        u32be(out, resources.len() as u32)?;
        out.write_all(&resources)?;
        let info_length = 2 + records.len() + channel_bytes;
        let padded = info_length + (info_length % 2);
        u32be(out, (4 + padded + 4) as u32)?;
        u32be(out, padded as u32)?;
        u16be(out, (-(layers.len() as i16)) as u16)?;
        out.write_all(&records)?;
        let mut row = vec![0u8; self.width];
        for layer in &layers {
            let [x, y, x1, y1] = layer.rect;
            for shift in [24, 16, 8, 0] {
                u16be(out, 0)?;
                for yy in y..y1 {
                    for xx in x..x1 {
                        let color = layer
                            .pixels
                            .map_or(layer.solid, |v| v[yy * self.width + xx]);
                        row[xx - x] = (color >> shift) as u8;
                    }
                    out.write_all(&row[..x1 - x])?;
                }
            }
            if let Some(mask)=layer.mask{u16be(out,0)?;out.write_all(mask)?;}
        }
        if info_length % 2 != 0 {
            out.write_all(&[0])?;
        }
        u32be(out, 0)?;
        // PSD merged RGB is white-matted; editable layer channels remain straight alpha.
        u16be(out, 0)?;
        let mut merged = vec![0u32; self.width * self.height];
        for (idx, output) in merged.iter_mut().enumerate() {
            let mut color = if self.state.background_visible {
                self.state.background
            } else {
                0
            };
            let mut base_alpha = 0.0;
            for layer in layers.iter().skip(1) {
                let pixel = layer.pixels.unwrap()[idx];
                if !layer.clipping {
                    base_alpha = if layer.visible {
                        channel(pixel, 24)*if layer.mask_enabled{layer.mask.map_or(1.0,|m|m[idx] as f32/255.0)}else{1.0}
                    } else {
                        0.0
                    };
                }
                if layer.visible {
                    color = composite(
                        color,
                        pixel,
                        layer.opacity * if layer.mask_enabled{layer.mask.map_or(1.0,|m|m[idx] as f32/255.0)}else{1.0} * if layer.clipping { base_alpha } else { 1.0 },
                        layer.blend,
                    );
                }
            }
            *output = color;
        }
        for shift in [16, 8, 0, 24] {
            for y in 0..self.height {
                for (x, byte) in row.iter_mut().enumerate() {
                    let color = merged[y * self.width + x];
                    let a = color >> 24;
                    let c = (color >> shift) & 255;
                    *byte = if shift == 24 {
                        a as u8
                    } else {
                        ((c * a + 255 * (255 - a) + 127) / 255) as u8
                    };
                }
                out.write_all(&row)?;
            }
        }
        out.flush()
    }
}
