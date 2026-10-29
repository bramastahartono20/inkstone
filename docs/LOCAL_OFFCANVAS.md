# Local drawing revision — 2026-10-29

Unpublished local build based on 0.20. No GitHub release was updated.

## QuickShape

Recognition resamples stroke geometry at even distances so slow corners do not dominate the fit. Rotated rectangle fitting is more tolerant, and closed triangles have an explicit polygon fit. Circles and ellipses retain separate aspect-ratio classification. Recognition still requires eight original samples; a stationary hold on a sparsely sampled stroke must not invent a shape.

## Drawing outside the raster canvas

Raster layers retain off-canvas paint in sparse 128 px tiles. The original brush color is retained; the editor displays the exterior with reduced opacity over the workspace grid, without a paper background. The native document saves the original pixels, and brush/eraser undo, redo, cancellation, layer visibility, duplication, merging and clearing retain the exterior data.

Only allocated tiles consume storage. Current bounds are 2048 pixels beyond each canvas edge, 256 tiles per layer and 1024 per document. A layer-storage limit reports an input error. Edge-crossing strokes use the CPU renderer; strokes confined to the canvas retain their existing GPU path. Exterior display uses 64 px tile previews; native stored tiles remain full resolution.

PNG, JPG and PSD export and gallery thumbnails retain the original canvas dimensions. Native documents containing exterior paint use format version 4; older app builds cannot open these documents. Older documents continue to load.

Selection, transform, ColorDrop, adjustments, masks and liquify still operate within the canvas. Exterior selection/transform as shown later in the reference video is not implemented in this revision. Vector mode is unchanged. Physical stylus/device performance needs user testing.

## Validation

79 Rust tests and 124 Android tests passed; 12 desktop GPU tests were ignored by the ordinary Rust test command. Android lint completed. Native libraries compiled for arm64-v8a and x86_64. Regression tests cover uneven rotated shape input, triangles, ellipse proportions, exterior JNI display, save/load, crossing strokes, erasing, cancellation, archived undo and redo, clearing and visibility.
