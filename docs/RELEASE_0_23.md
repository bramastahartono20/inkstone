# Inkstone 0.23 — Faster Drawing, Paint Mixing & Time-lapse

Drawing stays smooth on large, many-layer canvases. Paint Mixing, tutorials and time-lapse recording arrive from the local preview builds.

## Performance

- The canvas is shown as 256 × 256 tiles. Each frame re-uploads only the tiles a stroke touched, not the whole artwork. On a 3000 × 2000 canvas that was about 24 MB per frame.
- Paint Mixing brushes of 32 px and larger now paint on the GPU, including wet contact mixing and the wet edge. Before, mixing switched every dab to the CPU.
- Old undo steps are compressed on a background thread. Before, once history passed its 96 MB memory budget, each stroke end waited 80–160 ms while an older step was written to disk. Undo still uses a step while it is being saved.
- Layers are stored as copy-on-write tiles. Stroke undo shares the pre-stroke pixels instead of copying the full layer.
- A tile cache reuses the flattened layers below the active layer.
- Document saving streams compressed data instead of holding the whole encoded file in memory.

## Painting

- **Paint Mixing**: the brush picks up paint already on the layer and refills gradually with the selected colour on clear areas. Wetness controls how long pigment is carried, Colour pickup how strongly the brush loads, and Mix strength the visible effect. Wet contact keeps local colour variation across the brush tip.
- Inkflow stroke engine: smoother curves through gentle turns, sharp corners kept, speed-aware stabilisation, and better pressure response at stroke start.
- New and refined brush textures, including Soft Wash and Linen.
- A raster layer directly above a group can clip to that group's combined contents.
- Clipped layers show a connecting arrow. Alpha Lock appears next to the layer name.

## Tutorials and time-lapse

- 19 tutorial steps demonstrate the real editor in both light and dark themes, without changing your artwork.
- Time-lapse recording captures intermediate stroke frames at up to 12 fps and exports H.264 MP4.

Download `inkstone-0.23-debug.apk` and install it over the existing preview. Package `art.inkstone.preview`, version code 36, Android 8.0+, ARM64 and x86-64. The existing signing certificate is retained.

Tested on a Xiaomi Pad 7 before publication. Inkstone remains in early development. Experimental Vector and the existing resource and export limits still apply. License terms are unchanged.

Verification: 143 Rust tests and 17 physical-GPU tests passed. 156 of 158 Android tests passed; the two StudioTest failures (save-format and autosave-timing expectations) predate this release. APK signature, package and version, native libraries and 16 KB alignment checked.
