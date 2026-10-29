# Local UI refinements after 0.21

Recent color swatches are square and contiguous, matching palette cells. Reference long-press sampling shows the same color/white picker rings as the Raster canvas and clears them on release or cancellation.

Raster Layers restores scroll position after group updates. Vector Layers remembers its scroll position when rebuilt. Group blend/opacity opens a second popup beside the group menu with Back and live changes; the underlying Layers panel remains visible. Raster Symmetry and Drawing Guides now include Back to Canvas settings. The standalone guide popup also has Back.

Regression checks cover a scrolled list of 22 folders, live opacity without replacing Layers, Back returning to the group menu, and visible reference sampling feedback. Targeted Android suite: 29 tests passed. Build and static APK checks passed. APK: releases/inkstone-0.21-local-ui-fixes-debug.apk. Local only; physical tablet interaction remains to be tried.
