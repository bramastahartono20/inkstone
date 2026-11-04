# Google Play listing for Inkstone

Copy each part into Play Console. Images are in `artifacts/store/`. Regenerate them with `StoreScreenshotsTest`.

## Store listing (Grow users → Store presence → Main store listing)

**App name** (30 characters max)

```
Inkstone: Drawing & Painting
```

**Short description** (80 characters max)

```
Draw and paint on Android tablets with pressure pens, layers and 50 brushes.
```

**Full description** (4000 characters max)

```
Inkstone is a drawing and painting app for Android tablets and pressure-sensitive pens. It runs on its own drawing engine, written in Rust and accelerated by the GPU, so big brushes and fast strokes stay smooth.

BRUSHES
• 50 brush presets for sketching, inking, painting, airbrushing, calligraphy, charcoal and textures
• Pressure, flow and stabilization controls
• Brush Studio for making your own brushes, including multicolor brushes
• Eraser and smudge with the same brush library

PAINT MIXING (EXPERIMENTAL)
• Mix new paint with the colors already on the canvas
• Adjust strength, pickup and wetness

LAYERS
• Many layers, even on large canvases
• 15 blend modes, opacity, alpha lock and clipping masks
• Nested folders with drag and drop
• Adjustment layers that change everything below them

COLOR
• Color wheel, square picker, hex and RGB input
• Saved palettes and recent colors
• ColorDrop fill: drag a color into an area and slide to adjust the threshold
• Eyedropper: hold on the canvas to pick a color

SELECT AND TRANSFORM
• Freehand, rectangle, ellipse and automatic selection
• Feather, invert and saved selections
• Freeform, uniform, distort and warp transforms
• Liquify: push, pinch, expand and twirl

MORE
• Perspective and symmetry guides
• Time-lapse video export
• Canvas Atlas: see and arrange all your artwork in one workspace
• Experimental vector mode with SVG export
• Export to PNG, JPEG, PSD and Inkstone's own format
• Palm rejection, pen-only mode and gesture undo and redo

PRIVATE BY DESIGN
No account, ads, tracking or subscription. Inkstone has no internet permission. Your artwork stays on your device.

Inkstone is in early development. Features are still being added and some may change. Please keep backups of important artwork, and report bugs at github.com/bramastahartono20/inkstone/issues.
```

**Graphics**

| Field | File |
|---|---|
| App icon (512 × 512) | `artifacts/store/icon-512.png` |
| Feature graphic (1024 × 500) | `artifacts/store/feature-graphic.png` |
| Phone screenshots | `artifacts/store/phone-1-canvas.png` … `phone-4-brushes.png` |
| 7-inch tablet screenshots | `artifacts/store/tablet-1-canvas.png` … `tablet-5-gallery.png` |
| 10-inch tablet screenshots | the same tablet files |

**App category:** Art & Design. **Tags:** Drawing, Painting, Art.

**Contact details:** email `bramastahartono20@gmail.com` (shown publicly). Website: `https://github.com/bramastahartono20/inkstone`.

## App content (Policy and programs → App content)

| Section | Answer |
|---|---|
| Privacy policy | `https://github.com/bramastahartono20/inkstone/blob/main/PRIVACY.md` |
| App access | All functionality is available without special access |
| Ads | No, my app does not contain ads |
| Content rating | Category: All Other App Types. Answer No to every question. The rating should be Everyone / PEGI 3. |
| Target audience | 13–15, 16–17 and 18+. Leave out under-13 ages, so the Families policy doesn't apply. |
| Data safety | Does your app collect or share any of the required user data types? **No.** This shows "No data collected" and "No data shared". |
| Government apps | No |
| Financial features | My app doesn't provide any financial features |
| Health | My app doesn't have any health features |
| News app | No |

## Closed test release notes (0.24)

```
First test release on Google Play. Big, fast strokes are smoother, a canvas holds many more layers, and adjustment layers are easier to add. Please report any problems.
```
