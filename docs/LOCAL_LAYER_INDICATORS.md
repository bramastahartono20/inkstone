# Local layer indicators, tutorials and recording

Clipped raster layers show a downward connecting arrow. Alpha Lock appears next to the layer name. Tutorials draw a scaled copy of the actual current workspace view, with a pulse positioned from the actual control location, replacing the hand-drawn diagram.

Raster recording now requests intermediate stroke frames and allows capture at up to 12 fps rather than only recording after strokes with a one-second throttle. Capture remains best effort with one pending JPEG and the existing recording quota. This is not guaranteed continuous real-time recording; older recordings retain their missing intermediate states. MP4 export remains H.264.

Eight Android host checks passed, plus a native regression drawing repeatedly with clipping/Alpha Lock then disabling both and verifying paint and eraser work. The user-reported session freeze, which disappears after reopening artwork, was not reproduced and remains unresolved. This is not a confirmed fix for that report. No publication or physical Android test was performed.
