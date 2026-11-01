# Local wet contact mixing

Wet Paint Mixing now combines the carried brush color with a small spatial pigment field sampled from the pre-stroke layer. This preserves local differences across the brush footprint where strokes overlap, rather than applying the single reservoir color uniformly to the whole tip. The 9 by 9 field interpolates pigment colors through the contact area. Sampling the pre-stroke surface avoids repeatedly picking up newly deposited ink during one stroke.

The existing brush tip, strength, pickup, wetness and gradual refill behavior remain. Contact mixing runs only with wet mixing enabled, on ordinary paint tips, without changing erasers, masks, smudge or multicolor strand layouts. This is an approximation of wet contact, not a fluid simulation with drying, diffusion or paper absorption.

126 CPU/shader tests and 149 Android host tests passed, including subpixel contact continuity, overlapping washes, clear-paper color, refill and exact undo/redo. The APK libraries match the rebuilt native files and its signature verifies. No physical Android test or GitHub publication was performed. The previously reported drawing freeze remains unresolved.
