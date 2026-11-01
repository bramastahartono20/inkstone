# Local memory and adjustment revision

Not published. APK: releases/inkstone-0.22-local-memory-adjustment-fix.apk.

Stroke undo baselines now share the original pixel plane through Arc instead of allocating another full canvas at stroke start. History extracts changed rectangles directly from that shared baseline. Cancel and fade replay preserve the same pixels.

Native document saving serializes pixel runs directly to the writer instead of holding all encoded pixel runs in memory. The Android bridge streams serialization through gzip before allocating the Java byte array. Loading accepts both legacy JSON and this compressed payload, with a 128 MiB decompression cap. This reduces save-time memory peaks and the data crossing JNI; save latency and device memory limits still need physical testing. Existing outer Inkstone file compression is retained.

Adjustment sliders coalesce pending preview values, avoid time-lapse captures for intermediate preview updates, and commit when leaving the panel or before a save. The main Adjustments menu edits the current layer live; Layers has a new Adjust button for adding color adjustment layers. Blur remains a live raster operation.

Validation: 130 Rust tests passed, including shared stroke-baseline identity and compressed save/load pixel equality. Android Kotlin compilation, lint and debug assembly passed. Static APK/ELF checks and native-library byte matches passed. Signing certificate matched the existing debug key. Android host UI tests could not run successfully: after restoring the missing temporary test SDK from the persistent copy, Robolectric failed with an UnsatisfiedLinkError in RenderNode nativeBinding. No UI test pass or physical Xiaomi Pad 7 crash resolution is claimed for this APK.
