# Local Paint Mixing, tutorials and time-lapse

This local build includes the preceding performance work. It has not been published to GitHub.

## Paint Mixing

The brush picks up paint already on the active layer. On clear areas, fresh selected color gradually refills its reservoir, even at maximum Wetness. Crossing another painted area picks up that color again without lifting the pen. Wetness changes how long pigment is carried; Color pickup changes how strongly the surface loads the brush; Mix strength controls the visible effect.

The reservoir now retains floating-point pigment values instead of rounding back to an 8-bit color after each stamp. This prevents small refill increments from getting stuck. A weighted nine-point, bilinear, alpha-aware footprint smooths pickup across pixel boundaries. Color blending remains a compact RYB approximation, not a spectral pigment simulation.

Paint Mixing uses the current drawing brush, including its size, pressure and tip. Its panel has no separate brush-tip option. The toolbar icon has a soft green ring while mixing is enabled and a gray ring while disabled. Selecting the ordinary brush button turns mixing off as before.

## Tutorials

First use shows a dismissible welcome card. Tutorials can always be opened from Help in the gallery, Actions → Help in Raster, or Actions → Tutorials in Vector. Six short lessons cover drawing, colors/mixing, layers/groups, Smart Shape/transforms, Canvas Atlas, and saving/time-lapse. Each lesson has Back/Next navigation, and completion is remembered locally.

## Time-lapse

- Raster: Actions → Video. Vector: Actions → Time-lapse.
- Recording is stored per artwork, with new-artwork defaults in Custom Canvas → Time-lapse settings. Existing artwork's recording switch keeps its own setting.
- Artwork-only frames are captured after edits, at most once per second, with consecutive duplicates removed. Raster captures rendered committed edits; Vector snapshots after a short idle period. No menu, reference window, microphone or screen recording is used.
- Frames are retained locally across closing/reopening. Existing progress before recording was enabled cannot be reconstructed. The separate recording is not embedded in an exported Inkstone or PSD file.
- Capture is capped at a 960-pixel long edge. MP4 uses H.264 at 12 frames per second with a short initial and final hold. Transparent areas are rendered against white. Frames with different document sizes are fitted into the export frame.
- Android's hardware encoder is preferred. A pinned JCodec 0.2.5 software encoder provides an offline fallback. The software export has been encoded, demuxed and decoded independently with FFmpeg in host tests; the Android hardware encoder still needs verification on a physical tablet.
- Clear recorded frames requires confirmation and preserves the artwork. A recording pauses at 3,600 frames or 256 MiB rather than discarding older frames. Existing frames can still be exported.
- Video encoding happens during export, not continuously while drawing. Capture queues are bounded to one pending image, plus an explicit export snapshot. Frame compression and video export run outside the UI thread. Export progress appears in the Video panel, and MP4 uses the existing Android file picker.

## Validation

Native tests cover maximum-wetness recovery, mixing again after a clear area, subpixel pickup and exact undo/redo. Android tests cover brush reuse, indicator state, welcome/lesson navigation, recording persistence/pause/deduplication, recording only committed edits, per-artwork defaults, color conversion, and actual H.264 MP4 export. `artifacts/timelapse-export-verification.json` records independent decode evidence. Full build evidence is recorded in `artifacts/local-mixing-timelapse-verification.json` when packaging completes.
