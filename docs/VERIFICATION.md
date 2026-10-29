# Verification — 29 October 2026

## 0.21 release candidate

- 103 Rust tests passed; the 12 physical GPU regressions also passed locally using Metal. 132 Android framework/JNI tests passed. Lint: 0 errors, 127 warnings. Native and Android builds passed; device Vulkan/stylus testing is separate.
- Package `art.inkstone.preview`, version `0.21`, code 34; unchanged v2 certificate. Both packaged ABIs match source-built outputs, with fourteen JNI methods, 16 KB ELF alignment and matching license assets.
- APK: 15,737,306 bytes; SHA-256 `70cdbb46eda55150a17c3bbde74ba471b3d8bed79aa62529c186c46fcc69aba5`.
- [Audit findings and reproductions](BUG_HUNT_0_21.md), [release instructions](RELEASE_0_21.md), [APK verification](../artifacts/release-0.21-verification.json). GitHub publication and CI results are checked separately.

## 0.20 release candidate

- 122 Android framework/JNI tests and 77 Rust tests passed locally. Build/lint passed with 0 errors and 125 warnings. Raster mixing, brush library and Brush Studio screenshots inspected; Android hardware remains untested.
- Package `art.inkstone.preview`, version `0.20`, code 33, same v2 signer; both ABIs match source-built outputs, fourteen JNI methods, 16 KB ELF alignment and matching license assets. APK: 10,458,265 bytes; SHA-256 `bf4f6896cb80da3d6afe393320696e295f470c2d4f830c41bca97d608b6bb657`.
- [Release instructions](RELEASE_0_20.md); [package evidence](../artifacts/release-0.20-verification.json). GitHub publication is verified separately.

## Unpublished local 0.1.9-preview14

- 122 Android framework/JNI tests and 77 Rust tests passed. Covers independent Paint Mixing controls, editor-owned brush workflows, inactive Atlas eyedropper without document switching, and layer settlement from release coordinates in both directions.
- Build/lint passed: 0 errors, 125 warnings. Mixing, brush management and Brush Studio screenshots inspected; physical Android behavior remains untested.
- Package `art.inkstone.preview`, code 32, same v2 signer; both ABIs match source-built outputs, fourteen JNI methods, 16 KB ELF alignment and matching license assets. APK: 10,987,919 bytes; SHA-256 `1722011b2e4d4bfcca4ddefdc601aad606e958121c1032fd67c3845dc9315a5f`.
- Local only. [Instructions and limits](LOCAL_PREVIEW_019_14.md). Atlas samples saved previews, not full-resolution inactive engines.

## Unpublished local 0.1.9-preview13

- 119 Android tests, 77 Rust tests and 12 desktop Metal GPU checks passed. New checks cover pen-only direct eyedropper, normal-weight pen-hover Gallery, 170% font layout, recent ink persistence/cancellation and paint-load controls, carry behavior and exact Undo/Redo.
- Android build/lint passed: zero errors, 123 warnings. Large-font Raster/Vector screenshots inspected; Android hardware remains untested.
- Package `art.inkstone.preview`, code 31; same v2 signer, both ABIs, fourteen JNI methods, 16 KB alignment. Packaged native binaries match source-built outputs; license assets match source. APK: 10,458,277 bytes; SHA-256 `14cb8404d17a56869c8676dcfeced191fe86193f8ede6c4f8c56eaa52a6799d5`.
- Local only. [Instructions and limits](LOCAL_PREVIEW_019_13.md).

## 0.1.9-preview12 release checks

- 113 Android framework/JNI tests passed. Covers background visibility, rapid color changes, native-free picker, custom hex, Undo, light-mode toolbar/history rotation, gallery/canvas setup and preservation of actual color swatches.
- Android build/lint passed: zero errors, 117 warnings. Raster/Vector light-mode framework screenshots inspected. Physical Android testing remains pending.
- Native engine unchanged from preview11 (75 Rust and 12 desktop Metal checks); packaged native binaries and licensing assets match validated local files.
- Package `art.inkstone.preview`, code 30, same v2 signing certificate, both ABIs, fourteen JNI methods and 16 KB alignment. APK: 10,952,463 bytes; SHA-256 `7c14db00470f6d78f8eb2eaf2314dfd92d9c2d9e73df0c0e297819b5fcb02c73`.
- [Release instructions](LOCAL_PREVIEW_019_12.md); final GitHub publication is verified separately.

