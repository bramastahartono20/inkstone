use crate::{Layer, Sample};
use bytemuck::{Pod, Zeroable};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::Duration,
};

// Hold every layer the canvas budget allows plus headroom for one transient layer. A smaller
// cache evicts and re-uploads the whole stack on every frame.
fn cache_bytes() -> usize {
    crate::pixel_budget() + 64 * 1024 * 1024
}
pub const MAX_DABS: usize = 128;
#[repr(C)]
#[derive(Clone, Copy, Pod, Zeroable)]
pub struct Params {
    pub meta: [u32; 4],
    pub rect: [u32; 4],
    pub dab: [f32; 4],
    pub flags: [u32; 4],
    pub region: [u32; 4],
    pub palette:[u32;12],
    pub color_effect:[f32;4],
    pub direction:[f32;4],
    /// Paint Mixing: x = 1 when `contact` holds the 9 × 9 wet contact field, y = wet-edge strength.
    pub wet:[f32;4],
    pub contact:[[u32;4];21],
}
pub struct Dab {
    pub brush: super::Brush,
    pub distance:f32,
    pub params: Params,
    pub sample: Sample,
}
struct CachedLayer {
    buffer: wgpu::Buffer,
    /// Generation of the CPU pixels this buffer holds; None until the first upload.
    source: Option<u64>,
    used: u64,
}
pub struct Gpu {
    device: wgpu::Device,
    asynchronous_error: Arc<Mutex<Option<String>>>,
    queue: wgpu::Queue,
    layout: wgpu::BindGroupLayout,
    paint: wgpu::ComputePipeline,
    composite: wgpu::ComputePipeline,
    composite_layout: wgpu::BindGroupLayout,
    pub composites: u64,
    pickup: wgpu::ComputePipeline,
    advance: wgpu::ComputePipeline,
    gather: wgpu::ComputePipeline,
    tip: wgpu::Buffer,
    selection: wgpu::Buffer,
    layers: HashMap<u32, CachedLayer>,
    tick: u64,
    pub description: String,
    pub batches: u64,
    pub dabs: u64,
}
impl Gpu {
    pub fn new() -> Result<Self, String> {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: if cfg!(target_os = "macos") {
                wgpu::Backends::METAL
            } else {
                wgpu::Backends::VULKAN
            },
            ..Default::default()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            force_fallback_adapter: false,
            compatible_surface: None,
        }))
        .map_err(|e| format!("GPU unavailable: {e}"))?;
        let info = adapter.get_info();
        if info.device_type == wgpu::DeviceType::Cpu {
            return Err("Only a software GPU adapter is available".into());
        }
        let limits = wgpu::Limits::default();
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("Inkstone compute"),
            required_limits: limits,
            ..Default::default()
        }))
        .map_err(|e| format!("GPU device: {e}"))?;
        let asynchronous_error = Arc::new(Mutex::new(None));
        let error_sink = asynchronous_error.clone();
        device.on_uncaptured_error(Arc::new(move |error: wgpu::Error| {
            *error_sink.lock().unwrap() = Some(error.to_string());
        }));
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let entries: Vec<_> = (0..5)
            .map(|i| wgpu::BindGroupLayoutEntry {
                binding: i,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty: if i == 2 {
                        wgpu::BufferBindingType::Uniform
                    } else {
                        wgpu::BufferBindingType::Storage { read_only: i == 1 }
                    },
                    has_dynamic_offset: i == 2,
                    min_binding_size: None,
                },
                count: None,
            })
            .collect();
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Paint buffers"),
            entries: &entries,
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: None,
            bind_group_layouts: &[&layout],
            push_constant_ranges: &[],
        });
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Inkstone paint"),
            source: wgpu::ShaderSource::Wgsl(include_str!("paint.wgsl").into()),
        });
        let pipeline = |entry| {
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some(entry),
                layout: Some(&pipeline_layout),
                module: &shader,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                cache: None,
            })
        };
        let paint = pipeline("paint");
        let pickup = pipeline("pickup");
        let advance = pipeline("advance");
        let gather = pipeline("gather");
        let composite_entries: Vec<_> = (0..3)
            .map(|i| wgpu::BindGroupLayoutEntry {
                binding: i,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty: if i == 2 {
                        wgpu::BufferBindingType::Uniform
                    } else {
                        wgpu::BufferBindingType::Storage { read_only: i == 0 }
                    },
                    has_dynamic_offset: i == 2,
                    min_binding_size: None,
                },
                count: None,
            })
            .collect();
        let composite_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Composite buffers"),
            entries: &composite_entries,
        });
        let composite_pipeline_layout =
            device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: None,
                bind_group_layouts: &[&composite_layout],
                push_constant_ranges: &[],
            });
        let composite_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Layer composite"),
            source: wgpu::ShaderSource::Wgsl(include_str!("composite.wgsl").into()),
        });
        let composite = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("Layer composite"),
            layout: Some(&composite_pipeline_layout),
            module: &composite_shader,
            entry_point: Some("composite"),
            compilation_options: Default::default(),
            cache: None,
        });
        if let Some(e) = pollster::block_on(device.pop_error_scope()) {
            return Err(format!("GPU shader: {e}"));
        }
        let tip = Self::buffer(
            &device,
            8192,
            "Smudge tip",
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let selection = Self::upload(
            &device,
            &queue,
            &[0; 4],
            "No selection",
            wgpu::BufferUsages::STORAGE,
        );
        Ok(Self {
            device,
            asynchronous_error,
            queue,
            layout,
            paint,
            composite,
            composite_layout,
            composites: 0,
            pickup,
            advance,
            gather,
            tip,
            selection,
            layers: HashMap::new(),
            tick: 0,
            description: format!("{:?} · {}", info.backend, info.name),
            batches: 0,
            dabs: 0,
        })
    }
    #[cfg(test)]
    pub fn destroy_for_test(&self) {
        self.device.destroy();
    }
    fn upload(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        data: &[u8],
        label: &str,
        usage: wgpu::BufferUsages,
    ) -> wgpu::Buffer {
        // Avoid mapped-at-creation helpers: mapping an invalid buffer can panic after device loss.
        let buffer = Self::buffer(
            device,
            data.len() as u64,
            label,
            usage | wgpu::BufferUsages::COPY_DST,
        );
        queue.write_buffer(&buffer, 0, data);
        buffer
    }
    fn buffer(
        device: &wgpu::Device,
        size: u64,
        label: &str,
        usage: wgpu::BufferUsages,
    ) -> wgpu::Buffer {
        device.create_buffer(&wgpu::BufferDescriptor {
            label: Some(label),
            size: size.max(4),
            usage,
            mapped_at_creation: false,
        })
    }
    /// Whether every layer of `layers` can stay resident at once. Resident layers are full-size
    /// even when the CPU copy is sparse, so a larger stack would evict and re-upload every frame.
    pub fn fits(&self, layers: &[Layer]) -> bool {
        let mut ids = std::collections::HashSet::new();
        let size = layers.first().map_or(0, |l| l.pixels.len() * 4);
        layers.iter().filter(|l| ids.insert(l.id)).count() * size <= cache_bytes()
    }

    pub fn selection(&mut self, selection: Option<&[bool]>, alpha:Option<&[u8]>) {
        let values: Vec<u32> = selection
            .map(|s| s.iter().enumerate().map(|(i,v)|alpha.map_or(if *v{255}else{0},|a|a[i] as u32)).collect())
            .unwrap_or_else(|| vec![0]);
        self.selection = Self::upload(
            &self.device,
            &self.queue,
            bytemuck::cast_slice(&values),
            "Selection mask",
            wgpu::BufferUsages::STORAGE,
        );
    }
    fn sync_layer(&mut self, layer: &Layer) -> Result<(), String> {
        let size = layer.pixels.len() * 4;
        if size > self.device.limits().max_storage_buffer_binding_size as usize {
            return Err("Canvas exceeds GPU buffer limit".into());
        }
        self.tick += 1;
        if !self.layers.contains_key(&layer.id) {
            while (self.layers.len() + 1) * size > cache_bytes() {
                let oldest = self
                    .layers
                    .iter()
                    .min_by_key(|(_, l)| l.used)
                    .map(|(id, _)| *id);
                if let Some(id) = oldest {
                    self.layers.remove(&id);
                } else {
                    break;
                }
            }
            let buffer = Self::buffer(
                &self.device,
                size as u64,
                "Resident layer",
                wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            );
            self.layers.insert(
                layer.id,
                CachedLayer {
                    buffer,
                    source: None,
                    used: self.tick,
                },
            );
        }
        let cached = self.layers.get_mut(&layer.id).unwrap();
        cached.used = self.tick;
        if cached.source != Some(layer.pixels.generation())
        {
            let flat = layer.pixels.to_vec();
            self.queue.write_buffer(
                &cached.buffer,
                0,
                bytemuck::cast_slice(&flat),
            );
            cached.source = Some(layer.pixels.generation());
        }
        Ok(())
    }
    pub fn paint(
        &mut self,
        layer: &mut Layer,
        dabs: &[Dab],
        rect: [usize; 4],
        smudging: bool,
    ) -> Result<Option<Vec<u32>>, String> {
        let result = self.scoped("GPU paint", |gpu| {
            let mut encoder = gpu.device.create_command_encoder(&Default::default());
            let readback = gpu.encode_paint(&mut encoder, layer, dabs, rect, smudging)?;
            let submission = gpu.queue.submit([encoder.finish()]);
            gpu.wait(submission, &[&readback])?;
            Ok(Self::read_paint(&readback, rect, smudging))
        })?;
        Ok(self.apply_paint(layer, dabs, rect, result))
    }
    /// Paints a dab batch and composites the canvas in one submission, so a frame waits for the
    /// GPU once instead of twice. `layers` are taken before painting; the painted layer's resident
    /// buffer already holds the new dabs when the composite reads it.
    #[allow(clippy::too_many_arguments)]
    pub fn paint_and_composite(
        &mut self,
        layer: &mut Layer,
        dabs: &[Dab],
        paint_rect: [usize; 4],
        smudging: bool,
        layers: &[Layer],
        width: usize,
        rect: [usize; 4],
        background: u32,
    ) -> Result<(Option<Vec<u32>>, Vec<u32>), String> {
        let (paint, pixels) = self.scoped("GPU frame", |gpu| {
            let mut encoder = gpu.device.create_command_encoder(&Default::default());
            let paint = gpu.encode_paint(&mut encoder, layer, dabs, paint_rect, smudging)?;
            let composite = gpu.encode_composite(&mut encoder, layers, width, rect, background)?;
            let submission = gpu.queue.submit([encoder.finish()]);
            gpu.wait(submission, &[&paint, &composite])?;
            Ok((
                Self::read_paint(&paint, paint_rect, smudging),
                Self::read_composite(&composite),
            ))
        })?;
        self.composites += 1;
        Ok((self.apply_paint(layer, dabs, paint_rect, paint), pixels))
    }
    fn apply_paint(
        &mut self,
        layer: &mut Layer,
        dabs: &[Dab],
        rect: [usize; 4],
        (values, tip): (Vec<u32>, Option<Vec<u32>>),
    ) -> Option<Vec<u32>> {
        let [x, y, x1, y1] = rect;
        let w = x1 - x;
        for row in 0..y1 - y {
            layer.pixels.set_row(y + row, x, &values[row * w..(row + 1) * w]);
        }
        self.layers.get_mut(&layer.id).unwrap().source = Some(layer.pixels.generation());
        self.batches += 1;
        self.dabs += dabs.len() as u64;
        tip
    }
    /// Runs `body` inside validation and out-of-memory error scopes.
    fn scoped<T>(
        &mut self,
        label: &str,
        body: impl FnOnce(&mut Self) -> Result<T, String>,
    ) -> Result<T, String> {
        self.device.push_error_scope(wgpu::ErrorFilter::OutOfMemory);
        self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let result = body(self);
        let validation = pollster::block_on(self.device.pop_error_scope());
        let allocation = pollster::block_on(self.device.pop_error_scope());
        if let Some(e) = validation.or(allocation) {
            return Err(format!("{label}: {e}"));
        }
        if let Some(e) = self.asynchronous_error.lock().unwrap().take() {
            return Err(e);
        }
        result
    }
    /// Blocks until `submission` finishes and every readback buffer is mapped.
    fn wait(
        &self,
        submission: wgpu::SubmissionIndex,
        readbacks: &[&wgpu::Buffer],
    ) -> Result<(), String> {
        let (tx, rx) = std::sync::mpsc::sync_channel(readbacks.len());
        for readback in readbacks {
            let tx = tx.clone();
            readback.slice(..).map_async(wgpu::MapMode::Read, move |r| {
                let _ = tx.send(r);
            });
        }
        self.device
            .poll(wgpu::PollType::Wait {
                submission_index: Some(submission),
                timeout: Some(Duration::from_secs(10)),
            })
            .map_err(|e| format!("GPU wait: {e}"))?;
        for _ in readbacks {
            rx.recv_timeout(Duration::from_secs(1))
                .map_err(|e| format!("GPU readback: {e}"))?
                .map_err(|e| e.to_string())?;
        }
        Ok(())
    }
    fn read_paint(
        readback: &wgpu::Buffer,
        rect: [usize; 4],
        smudging: bool,
    ) -> (Vec<u32>, Option<Vec<u32>>) {
        let [x, y, x1, y1] = rect;
        let pixels = (x1 - x) * (y1 - y);
        let mapped = readback.slice(..).get_mapped_range();
        let values: &[u32] = bytemuck::cast_slice(&mapped);
        let tip = if smudging {
            Some(values[pixels..].to_vec())
        } else {
            None
        };
        let out = values[..pixels].to_vec();
        drop(mapped);
        readback.unmap();
        (out, tip)
    }
    fn read_composite(readback: &wgpu::Buffer) -> Vec<u32> {
        let mapped = readback.slice(..).get_mapped_range();
        let words: &[u32] = bytemuck::cast_slice(&mapped);
        let out = words.chunks_exact(2).map(|v| v[0]).collect();
        drop(mapped);
        readback.unmap();
        out
    }
    fn encode_paint(
        &mut self,
        encoder: &mut wgpu::CommandEncoder,
        layer: &Layer,
        dabs: &[Dab],
        rect: [usize; 4],
        smudging: bool,
    ) -> Result<wgpu::Buffer, String> {
        self.sync_layer(layer)?;
        let [x, y, x1, y1] = rect;
        let w = x1 - x;
        let h = y1 - y;
        let align = self
            .device
            .limits()
            .min_uniform_buffer_offset_alignment
            .max(256) as usize;
        let stride = std::mem::size_of::<Params>().div_ceil(align) * align;
        let mut data = vec![0u8; (dabs.len() + 1) * stride];
        for (i, dab) in dabs.iter().enumerate() {
            data[i * stride..i * stride + std::mem::size_of::<Params>()].copy_from_slice(bytemuck::bytes_of(&dab.params));
        }
        let mut gather = dabs.last().unwrap().params;
        gather.region = [x as u32, y as u32, w as u32, h as u32];
        data[dabs.len() * stride..dabs.len() * stride + std::mem::size_of::<Params>()]
            .copy_from_slice(bytemuck::bytes_of(&gather));
        let params = Self::upload(
            &self.device,
            &self.queue,
            &data,
            "Dab batch",
            wgpu::BufferUsages::UNIFORM,
        );
        let bytes = (w * h * 4) as u64;
        let output = Self::buffer(
            &self.device,
            bytes,
            "Changed pixels",
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let readback = Self::buffer(
            &self.device,
            bytes + if smudging { 4096 } else { 0 },
            "Paint readback",
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        );
        let buffers = [
            &self.layers[&layer.id].buffer,
            &self.selection,
            &params,
            &self.tip,
            &output,
        ];
        let entries: Vec<_> = buffers
            .iter()
            .enumerate()
            .map(|(i, b)| wgpu::BindGroupEntry {
                binding: i as u32,
                resource: if i == 2 {
                    wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                        buffer: b,
                        offset: 0,
                        size: std::num::NonZeroU64::new(std::mem::size_of::<Params>() as u64),
                    })
                } else {
                    b.as_entire_binding()
                },
            })
            .collect();
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("Paint batch"),
            layout: &self.layout,
            entries: &entries,
        });
        for (i, dab) in dabs.iter().enumerate() {
            let offset = (i * stride) as u32;
            if smudging {
                Self::dispatch(encoder, &self.pickup, &group, offset, 4, 4);
            }
            if !smudging || dab.params.flags[3] == 0 {
                let r = dab.params.rect;
                if r[2] > r[0] && r[3] > r[1] {
                    Self::dispatch(
                        encoder,
                        &self.paint,
                        &group,
                        offset,
                        (r[2] - r[0]).div_ceil(8),
                        (r[3] - r[1]).div_ceil(8),
                    );
                }
                if smudging {
                    Self::dispatch(encoder, &self.advance, &group, offset, 4, 4);
                }
            }
        }
        Self::dispatch(
            encoder,
            &self.gather,
            &group,
            (dabs.len() * stride) as u32,
            (w as u32).div_ceil(8),
            (h as u32).div_ceil(8),
        );
        encoder.copy_buffer_to_buffer(&output, 0, &readback, 0, bytes);
        if smudging {
            encoder.copy_buffer_to_buffer(&self.tip, 0, &readback, bytes, 4096);
        }
        Ok(readback)
    }
    pub fn composite(
        &mut self,
        layers: &[Layer],
        width: usize,
        rect: [usize; 4],
        background: u32,
    ) -> Result<Vec<u32>, String> {
        let out = self.scoped("GPU composite", |gpu| {
            let mut encoder = gpu.device.create_command_encoder(&Default::default());
            let readback = gpu.encode_composite(&mut encoder, layers, width, rect, background)?;
            let submission = gpu.queue.submit([encoder.finish()]);
            gpu.wait(submission, &[&readback])?;
            Ok(Self::read_composite(&readback))
        })?;
        self.composites += 1;
        Ok(out)
    }
    fn encode_composite(
        &mut self,
        encoder: &mut wgpu::CommandEncoder,
        layers: &[Layer],
        width: usize,
        rect: [usize; 4],
        background: u32,
    ) -> Result<wgpu::Buffer, String> {
        let [x, y, x1, y1] = rect;
        let w = x1 - x;
        let h = y1 - y;
        let bytes = (w * h * 8) as u64;
        if bytes > self.device.limits().max_storage_buffer_binding_size as u64 {
            return Err("Composite exceeds GPU buffer limit".into());
        }
        let work = Self::buffer(
            &self.device,
            bytes,
            "Composite patch",
            wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
        );
        let readback = Self::buffer(
            &self.device,
            bytes,
            "Composite readback",
            wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        );
        let stride = self
            .device
            .limits()
            .min_uniform_buffer_offset_alignment
            .max(256) as usize;
        let modes = [
            "Normal",
            "Multiply",
            "Colour Burn",
            "Linear Burn",
            "Colour Dodge",
            "Subtract",
            "Exclusion",
            "Hard Light",
            "Screen",
            "Overlay",
            "Add",
            "Darker Colour",
            "Darken",
            "Lighten",
            "Difference",
        ];
        let mut data = vec![0u8; layers.len() * stride];
        for (i, layer) in layers.iter().enumerate() {
            let words: [u32; 16] = [
                width as u32,
                0,
                background,
                (i == 0) as u32,
                x as u32,
                y as u32,
                w as u32,
                h as u32,
                layer.opacity.to_bits(),
                0,
                0,
                0,
                modes.iter().position(|m| *m == layer.blend).unwrap_or(0) as u32,
                layer.is_visible() as u32,
                layer.clipping as u32,
                0,
            ];
            data[i * stride..i * stride + 64].copy_from_slice(bytemuck::cast_slice(&words));
        }
        let params = Self::upload(
            &self.device,
            &self.queue,
            &data,
            "Layer settings",
            wgpu::BufferUsages::UNIFORM,
        );
        for (i, layer) in layers.iter().enumerate() {
            self.sync_layer(layer)?;
            let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Layer composite"),
                layout: &self.composite_layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: self.layers[&layer.id].buffer.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: work.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: wgpu::BindingResource::Buffer(wgpu::BufferBinding {
                            buffer: &params,
                            offset: 0,
                            size: std::num::NonZeroU64::new(64),
                        }),
                    },
                ],
            });
            Self::dispatch(
                encoder,
                &self.composite,
                &group,
                (i * stride) as u32,
                (w as u32).div_ceil(8),
                (h as u32).div_ceil(8),
            );
        }
        encoder.copy_buffer_to_buffer(&work, 0, &readback, 0, bytes);
        Ok(readback)
    }
    fn dispatch(
        encoder: &mut wgpu::CommandEncoder,
        pipeline: &wgpu::ComputePipeline,
        group: &wgpu::BindGroup,
        offset: u32,
        x: u32,
        y: u32,
    ) {
        // Separate passes make pickup, painting, and tip transport ordered on every backend.
        let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
            label: Some("Paint compute"),
            timestamp_writes: None,
        });
        pass.set_pipeline(pipeline);
        pass.set_bind_group(0, group, &[offset]);
        pass.dispatch_workgroups(x, y, 1);
    }
}
