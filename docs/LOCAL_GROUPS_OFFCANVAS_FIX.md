# Local group placement and off-canvas transform fixes

APK: `releases/inkstone-0.20-groups-offcanvas-fix.apk`.
SHA-256: `6f17c55dc997b5a6d653afb94487255c2623c23406ba32a7de8dfe0e5dd36eb8` (15,413,986 bytes).

This build includes the preceding local QuickShape, off-canvas storage and nested raster group changes. It has not been published or tested on a physical tablet.

- Empty groups retain a position among layers/groups, including after save/load and undo/redo. Their header can be dragged without any child layers. Empty group gaps remain stable when their anchor layer joins or leaves the group.
- A drop below the last layer remains inside the folder when placed over the indented row. Drag toward the left edge (outside the indented contents) to move it out. Folder headers still accept a center drop to join the folder.
- Off-canvas rendering now shares the binary JNI render-patch transfer. During a stroke only changed 128-pixel tiles are recomposited/transferred, with 64-pixel previews; existing bitmap tiles are retained. JSON pixel-array transfer and rebuilding every off-canvas bitmap on each frame have been removed from the Android drawing path.
- Whole-layer transform bounds and previews include off-canvas pixels. Move, scale, rotation, projective and mesh transform sampling support signed coordinates, preserving art across the paper edge. Undo/redo and native save/load retain these pixels. An explicit selection still transforms only selected artboard pixels.
- Transform storage retains the existing 2048-pixel exterior margin and tile budgets. PNG/JPG/PSD exports remain cropped to artboard bounds. Experimental vector groups are not changed by this build.

Validation: 86 Rust tests passed (12 desktop GPU tests ignored), 127 Android unit/integration tests passed, lint had no errors. Both Android ABIs compiled and matched the packaged libraries. APK static checks and v2 signature verification passed. Off-canvas transform preview inspected locally. Device drawing latency still requires testing on the tablet.