## 0.1.9-preview11 release checks

- 108 Android framework/JNI tests, 75 standard Rust tests and 12 physical desktop Metal GPU tests passed. Multicolor panel tests verify editor-owned controls and applied color, tip, layout and cycle persistence; Atlas/group/library checks are retained.
- Android build/lint passed: zero errors, 113 warnings. The framework-rendered Multicolor panel was visually inspected. Android device performance remains untested.
- Package `art.inkstone.preview`, code 29, `0.1.9-preview11`, same v2 signer; both ABI libraries match source-built binaries, fourteen JNI methods, 16 KB alignment. License assets match source.
- APK: 10,944,538 bytes; SHA-256 `070d9441ae6dafc3b295e392d8ee04293c4c5e5abffd9801aeef6713ba783170`. Machine-readable evidence: `artifacts/preview11-full-test-summary.json` and `artifacts/apk-verification.json`.
- Publication is verified separately. [Release instructions](LOCAL_PREVIEW_019_11.md).

## 0.1.9-preview7 release checks

- 95 Android framework/JNI tests and 67 standard Rust tests passed. New regression checks verify 20 dp group-member indentation in Raster/Vector and returning to root alignment.
- Android build/lint: zero errors, 97 warnings. Grouped Raster screenshot inspected. Native binaries are unchanged from preview6's ten passing physical Metal GPU checks and independent SVG/PSD/masked PSD validation.
- Package `art.inkstone.preview`, version code 25, `0.1.9-preview7`; existing v2 signer retained. Both packaged ABI libraries match validated outputs, with fourteen JNI methods and 16 KB alignment. Bundled licensing files match source.
- APK `releases/inkstone-0.1.9-preview7-debug.apk`: 10,352,969 bytes; SHA-256 `3d5addd0e19859262afeda5f93166f847083b58d788a374b5c5de3ae48487952`.
- GitHub CI/release status is verified separately during publication. Physical Android testing remains pending. [Release instructions](LOCAL_PREVIEW_019_7.md).


## Unpublished local 0.1.9-preview6

- 94 Android framework/JNI tests, 67 standard Rust tests and ten physical Metal GPU tests passed. Includes slow Fade lead-in/taps, folder boundaries and sole-member exit, line/folder glow distinction, layer and gallery settlement, persistent tags/stacks, sort/filter, and actual artwork drops between stacks and Gallery.
- Android build and lint passed: zero errors, 97 warnings. Independent SVG, layered PSD and masked PSD validation passed. Gallery screenshots were inspected.
- Package `art.inkstone.preview`, version code 24, `0.1.9-preview6`; existing v2 signer retained. Both packaged ABI libraries match rebuilt outputs, with fourteen JNI methods and 16 KB alignment. Bundled license files match source.
- APK `releases/inkstone-0.1.9-preview6-debug.apk`: 10,352,825 bytes; SHA-256 `9c7fe7ef6a26602e8b8782442c57ebacea797444275065ed64c6e97e0e05abcb`.
- Local only; hardware drag/stylus/Vulkan and animation pacing still need device testing. [Preview instructions](LOCAL_PREVIEW_019_6.md).


## Unpublished local 0.1.9-preview5

