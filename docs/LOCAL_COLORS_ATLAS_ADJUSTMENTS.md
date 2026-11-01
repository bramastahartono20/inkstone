# Local colors, Atlas, Gallery and adjustment revision

Local APK: `releases/inkstone-0.22-local-colors-atlas-adjustments-debug.apk`. Not published.

- Tutorials: replaced the confusing refill animation with a staged selected-color / pickup / refill demonstration. Size/opacity/eraser steps focus on each control in sequence. Removed automatic panel panning into empty space.
- Palette taps rebuild Colors with the chosen hue, matching Recent colors. The color disc uses GPU gradients instead of allocating and calculating a bitmap for every hue change; preference writes while dragging are debounced.
- New brush textures use interpolated fine grain and softer coverage transitions. Linen uses a lower-contrast local tip pattern instead of fixed canvas-coordinate checker holes. Soft Wash uses a wet wash tip with pickup and retention defaults. Brush swatches were regenerated from the native engine.
- Atlas marks the current artwork immediately in overview. The raster editor's quick-pinch fit gesture is enabled while Atlas is linked, including a quick contraction starting on the board.
- Gallery: artwork-to-artwork drops create a stack (or join the target's stack), using the existing release-position transition. Select gives a brief scale animation and shows a checkbox on every artwork. Gallery has one Import entry with image/document choices.
- Brightness, Saturation, Grayscale and Invert now create editable native adjustment layers with live controls. Their source pixels remain untouched. Adjustment metadata survives native saving and undo/redo; these layers use normal layer grouping, visibility, opacity, masks and clipping.
- Blur previews live against the original layer pixels and commits on leaving its controls. Reset restores the original pixels. It is still a raster operation, not a separate adjustment layer.

Limitations: brush grain remains procedural rather than fluid simulation. Adjustment parameters are stored in Inkstone documents; editable PSD adjustment-layer compatibility is not implemented. Hardware gesture feel, visual quality and performance still require testing on the user's tablet. The Canvas Atlas board fit addition targets the raster editor.

Verification: 129 Rust CPU tests passed, including native adjustment save/clipping and absolute live-preview history checks. The physical GPU brush/smudge/history parity test passed. The full 157 Android host tests passed, then the four tutorial/export experience tests passed again after the final tutorial camera changes. Lint and APK assembly passed. APK integrity, both native ABI byte matches, JNI symbols, 16 KB ELF alignment and the existing debug certificate were verified. No physical Android device was used.
