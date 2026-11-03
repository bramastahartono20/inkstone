# Inkstone 0.24 — Big Brushes, More Layers & Adjustment Layers

Large, fast strokes stay responsive, and a canvas can now hold many more layers. Adjustment layers are easier to add and recognise.

## Performance

- Brush dabs that cross the canvas edge stay on the GPU. Before, the first dab touching the edge moved the rest of the stroke to the CPU. A 400 px stroke along the edge took about half the time in testing.
- A new stroke no longer re-uploads the whole layer to the GPU. On a 2800 × 2000 canvas that was 22 MB at the start of every stroke.
- When you paint under other layers, painting and compositing share one GPU round trip per frame instead of two. Frame time roughly halved in testing.

## Layers and memory

- The layer memory limit follows the device's RAM: about 12% of total memory, between 256 MB and 1.5 GB. It was a fixed 256 MB, which allowed only about 10–12 painted 2000 × 3000 layers. A device with 8 GB holds about 40 such layers.
- Documents save to and open from files directly. Large artwork no longer passes through the Java heap, and a document can be larger than the old 128 MB limit. A save writes a temporary file first and then replaces the old one, so a failed save keeps the previous version.

## Adjustment layers

- Choosing an adjustment from the Layers panel always adds a new layer, named Adjustment Layer 1, 2, 3 and so on.
- An adjustment layer shows ◐ in its thumbnail and its type next to the name.
- It changes every layer below it. With Clipping Mask on, it changes only the layer directly below.
- Layer Options has a slider for an adjustment layer's strength.

## Fixes

- Clipped layers show their arrow again. The arrow is now drawn by the app, because the "↳" character was missing from some device fonts.
- The canvas no longer shows a faint grid of lines between display tiles at some zoom levels.
- The Tutorials icon on the gallery page is readable in the light theme.

Download `inkstone-0.24-debug.apk` and install it over the existing preview. Package `art.inkstone.preview`, version code 37, Android 8.0+, ARM64 and x86-64. The existing signing certificate is retained.

The performance and memory changes were tested on a Xiaomi Pad 7 in preview builds. Inkstone remains in early development. Experimental Vector and the existing export limits still apply. License terms are unchanged.

Verification: 164 Rust tests passed, including the physical-GPU tests. 156 of 159 Android tests passed. The three failures (two StudioTest and one LayerQueueTest) predate this release. APK signature, package and version, native libraries and 16 KB alignment were checked.