- 88 Android framework/JNI tests, 65 standard Rust tests and ten physical Metal GPU tests passed. The final perspective-ray spacing adjustment also passed a focused guide test and rebuild. Coverage includes Fade start/taps, real layer drops, archive-safe history names, QuickShape names, vanishing point dragging, and Liquify modes/cancel/selection/one-step Undo.
- Android build and lint passed: zero errors, 96 warnings. Independent SVG, layered PSD and masked PSD validation passed. Guide and Liquify screenshots were inspected.
- Package `art.inkstone.preview`, version code 23, `0.1.9-preview5`. V2 signer retained; both packaged ABI libraries match rebuilt outputs, with fourteen JNI methods and 16 KB alignment. Bundled license files match current source.
- APK `releases/inkstone-0.1.9-preview5-debug.apk`: 10,302,769 bytes; SHA-256 `0f0d195eccb35225e861d8c13af080a85eb048ad123805a9231e00fe862f7586`.
- Local only. Physical Android pen/Vulkan and large-radius Liquify performance are unverified. [Preview instructions](LOCAL_PREVIEW_019_5.md).


## Unpublished local 0.1.9-preview4

- 84 Android tests, 61 standard Rust tests and nine physical Metal GPU tests passed. New coverage includes speed-sensitive Fade through timed/merged JNI input, CPU/GPU parity, continuous Warp tangents, group UI/save/history/member flags, persistent Recent history and category scroll retention.
- Android build and lint passed: zero errors, 95 warnings. Independent SVG, layered PSD and masked PSD checks passed. Live/committed Warp and group panel screenshots were inspected.
- Package `art.inkstone.preview`, version code 22, `0.1.9-preview4`; v2 signer retained. Both packaged native ABI libraries match the rebuilt outputs, with fourteen JNI symbols and 16 KB ELF alignment.
- APK `releases/inkstone-0.1.9-preview4-debug.apk`: 10,250,653 bytes, SHA-256 `2554cfad1419b01b17551726d0b447c8d83c68e0138e4b07864aa6b53a468979`.
- Local only; no push/tag/release. Android hardware input and Vulkan still require testing. See [instructions and group limitations](LOCAL_PREVIEW_019_4.md).


## 0.1.9-preview3 — PR #1 integration and release candidate

- Integrated contribution `f19aa0903aed00e6a84bc3e4e86d42cd712f73cc` by andikasujanadi into the preview2 working tree, with local compatibility fixes. Prepared for publication on 26 October 2026; Recent, Fade, category scrolling and missing layer folders are explicitly recorded in KNOWN_ISSUES.md.
- 79 Android-framework tests passed against the existing preview2 host JNI engine. Seven new integration tests cover full independent Paint/Smudge/Eraser profile persistence including Fade; preset-default migration; shortcut toggling and repeat rejection; Vector dialog key recording/reset/dismissal; left/right panel navigation; fit-animation interruption; and quick pinch with enabled/disabled/locked states. The stabilizer regression now follows the new return-to-Preferences flow.
- Android lint: zero errors, 95 warnings. Build succeeded. APK v2 signature matches the existing preview certificate. Package `art.inkstone.preview`, version code 21, version name `0.1.9-preview3`. Both ABI libraries match the already validated preview2 native outputs; fourteen JNI entry points, 16 KB ELF alignment. Bundled license notices match current sources, including contribution credit.
- APK: `releases/inkstone-0.1.9-preview3-debug.apk`, 10,184,161 bytes. SHA-256: `2d60f9b7bebec6b6b1303b1ccc591fd6f23792066bf2d185f2c9f787db5b8489`.
- Rust/native code is unchanged by this integration; preview2's 59 standard Rust and eight physical Metal GPU results below were not rerun. Physical Android stylus, keyboard and Vulkan testing remains pending. Instructions: [LOCAL_PREVIEW_019_3.md](LOCAL_PREVIEW_019_3.md).

## Unpublished local 0.1.9-preview2

