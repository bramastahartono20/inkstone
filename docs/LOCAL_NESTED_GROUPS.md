# Local nested layer-group build

This local APK extends the raster Layers panel with nested groups and dragging entire group subtrees. It includes the earlier local QuickShape and off-canvas drawing changes. It has not been published or tested on a physical tablet.

- Tap a group name to select its header; hold and drag the name/header to move the whole group with its descendants. The arrow separately expands/collapses it.
- Drop in the center of another group header to nest it. Drop at a boundary outside its parent to move it out. Insertion lines and folder glow retain their existing drag feedback and settling animation.
- Children indent 20 dp per level. Group blend labels stay on one line.
- With a group selected, Group creates an empty child folder, and + adds a layer to that folder. Select a layer to return to ordinary layer operations.
- Visibility and locking inherit through all ancestors. Opacity/blend compositing applies each ancestor at its own boundary, including off-canvas previews. Group moves, membership and ungroup are undoable.
- Cycles are rejected; nesting is limited to 32 levels. Native documents with nested groups use format 5; existing formats 1–4 still load. Older app builds reject format 5 rather than silently discarding hierarchy.
- PSD export preserves nested group appearance using the existing group-baking strategy; native files retain editable hierarchy. This update does not add nested groups to the experimental vector editor.

Validation: 82 Rust tests passed (12 desktop GPU tests ignored); 125 Android unit/integration tests passed; lint: 0 errors, 126 warnings. Android arm64-v8a and x86_64 libraries compiled, packaged libraries matched build outputs, APK static checks and v2 signature verification passed. Rendered nested-group panel inspected locally.

APK: `releases/inkstone-0.20-nested-groups-test.apk` (15,401,538 bytes).
SHA-256: `505a675b79b2704dcead2cdd48679d9b5d2170a97d5025494c599e8798f8aa14`.
