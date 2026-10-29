# Known issues

## Current 0.21

The [0.21 release notes](RELEASE_0_21.md) and [bug-hunt report](BUG_HUNT_0_21.md) describe the current implementation. All 16 confirmed audit findings are fixed. Physical Android stylus/Vulkan performance, animation pacing and thermals remain unverified. Exterior storage is bounded and exports crop to the artboard; selections remain artboard-based. Raster folders can nest; Vector folders/stacks remain one level. Mixing is approximate and PSD group appearance may be baked. Older sections below preserve historical feedback.

## Historical preview12

Background colour uses a shared editor picker and selecting a color restores background visibility. Light interface uses shared panel/control colors. Multicolor uses an editor-owned panel. Canvas Atlas shares the editor camera and shows inactive documents as bounded previews. Group blend/opacity and personal brushes are available. Physical Android testing is still required for stylus behavior, Fade starts, drag timing, navigation feel and Vulkan performance. Wet mix is an approximation, isolated Raster group compositing uses CPU patches, and nondefault group appearance is baked to a single PSD layer. Atlas positions, gallery metadata and personal brush libraries stay device-local. Groups/stacks are one level. See [current release instructions](LOCAL_PREVIEW_019_12.md). Older sections below record historical feedback rather than the current inventory.

## Local preview6 — awaiting device testing

[Preview6](LOCAL_PREVIEW_019_6.md) addresses the faint remaining cap on slow Fade starts, adds animated layer insertion lines and boundary-based folder exit, and removes Outside groups. It also adds local gallery sorting, tags, named stacks/counts and animated artwork drag/drop. Gallery library metadata is device-local and separate from exported artwork. Physical stylus behavior and animation frame pacing still need retesting on the reporting devices.

## Local preview5 — awaiting device testing

The [unpublished preview5](LOCAL_PREVIEW_019_5.md) addresses the opaque Fade start cap and replaces folder-menu-only organization with long-press layer drag/drop in both editors. It also adds named Undo/Redo feedback, drawing guides and basic Raster Liquify. User feedback on preview4 described the other changes as appearing okay; the new build still needs physical-device retesting. Guides are overlays with device-wide settings, and Liquify uses CPU raster resampling with a limited radius and no editable Vector deformation. See the preview instructions for access and limits.

## Local preview4 — awaiting device testing

The unpublished [preview4 build](LOCAL_PREVIEW_019_4.md) implements per-tool persistent Recent history, category changes without rebuilding the category scroller, speed-sensitive constant-width Fade and smoothly interpolated Warp. It also adds basic editable Raster/Vector folders. Local regression tests pass; the reporting devices still need retesting. Groups are one level, with numeric offset movement and no group blend/opacity, nested folders or gesture group scaling. PSD/SVG folder hierarchy export is not implemented.

## Device feedback — 26 October 2026

The following describes the published 0.1.9-preview3. It is retained as the baseline for the local preview4 changes above.

- **Recent brushes:** Recent currently shows only the active brush rather than a history of multiple brushes.
- **Fade Brush:** the user reports that the fade still does not behave correctly. The intended behavior is a fading tail with constant width and unrestricted stroke length. Framework/native regression tests do not establish that the device result meets that expectation.
- **Brush-category scroll jump:** selecting a category lower in the library can recreate its list and animate from the top back down to the category. The intended behavior is to retain the category list's scroll position and update only the brush list. Current auto-scroll remains unchanged for this release.
- **Layer groups:** editable layer folders/grouping are not implemented in Raster or Vector mode. SVG export's named layer `<g>` elements represent individual layers; they are not an in-app layer-folder feature.

## Vector color selection and fill — pending device retest

On the user's device, changing the Vector color could update the displayed color code without updating the effective color, and dragging the color onto a closed shape did not reliably recolor it. This affects 0.1.8.

The 0.1.9-preview3 build includes the local fix introduced in preview1: wheel and valid hex changes update active ink, swatches and picker consistently. An editor-event regression test confirms that a real toolbar drag-fill uses the selected hex color and Undo/Redo restores the fill. Physical retesting on the reporting device is still needed.