- 72 Android-framework tests passed against the rebuilt host JNI library. New checks cover the six-group compact selection toolbar, one expanded group at a time, portrait scrolling, combined/feathered/saved selections, Automatic selection, clipboard paste across documents, Freeform/Uniform/Distort/Warp, snapping, reset/fit, interpolation, preview/commit parity, cancellation and Undo/Redo.
- 59 standard Rust tests and eight physical Metal GPU tests passed. Fade now keeps its width and supports arbitrary stroke length, with tail opacity applied at pen lift. Coverage includes tap/short strokes, input-batch independence, mid-stroke eraser settings, Undo/Redo, CPU/GPU parity and removal of stale live-tail pixels.
- Independent SVG, layered PSD and masked PSD fixture validation passed. Compact collapsed/expanded toolbar, feathered selection and live/committed warp screenshots were visually inspected.
- Android lint: zero errors, 93 warnings. APK v2 signature matches the existing preview certificate. Package `art.inkstone.preview`, version code 20, version name `0.1.9-preview2`. Both ARM64/x86-64 native libraries match rebuilt outputs, expose fourteen JNI entry points and use 16 KB ELF load alignment. Bundled license notices match source files.
- APK: `releases/inkstone-0.1.9-preview2-debug.apk`, 10,167,657 bytes. SHA-256: `8d308db5fe348c418f575fea4f53bdff0725d4b502a636c3ccca75ece7629778`.
- No commit, push, tag or GitHub release. Physical Xiaomi/Samsung stylus and Android Vulkan testing remains pending. Details: [LOCAL_PREVIEW_019_2.md](LOCAL_PREVIEW_019_2.md).

## Unpublished local 0.1.9-preview1

- 67 Android-framework tests passed against the rebuilt real host JNI library, including seven new regression tests. They exercise live Vector wheel/hex/preview/toolbar color and drag-fill with Undo/Redo; mid-stroke S Pen-style erasing with separate wide eraser settings and one Undo; once-per-press key mappings and persistence; legacy and modern button bits; mouse exclusion; the function chooser and direct Eyedropper; native fade/reset; and removal of the redundant finger setting with migration of old false values.
- 54 standard Rust tests passed. Seven physical Metal GPU tests passed, including a new fade/profile-switch comparison with CPU output (maximum channel difference bounded at three). Native fade tests cover input-batch independence, taper/cutoff, per-stroke reset, cancel, Undo/Redo, and mid-stroke eraser profile changes.
- Independent SVG, layered PSD, and masked PSD fixture validation passed. Framework screenshots of faded strokes, the stylus settings and a Vector shape filled with the live hex color were visually inspected.
- Android lint: zero errors, 89 warnings. APK build and v2 signing verified against the existing preview certificate. Package `art.inkstone.preview`, version code 19, version name `0.1.9-preview1`. ARM64/x86-64, fourteen JNI methods per ABI, 16 KB ELF alignment; packaged native binaries match the rebuilt outputs.
- APK: `releases/inkstone-0.1.9-preview1-debug.apk`, 10,007,545 bytes. SHA-256: `4d5295dd383a83af2007545783983e1f70fd2afe46f839191c147e8ef5e2add0`. Packaged source-available/artwork-use notices match the current license files and sole-author copyright notice.
- No commit, push, tag or GitHub release for these local changes. Physical Samsung S Pen and Xiaomi stylus/Vulkan behavior still need device testing. Details: [LOCAL_PREVIEW_019.md](LOCAL_PREVIEW_019.md).

## 0.1.8 release checks

- 60 Android-framework tests passed against the real host JNI library, including custom palette creation/selection/rename/delete, persistence, Studio copy-on-edit, event-dispatched color drops without canvas painting, and square gapless swatches. The palette screenshot was visually inspected with fixed visible tab navigation.
- 51 standard Rust tests passed. The unchanged native engine also passed all six physical Metal GPU tests in the preceding preview4 validation; Android Vulkan/stylus validation remains a physical-device task.
- Independent SVG, layered PSD and masked PSD fixture validation passed. Android lint has zero errors and 86 warnings (including programmatic View-constructor and untranslated-text warnings for the new palette UI).
- APK: `releases/inkstone-0.1.8-debug.apk`, 9,984,413 bytes, package `art.inkstone.preview`, version code 18. SHA-256: `ddbd49f91486401855944f03bd7eaccb980b4af9943c628bcc9157343eb94c89`.
- APK v2 signature matches the previous preview signer. ARM64 and x86-64 libraries match the rebuilt outputs, expose fourteen JNI methods each, and use 16 KB ELF load alignment.
- The supplied README image `artifacts/editor-tablet.png` is unchanged. The reported Vector effective-color/drag-fill bug is explicitly deferred and documented in [KNOWN_ISSUES.md](KNOWN_ISSUES.md).
- These are local checks; GitHub Actions status must be checked separately on the release commit.

