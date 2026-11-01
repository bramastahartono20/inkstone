use super::*;
impl Layer {
    pub(super) fn mask_coverage(&self, i: usize) -> f32 {
        if self.mask_enabled {
            self.mask.as_ref().map_or(1.0, |m| m[i] as f32 / 255.0)
        } else {
            1.0
        }
    }
    pub(super) fn masked_pixel(&self, i: usize) -> u32 {
        let c = self.pixels[i];
        (c & 0xffffff) | ((((c >> 24) as f32 * self.mask_coverage(i) + 0.5) as u32) << 24)
    }
}
impl Engine {
    pub(super) fn record_mask(&mut self, layer: usize, before: &[u8], search: [usize; 4]) {
        let after = self.state.layers[layer].mask.as_ref().unwrap();
        let mut r = [self.width, self.height, 0, 0];
        for y in search[1]..search[3] {
            for x in search[0]..search[2] {
                let i = y * self.width + x;
                if before[i] != after[i] {
                    r[0] = r[0].min(x);
                    r[1] = r[1].min(y);
                    r[2] = r[2].max(x + 1);
                    r[3] = r[3].max(y + 1);
                }
            }
        }
        if r[2] == 0 {
            return;
        }
        let mut old = vec![];
        let mut new = vec![];
        for y in r[1]..r[3] {
            old.extend_from_slice(&before[y * self.width + r[0]..y * self.width + r[2]]);
            new.extend_from_slice(&after[y * self.width + r[0]..y * self.width + r[2]]);
        }
        self.record(Change::Mask {
            id: self.state.layers[layer].id,
            rect: r,
            before: old,
            after: new,
        });
    }

    pub(super) fn mask_command(&mut self, v: &Value) -> Result<(), String> {
        let action = v["action"].as_str().unwrap_or("add");
        let l = &self.state.layers[self.state.active];
        if l.is_locked() {
            return Err("This layer is locked".into());
        }
        if action == "pixels" {
            self.mask_edit = false;
            return Ok(());
        }
        if action == "edit" {
            if l.mask.is_none() {
                return Err("Add a layer mask first".into());
            }
            self.mask_edit = true;
            return Ok(());
        }
        if action != "add" && l.mask.is_none() {
            return Err("This layer has no mask".into());
        }
        if !matches!(action, "add" | "invert" | "enable" | "delete" | "apply") {
            return Err("Unknown mask action".into());
        }
        if action == "add" && l.mask.is_some() {
            return Err("This layer already has a mask".into());
        }
        if action == "add"
            && Self::layer_bytes(&self.state.layers)
                + self.width * self.height
                > PIXEL_BUDGET * 4
        {
            return Err("Layer mask exceeds the layer memory budget".into());
        }
        self.structure(|e| {
            let l = &mut e.state.layers[e.state.active];
            match action {
                "add" => {
                    let hide = v["hide"].as_bool().unwrap_or(false);
                    l.mask = Some(Arc::new(
                        (0..e.width * e.height)
                            .map(|i| {
                                if let Some(s) = &e.selection {
                                    if s[i] {
                                        255
                                    } else {
                                        0
                                    }
                                } else if hide {
                                    0
                                } else {
                                    255
                                }
                            })
                            .collect(),
                    ));
                    l.mask_enabled = true;
                }
                "invert" => {
                    for p in Arc::make_mut(l.mask.as_mut().unwrap()) {
                        *p = 255 - *p;
                    }
                }
                "enable" => l.mask_enabled = v["enabled"].as_bool().unwrap_or(!l.mask_enabled),
                "apply" => {
                    let mask = l.mask.take().unwrap();
                    if l.mask_enabled {
                        let (w,h)=(l.pixels.width(),l.pixels.height());let mut flat=l.pixels.to_vec();
                        for (i, p) in flat.iter_mut().enumerate() {
                            let a = ((*p >> 24) * mask[i] as u32 + 127) / 255;
                            *p = (*p & 0xffffff) | (a << 24);
                        }
                        l.pixels = crate::tiles::Tiles::from_flat(&flat,w,h);
                    }
                    l.mask_enabled = true;
                }
                "delete" => {
                    l.mask = None;
                    l.mask_enabled = true;
                }
                _ => {}
            }
        });
        self.mask_edit = matches!(action, "add" | "invert");
        Ok(())
    }
}
