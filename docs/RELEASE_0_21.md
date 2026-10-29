# Inkstone 0.21 — Layer Groups, Off-canvas Drawing & Reliability

Inkstone remains an early-development drawing app. This release includes the local changes since 0.20 and the fixes confirmed in the [bug-hunt report](BUG_HUNT_0_21.md).

## Layer organization

- Create several independent folders from Layers → Group. The custom Inkstone panel replaces the native name dialog. New folders default to Top level; Location explicitly chooses a parent.
- Select and hold a group header to drag the complete folder. Nest Raster groups up to 32 levels, with an additional 20 dp indent at each level. New children expand their parents so they remain visible.
- Empty folders retain their position through move, save/load and Undo/Redo. Drop over the indented last row to place a layer at the bottom inside its folder; drag toward the left edge to leave the folder.
- Parent visibility, locking, blend mode and opacity apply through the hierarchy. Delayed layer refreshes no longer replace a group-name form.

## Drawing and transforms

- QuickShape better distinguishes triangles, rectangles, circles and ellipses, including rotated and unevenly sampled outlines.
- Raster strokes can continue beyond the paper, displayed partially transparent over the workspace. Exterior ink remains attached to its layer through native saving, Undo/Redo, whole-layer transforms, group offsets and Canvas Flip.
- Exterior previews transfer only changed tiles through the binary render-patch path during drawing. PNG/JPEG/PSD output remains cropped to the paper.
- Transform preview retains nested group appearance. Unsupported transforms outside storage bounds now report an error instead of silently deleting the artwork.

## Confirmed bug fixes

See the report for all 16 findings and regression evidence. Fixes protect paste/group identifiers, clipboard contents, merge appearance and tile limits, extreme-aspect-ratio imports, native import resource budgets, oversized empty-layer metadata, recent-brush identity and asynchronous panel updates. A merge across a group boundary is rejected until the layers are placed in the same folder.

## Install and compatibility

Download `inkstone-0.21-debug.apk` from [the 0.21 release](https://github.com/bramastahartono20/inkstone/releases/tag/v0.21). Package `art.inkstone.preview`, version code 34; ARM64 and x86-64 are included. The previous preview signing certificate is retained, allowing installation over earlier official previews without clearing local artwork.

Native projects using nested folders or exterior ink require this newer app. Format versions 1–4 still load; nested hierarchy/position metadata uses format 5. Export editable `.inkstone` backups before uninstalling or clearing app data. The source-available license and commercial-artwork permission are unchanged.

## Current limits

The exterior area extends at most 2048 pixels beyond the artboard, with 256 exterior tiles per layer and 1024 per document. Resource limits include a 16-megapixel artboard, the existing 256 MiB decoded layer-data budget, a 4096-layer metadata safety ceiling, 512 folders and bounded undo storage. Large documents may reach their memory budget much earlier. Whole-layer transforms include exterior ink; explicit selections still cover artboard pixels. Nested folders are available in Raster; experimental Vector folders and gallery stacks remain one level. Group appearance is baked where required during PSD export. Paint Mixing remains an approximation.

Local verification: 103 Rust tests, 12 physical Metal GPU tests on the Mac, and 132 Android framework/JNI tests passed. Lint had 0 errors and 127 warnings. APK native libraries, license assets, signature and 16 KB alignment were verified. Xiaomi/Samsung physical stylus behavior, Android Vulkan, thermals and animation pacing still need device testing.