## Local 0.1.8-preview4

- 51 standard Rust tests passed, plus all six opt-in physical Metal GPU tests on this Mac. New coverage checks 101 shared empty layers at 2048 × 1536, drawing and undo/redo, native save/reload, 81 shared duplicate layers, binary history with masks/shared buffers, and GPU strokes after 70 added empty layers.
- 57 Android tests passed against the rebuilt host JNI engine. Editor-dispatched stylus drawing works before and after 70 added layers with pen-only enabled; Vector supports 81 layers through save/reload and undo/redo. The resulting framework screenshot was visually inspected.
- Full regression testing caught an empty-layer GPU background initialization issue introduced by filtering empty layers; fixed by compositing empty canvases directly, then the complete suite passed.
- Android lint: zero errors, 84 warnings. Local APK build succeeded; version code 17, version name 0.1.8-preview4. V2 signature matches the previous preview signer. ARM64/x86-64, fourteen JNI entry points per ABI, 16 KB ELF alignment, packaged binaries match native build outputs.
- APK: `releases/inkstone-0.1.8-preview4-debug.apk`, 9,968,037 bytes. SHA-256: `c0fd94fa42802c7b2de1b3babfc2e9ffa2225d31e64a88560382d96fc96d63ae`.
- No fixed layer-count cap; live painted Raster data still has a memory budget. No claim of unlimited physical storage or device-tested performance. Not pushed or released on GitHub. Xiaomi stylus/Vulkan validation remains pending.

## Local 0.1.8-preview3

- 48 Rust tests passed; five physical GPU tests remain opt-in. Added exact 30-step backward/forward history recovery after cache spill, mask history recovery, cache cleanup, and failed-read preservation checks.
- 55 Android tests passed against the rebuilt host JNI engine. New tests cover visible pointer-following ColorDrop, Vector fill/closed-path persistence/SVG closure, transformed endpoint joining, locked/open-path rejection, two-/three-finger gestures with jitter and reordered finger release, canceled/panning gesture rejection, and 30-step Vector history.
- Existing independent SVG/PSD export validation passed (resvg-py and psd-tools). Framework-rendered Vector drag/fill and Raster ColorDrop screenshots inspected.
- Android lint: zero errors, 84 warnings. APK build and v2 signature verified with the existing preview signer; version code 16, ARM64/x86-64, fourteen JNI entry points per ABI, 16 KB ELF alignment. Packaged native libraries match the rebuilt binaries.
- APK: `releases/inkstone-0.1.8-preview3-debug.apk`, 9,936,033 bytes. SHA-256: `4ad372e1ba787a2bb2077a27dfd3e16741ff9ec43c683cc7d93d5e9a93d27dba`.
- Local preview only. Not uploaded to GitHub; physical Xiaomi stylus/gesture behavior and OS screenshot interception remain device-test items.

## Local 0.1.8-preview2 (previous checks)

- 49 Android tests passed: 29 Raster/shared, nine Vector, five workflow and six new interaction tests against the real host JNI engine.
- New coverage includes one-tap libraries, repeated gallery taps, first-frame readiness, canceled/failed loads and retry, save navigation, pen-only touch controls, Raster/Vector pan/zoom/rotate, ColorDrop and object editing.
- Android lint: zero errors, 83 warnings. Framework-rendered opening/saving overlays and the Eraser Library were visually inspected.
- APK build and v2 signature verified; version code 15, same preview signer, ARM64/x86-64, fourteen JNI methods and 16 KB ELF alignment. Packaged native libraries match the prior validated workflow libraries byte-for-byte.
- SHA-256: `fcf00946516ab4defd291f170b91d3704e200d776011198758a5b04472fb6284`.
- Local APK only; not uploaded to GitHub or tested on physical Xiaomi hardware. The earlier engine checks below were not rerun for these Kotlin-only fixes.

