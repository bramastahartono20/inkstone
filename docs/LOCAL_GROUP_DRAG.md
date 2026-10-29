# Local group dragging

Group appearance uses screen-to-activity coordinates so its popup sits immediately left of the group menu, below the top bar, rather than at the far left edge.

Move group contents replaces numeric offsets. Raster previews all descendant layers in document coordinates using a bounded transform snapshot; dragging does not create history entries. Done commits one native group translation; Cancel restores the preview. Translation includes masks and exterior tiles. Vector also supports direct dragging, with one commit per completed drag and Undo to restore.

Regression coverage includes moving a Raster group while an unrelated empty layer is active and checking one Undo entry. The complete Android test suite passed. Native engine binaries were unchanged. Local APK: releases/inkstone-0.21-local-group-drag-debug.apk. Physical tablet testing remains outstanding.
