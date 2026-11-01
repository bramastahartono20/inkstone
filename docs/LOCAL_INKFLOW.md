# Inkflow engine experiment

Inkflow is the working name for this local revision of Inkstone's Rust raster drawing engine. It is not a new public release or a claim of name availability. Vector path editing keeps its separate pipeline.

## Stroke changes

- Causal cubic interpolation carries the previous tangent through gentle turns. Adaptive subdivision keeps geometric error below 0.15 document pixels, with bounded subdivision. Sharp corners and reversals keep their direction. The engine does not wait for a future input point.
- Stabilization uses elapsed time and measured speed. Slow input gets more filtering; fast movement catches up sooner. A short final segment reaches the last accepted pen coordinate on release.
- Timed pressure and tilt use a 7 ms response. The first moving event can correct an anomalously high contact pressure before the first stamp is deposited. A stationary tap still paints on release. This waits for one moving event at stroke start; actual pen latency needs device testing.
- Stamp spacing follows pressure-adjusted diameter. Soft brush tips use 2.2% diameter spacing, compared with the previous 3.5%, with coverage compensation to preserve flow. The minimum step is 0.25 document pixels. Smudge keeps its previous spacing and transport behavior.
- Coverage compensation is shared between the CPU painter and GPU shader, including soft erasers and selection coverage. Repeated stationary events do not deposit extra ink. Distinct positions with equal Android timestamps still draw, including a mid-stroke pen-button tool switch.
- Android tests now declare the host native library as an input, so rebuilding Rust invalidates cached test results.

## Local checks

Commands and results are recorded in `artifacts/local-inkflow-verification.json`. Native checks cover gentle curves, reversals, time-based filtering, pressure transitions, contact blobs, taps, equal timestamps, flow, history, and CPU/GPU parity. Android checks exercise the JNI bridge and existing editor workflows. The host probe excludes Android event delivery and display latency; it is not a tablet performance measurement.

`inkflow_probe` renders five actual engine strokes: fine pen, pressure pen, airbrush, wash, and a constant-width fade. Run with `--gpu` to exercise a physical GPU. Optional `INKFLOW_PROBE_OUTPUT` sets the output PPM path.

## Try on a tablet

Install `releases/inkstone-0.22-local-inkflow-debug.apk`. Its application ID, version and signing identity match the current preview, allowing an update over it.

Try slow curves, fast circles, light-to-heavy pressure, tiny taps, and long fade strokes. Compare stabilization at low and high settings. Check the S Pen eraser button during a stroke, and test large airbrush/wash strokes on a layered artwork. Existing artwork data is not migrated. This local revision has not been published to GitHub or tested on a physical Android tablet here.