## Local 0.1.8-preview1 (prior engine checks)

The unpublished local APK adds Raster layer masks, centered symmetry, editable QuickShape, a Reference companion in Raster/Vector, and layered 8-bit RGB PSD import. See [LOCAL_WORKFLOWS.md](LOCAL_WORKFLOWS.md) for usage and limits.

- **46 Rust tests** passed; five physical GPU checks are opt-in.
- **43 Android tests** (29 Raster/shared, nine Vector, five new workflow tests) passed against the real host JNI engine.
- **One physical Metal test** passed for symmetry, masked CPU compositing and exact device-loss fallback without re-mirroring a batch.
- Independent psd-tools RAW/RLE/ZIP/prediction import fixtures matched within two channel levels. Independent export verification retained all original layer pixels and grayscale masks; maximum premultiplied composite error was about 1.03/255.
- Existing SVG and PSD fixture checks passed.
- **Lint:** zero errors, 82 warnings.
- APK v2 signature verified with the existing preview signer; version code 14, ARM64/x86-64, fourteen JNI entry points and 16 KB ELF alignment.
- `artifacts/apk-verification.json` has since been refreshed for preview2. It has not been uploaded to GitHub and has not yet been tested on Xiaomi.

## Published 0.1.7 checks (historical)

Version 0.1.7, package `art.inkstone.preview`. Local release checks passed:

- **39 Rust tests**, zero failures; four physical-GPU checks are opt-in.
- **38 Android tests** (29 Raster/shared, nine Vector), zero failures.
- **One physical Metal comparison** rerun for brush, eraser and smudge, including all eraser footprint families; passed.
- **Independent SVG/PSD fixture checks** passed.
- **Android lint:** zero errors, 75 warnings remain.
- APK packaging and signature checks are recorded with the release artifact.

## Coverage

Rust behavior tests cover pressure, procedural brush coverage, matching eraser footprints, edge hardness, selections, alpha lock, spatial smudge, ColorDrop, one-pixel edge cleanup, layers, history, persistence, PSD encoding and affine transforms. Both compute shaders are parsed and validated without requiring a GPU.

Android tests use SDK 35 Robolectric native graphics and the real host Rust JNI library. They cover gallery saving/reopening, PNG/JPG Gallery writes and failure cleanup, layered PSD, experimental Vector SVG, cubic node selection/handles/insertion, stabilization persistence, independent eraser presets, live photo transforms, canceled gestures, tooltips, color updates and portrait/landscape layout.

Independent resvg and psd-tools checks assess exported SVG at 1× and 2×, PSD layer metadata/pixels and merged previews. These checks do not establish every external editor's editing behavior.

The supplied `artifacts/editor-tablet.png` is preserved for the README. Other test images in `artifacts/` are desktop framework renders, not device screenshots.

## Hardware boundary

Physical Metal checks assess CPU/GPU brush and smudge agreement, compositing, transforms, history and recovery from intentional device loss. Some checks were run in earlier iterations; the release update reruns the brush/eraser/smudge comparison. Android Vulkan execution, sustained latency, thermals, pressure calibration, palm rejection, hover, stylus shortcuts and frame pacing require physical-device testing. Photoshop desktop interoperability has not been tested directly.

The renderer remains hybrid: GPU compute where available, CPU fallback, CPU bitmap readback/presentation and CPU ColorDrop/adjustments/export. See [GPU.md](GPU.md), [PERFORMANCE.md](PERFORMANCE.md) and [feature status](PARITY.md).

## Build outputs

The APK includes ARM64 and x86-64 libraries, thirteen JNI entry points and 16 KB-aligned ELF load segments. `artifacts/apk-verification.json` records APK size, hash and packaged native metadata. The preview uses the existing signing certificate so it can update an installed preview without clearing artwork.

GitHub Actions runs on pushes. Check its live status separately; local checks are not a claim that a remote workflow has passed.
