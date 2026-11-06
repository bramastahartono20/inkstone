# Inkstone 0.25 — Brush Import, New Look & Renamed Library

Inkstone can now import `.brush` and `.brushset` files, the interface has its own darker look with a teal accent, and the brush library has new names.

## Brush import

- Brush Library → **+** → **Import .brush / .brushset** adds the brushes to a category named after the set.
- Imported brushes paint with their own tip shape and grain, on both the GPU and the CPU, and also work as eraser and smudge.
- Grain can stay fixed to the canvas or roll along the stroke, so pattern brushes follow the direction you draw.
- Random rotation, size, opacity and scatter, speed → opacity, minimum size and opacity under light pressure, and colour mixing are read from the file.
- Brushes that name an image built into another app get Inkstone's own soft round, hard round, grunge or charcoal image instead.
- Brush Studio has new sliders for imported brushes: Spacing, Rotation, Follow stroke, Random rotation, Random size, Random opacity, Scatter, Speed → Opacity, Grain scale and Grain follows stroke.
- The importer reads the brush settings that have been worked out so far. Size and grain scale are approximations; some brushes need resizing after import.

## New look

- Darker greys throughout, with a teal accent in place of blue on selected tools, buttons and lists. The accent keeps white text readable in both themes.
- New Paint Mixing (palette and brush) and Smudge (hand) icons, matched in size and weight to the other tools.
- **Gallery** is now **Home**.

## Renamed brushes and features

- Brush categories: Pencils, Pens, Lines, Paints, Expressive, Lettering, Airbrush, Texture, Shapes, Charcoal, Nature, Spray, Surfaces, Retro, Glow, Grit, Botanical and Watercolour.
- All 58 built-in brushes have new names, for example Ink Pen, Drafting Pen, Dry Nib, Cloud Soft and Blend Medium.
- ColorDrop is now **Drop Fill**.
- Your chosen brushes, recent brushes and custom brushes carry over to the new names automatically.

## Fixes

- Four Android tests were outdated or flaky and now pass: two expected the old save format and autosave delay, one used a different Robolectric graphics mode, and one opened the Layers panel before it had drawn.

Download `inkstone-0.25-debug.apk` and install it over the existing preview, or update from the Google Play closed test. Preview package `art.inkstone.preview`, Play package `art.inkstone`, version code 38, Android 8.0+, ARM64 and x86-64.
