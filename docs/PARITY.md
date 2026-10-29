# Inkstone feature status

Local [preview10](LOCAL_PREVIEW_019_10.md) replaces the separate Atlas overview screen with a shared editor camera and in-place artwork activation. It adds custom multicolor combinations, per-tip settings, across-width and bristle color rendering on CPU/GPU, and seven additional multicolor presets. This build is unpublished; physical Android testing remains pending.

Local [preview9](LOCAL_PREVIEW_019_9.md) adds Canvas Atlas overview, group blend/opacity, persistent custom brush libraries, multicolor brushes and experimental RYB wet mixing. Preview8 panel sizing fixes are included. This build is unpublished and physical Android testing is pending.

[Preview7](LOCAL_PREVIEW_019_7.md) publishes the preview4–6 work with indented Raster/Vector group members and animated reparenting. Hardware testing boundaries and current limits are listed in the release instructions.

The unpublished [local preview6](LOCAL_PREVIEW_019_6.md) adds animated gallery sorting/tags/stacks with direct artwork drops, boundary-based layer folder exit, pulsing placement lines and a smoother slow Fade start. Physical Android testing remains pending.

The unpublished [local preview5](LOCAL_PREVIEW_019_5.md) adds long-press layer drag/drop, descriptive history feedback, perspective/isometric guide overlays and basic Raster Liquify, and removes the opaque Fade start cap. Android hardware testing is pending.

The unpublished [local preview4](LOCAL_PREVIEW_019_4.md) adds smooth Warp, speed-sensitive Fade, persistent Recent history, stable category scrolling and basic Raster/Vector folders. Device retesting is pending. Published 0.1.9-preview3 includes the [preview2 selection and transform tools](LOCAL_PREVIEW_019_2.md), plus [PR integration and input improvements](LOCAL_PREVIEW_019_3.md). Fade, Recent history and category scrolling remain device-reported issues. See [workflows](LOCAL_WORKFLOWS.md) and [known issues](KNOWN_ISSUES.md).

| Area | Current implementation | Remaining work |
|---|---|---|
| Canvas layout | Anchored panels and pointers, category glyphs and stroke previews, Actions icon tabs, pill switches, live size HUD, filled toolbar silhouettes, 44 dp tablet bar, interruptible eased popovers | Device testing of layout, timings, gallery transitions and every panel state |
| Gallery | Local documents, cached thumbnails, create/import, rename, duplicate, delete; date/name sorting, tags/filtering, named stacks/counts, bulk and direct drag-to-stack operations, eased card transitions | Nested stacks, manual artwork ordering, library metadata export/sync, selection sharing, gallery preview gestures |
| Brushes | Fifty-seven procedural presets; distinct airbrush hardness, bristle, marker, grain, and spray masks; pressure, flow, smoothing, persistent per-tool Recent, speed-sensitive Fade and stable category scrolling | Device retesting; grain/shape editors, dual brushes, brush dynamics, custom library import/export |
| Smudge | Spatial color pickup and transport with brush masks and alpha-aware mixing | Physical pigment models, bristle color loading and wetness simulation, lower smudge latency |
| Erase | Shared procedural brush library, separate tool settings, inverted previews, pressure and edge hardness | Persistent custom libraries |
| Stylus | Android pressure with global disable switch, tilt, hover, cancellation, side-button events | Xiaomi physical testing; calibration curves; prediction; vendor-specific shortcuts |
| Touch | Pan/zoom/rotate with position lock, undo/redo taps, finger eyedropper, editable hold-to-shape fitting, centered drawing symmetry | Movable symmetry axes, QuickMenu, customizable gesture bindings |
| Color | Hue ring, inner color area, Classic square, RGB/hex, basic harmony, persistent named custom palettes, drag preview to save a swatch | Expanded harmony modes, color history, profiles and CMYK; Vector color selection/fill device bug remains open |
| ColorDrop | Drag active color to fill, live threshold preview, radial reveal, cancellation and one-step Undo | Reference layers and further edge refinement |
| Layers | Core operations, layer locking, alpha lock, clipping, editable grayscale layer masks, fifteen blend modes, thumbnails, inline blend dropdown, swipe actions, long-press drag/drop between layers and folders, nested Raster folders, one-level Vector folders, group appearance/offset movement and persistent empty-folder positions | Nested Vector folders and gesture group transforms, reference layers, selection gestures, more blending modes |
| Selection | Automatic/freehand/rectangle/ellipse, Replace/Add/Remove/Invert, Feather, named saved masks, clipboard actions, soft overlay and dashed outlines; edits constrained by coverage | Device testing; more accurate full-resolution boundary preview |
| Transform | Freeform/Uniform/Distort/smooth fixed-grid Warp, bounds and handles, two-finger object gestures, snapping, Nearest/Bilinear, flip, fit/reset, cancel/one-commit Undo | Persistent image objects, device testing |
| Adjustments | Brightness, saturation, box blur, grayscale, invert; live Raster Liquify Push/Pinch/Expand/Twirl/Restore with cancel/one-step Undo | Advanced Liquify reconstruction/Freeze and GPU implementation; live color-adjustment previews; hue/curves/color balance; true Gaussian blur; Clone, Bloom, Glitch, Noise, Sharpen |
| Canvas | Preset popup, full custom editor, physical units, persisted DPI, background options, workspace grid, fit, adjustable perspective/isometric guide overlays with draggable vanishing points | Crop/resize after creation, movable symmetry axes, assisted drawing and per-document guide metadata |
| Files | Editable `.inkstone`, layered 8-bit RGB PSD import/export, flattened PNG/JPEG with Photo Gallery saving, image insertion | Additional PSD features, PDF, TIFF, profile conversion, EXIF orientation |
| Vector documents | Separate Pen/Line/Rectangle/Ellipse mode, object move/scale/rotate and cubic anchors/handles, anchor insertion, layers, editable SVG and project export | SVG import, text, boolean operations, gradients, closed-path topology editing, multi-object selection |
| Reference companion | Local per-artwork image, movable/resizable window, pan/zoom, canvas snapshot and eyedropper in Raster/Vector | Embedding reference assets in exports, live canvas reference, orientation metadata |
| Text | Not implemented | Font management, editing, vector/raster conversion |
| Animation / Page Assist | Not implemented | Timeline, onion skin, frame playback/export, multi-page workflows |
| 3D painting | Not implemented | Mesh loading, UV painting, lighting, texture export |
| Time-lapse | Not implemented | Stroke recording, replay, video encoding/export |
| Accessibility | Native labels/focus, basic keyboard bindings, pen/finger toggle | Full TalkBack pass, large-text reflow, switch access, color-independent selection cues |
| Performance | wgpu compute brush/eraser/smudge and live layer compositing; bounded layer cache, CPU fallback, dirty-rectangle readback, batched input, independent storage worker | Tiled renderer, direct GPU presentation, input prediction, Android hardware benchmarks |

Next priorities:

1. Measure Android pen delivery, stroke latency, memory and thermals on physical devices.
2. Improve accessibility, layout and interaction feedback from device reports.
3. Extend tiled GPU rendering and reduce readback overhead.
4. Improve masks, transforms, adjustments, guides and file interoperability.
