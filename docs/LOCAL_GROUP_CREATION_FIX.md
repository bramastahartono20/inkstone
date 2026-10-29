# Local group creation fixes

APK: `releases/inkstone-0.20-group-creation-fix.apk`.
SHA-256: `7846dc039df8e1704ee00e03f0bd07f41e9869e9538e52a9d26c382f5a5d10f6` (15,416,690 bytes).

This local build includes the preceding QuickShape, off-canvas rendering/transforms and nested raster-group fixes. It has not been published or tested on a physical tablet.

- The Layers header's Group button opens an Inkstone panel, with a name field, Location, Create group and Cancel. It no longer opens an Android AlertDialog.
- Repeated creation defaults to independent, empty folders at Top level, including when the active layer already belongs to another group. Location explicitly chooses a parent for a subfolder. Layer-specific group creation still wraps that chosen layer.
- Unique suggested names and the new folder's selection make successive creations visible. Blank names show an inline error; duplicate submission is disabled while the request runs. The keyboard Done action also submits.
- Late layer-thumbnail callbacks are guarded by panel revision. Opening a new-group/location form invalidates older refresh requests, including a delayed group-collapse refresh that previously replaced the form during interaction tests.
- New children expand their ancestors so they cannot be hidden in a collapsed parent. Group headers indent 20 dp per depth; layer rows indent another level. Native save/load and undo preserve the hierarchy.
- Rename group also uses the custom name form. This change does not redesign the experimental vector group editor or every other existing group-settings dialog.

Validation: 87 Rust tests passed (12 desktop GPU tests ignored); 128 Android unit/integration tests passed; lint had 0 errors. The UI test creates three root folders in sequence, then a child and grandchild, checks 20/40 dp group indentation and 62 dp layer indentation, verifies blank-name validation, keyboard submission and absence of a native AlertDialog. Rendered name form and nested group panel inspected locally. Both Android native libraries compiled and matched the APK; static APK and v2 signature checks passed.
