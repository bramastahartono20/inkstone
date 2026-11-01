# Local performance revision

This revision follows reports of fast strokes stuttering across all brushes, slow Smart Shape resizing, and worsening lag on a Xiaomi Pad 7 with more than 20 layers. It has not been published or measured on that device here.

## Changes

- Raster frame compositing prepares group traversal once per patch, instead of finding group ancestry for every pixel. Normal blending has a shorter CPU path, including the original behavior at nearly transparent edges.
- A cache reuses the flattened backdrop beneath the active layer where composition permits it. It stores at most 512 tiles of 64 × 64 pixels (8 MiB), holds weak references to source planes, and invalidates on pixel/mask changes, visibility, opacity, blend, layer order, dimensions, or background changes. Clipped active layers and isolated group composition use the general renderer. Small CPU patches use the cache; large eligible patches can still use the GPU.
- GPU brush dabs accumulate across input packets and flush for a rendered frame, a completed stroke, a bounded batch, or an operation that reads pixels. Each pen packet no longer forces its own synchronous GPU readback. Samples are retained in order.
- Brush symmetry uses a fixed stack array instead of a heap allocation for every stamp. CPU radial distance uses a direct square root for the bounded stamp footprint. Brush-effect JSON is cached until its settings change.
- Smart Shape preview replaces the existing stroke transaction in place. It restores the previous painted region, retains the original undo snapshot, and marks the union of old/new bounds as damaged. Resizing no longer cancels/recreates a full-canvas transaction on every move. Preview coordinates are also reused between UI draws. Commit remains one undo step; cancel restores the original artwork, masks, and exterior pixels.
- Sparse stroke undo records store changed tiles when that is smaller than the stroke's enclosing rectangle. Large empty spaces between marks are excluded. Dense edits keep contiguous history. The 64-step history limit and archive support are retained.
- Replaced layer thumbnail bitmaps are recycled after their UI replacements are installed.

## Host workload measurements

`cargo run --offline --release -p ink-engine --example frame_stress` exercises 24 painted layers, 80 rapid strokes, and 640 partial frame updates. `--shapes` exercises 120 resizes on a 1600 × 1200 canvas. `--history` exercises 100 long diagonal strokes on a 2048 × 1536 canvas. The comparison source is the preceding local Inkflow revision, before these performance changes. Runs were sequential, without the Android/GPU test suites running concurrently.

| Workload | Previous local revision | This revision |
| --- | ---: | ---: |
| 24-layer patch render, p95 | 5.17 ms | 0.063 ms |
| 24-layer stroke including eight patch updates, first ten average | 25.04 ms | 3.44 ms |
| Same workload, last ten average | 26.09 ms | 3.62 ms |
| Smart Shape resize including patch render, p95 | 19.92 ms | 1.57 ms |
| Mean pixels transferred per shape resize | 1,904,117 | 31,656 |
| Long diagonal stroke plus undo handling, p95 | 14.18 ms | 6.87 ms |

These are host CPU workload timings, not Android frame rates or pen-to-display latency. Warm cache reuse is especially beneficial in the 24-layer workload. Other layer structures can take the general compositing path. Recheck fast curves, large soft brushes, Smart Shape resizing, and a longer session on the Xiaomi Pad 7.

The test/build/packaging evidence is in `artifacts/local-performance-verification.json`. The local APK is `releases/inkstone-0.22-local-performance-debug.apk` and retains the preview package and signing identity.
