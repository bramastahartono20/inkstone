# Inkstone 0.22 — Group Movement & Editor Controls

Move a group's artwork directly on the canvas instead of entering numeric offsets. Group appearance controls update the artwork live while keeping the Layers panel visible.

- Tap the group name or arrow to expand/collapse; the Layers list preserves its scroll position.
- Group options use a custom popup. Blend & opacity opens immediately to its left, below the toolbar, with Back and live changes.
- Choose **Move group contents**. In Raster, drag the preview, then Done to apply or Cancel to restore. Done creates one Undo entry and moves nested descendants, masks and off-canvas ink. In Vector, each completed drag is one edit; use Undo to restore and Done moving group to exit.
- Recent colour swatches now use contiguous square cells matching the palette.
- Hold a reference image to sample colour with the same coloured/white ring feedback as the drawing canvas. Pan and pinch remain available.
- Symmetry and Drawing Guide use custom controls with Back navigation.

Download `inkstone-0.22-debug.apk` and install over the existing preview. Package `art.inkstone.preview`, version code 35, Android 8.0+, ARM64 and x86-64; the existing signing certificate is retained.

The user tested the local build containing these changes and approved publication. The final versioned APK is rebuilt and checked separately. Inkstone remains in early development; experimental Vector and existing resource/export limits still apply. License terms are unchanged.

Verification: 137 Android tests passed; lint 0 errors, 132 warnings. APK signature, package/version, native libraries and 16 KB alignment checked.
