# Local group clipping revision

A raster layer immediately above a group, outside that group, can use Clipping Mask to restrict its output to the combined visible contents of that group. Nested groups, enabled layer masks, group visibility and opacity participate in the boundary. Consecutive clipped layers retain the same base group.

The existing layer clipping switch is used. The layer options include an explanation of placement. Group clipping uses isolated group compositing, including groups normally set to Pass Through. The GPU brush renderer remains available; group clipping composites through the prepared CPU plan because the flat GPU compositor and backdrop cache do not represent group clip boundaries.

Editable native files preserve the original hierarchy and clipping flags. PSD export preserves the clipped appearance by flattening a base group to an export layer, matching the existing export approach for isolated groups. It does not export an editable PSD group hierarchy.

Validation: 123 CPU/shader tests, 16 physical Metal GPU checks and 149 Android host tests passed. Lint reported zero errors and 137 warnings. Regression tests cover disjoint group members, transparent final members, masks, visibility, opacity, nested groups, multiple clipped layers, moves, ungroup, save/load, full/patch rendering, undo/redo and PSD appearance. The APK's two native libraries match the rebuilt Android libraries and its v2 signature verifies. This build has not been installed on a physical Android device or published.

APK: `releases/inkstone-0.22-local-group-clipping-debug.apk`
Proof: `artifacts/local-group-clipping-verification.json`
