package art.inkstone

/**
 * Brush and category names used before 0.25. Saved preferences, recent-brush lists and custom
 * brushes may still hold them, so they are translated to the current names when read.
 */
internal object LegacyNames{
    val brushes=mapOf(
        "Soft Brush" to "Cloud Soft","Medium Brush" to "Cloud Medium","Medium Hard Brush" to "Cloud Medium Firm","Hard Brush" to "Cloud Firm",
        "Soft Blend" to "Blend Soft","Medium Blend" to "Blend Medium","Medium Hard Blend" to "Blend Medium Firm","Hard Blend" to "Blend Firm",
        "Soft Airbrush" to "Mist Soft","Medium Airbrush" to "Mist Medium","Fine Airbrush" to "Mist Fine",
        "Studio Pen" to "Ink Pen","Technical Pen" to "Drafting Pen","Dry Ink" to "Dry Nib","Fade Brush" to "Fading Pen","Fine Liner" to "Fineliner","Chisel Marker" to "Chisel Tip",
        "Graphite" to "Graphite Stick","Soft Pencil" to "Soft Lead",
        "Chalk" to "Sidewalk Chalk","Expressive Line" to "Loose Line","Pastel" to "Pastel Stick",
        "Round Paint" to "Round Sable","Flat Brush" to "Flat Shader","Water Wash" to "Clear Wash","Wet Paint" to "Wet Mix","Gouache" to "Matte Gouache",
        "Opaque Round" to "Opaque Sable","Soft Glaze" to "Thin Glaze","Dry Bristle" to "Scumble Bristle","Wide Bristle" to "House Brush",
        "Bristle" to "Hog Bristle","Palette Ribbon" to "Ribbon","Pastel Threads" to "Threads",
        "Monoline" to "Even Line","Flat Lettering" to "Flat Nib","Broad Lettering" to "Broad Nib",
        "Compressed Charcoal" to "Pressed Charcoal","Willow" to "Twig Charcoal","Soft Charcoal" to "Smoky Charcoal",
        "Broken Marks" to "Scatter Marks","Dust" to "Stardust","Hard Edge" to "Steel Edge","Soft Glow" to "Halo",
        "Dry Surface" to "Dry Plaster","Leather Grain" to "Leather","Linen Weave" to "Linen",
        "Leaf Scatter" to "Falling Leaves","Moss Stipple" to "Moss","Soft Grain" to "Pollen",
        "Fine Mist" to "Fine Spray","Wide Spray" to "Wide Can","Rough Grain" to "Rough Tooth",
        "Etching" to "Etched Line","Faded Ink" to "Old Ink","Halftone Print" to "Halftone",
        "Diluted Wash" to "Pale Wash","Soft Wash" to "Wet Wash",
    )
    val categories=mapOf(
        "Sketching" to "Pencils","Inking" to "Pens","Drawing" to "Lines","Painting" to "Paints","Artistic" to "Expressive",
        "Calligraphy" to "Lettering","Airbrushing" to "Airbrush","Textures" to "Texture","Abstract" to "Shapes",
        "Charcoals" to "Charcoal","Elements" to "Nature","Spraypaints" to "Spray","Materials" to "Surfaces",
        "Vintage" to "Retro","Luminance" to "Glow","Industrial" to "Grit","Organic" to "Botanical","Water" to "Watercolour",
    )
    fun brush(name:String)=brushes[name]?:name
    fun category(name:String)=categories[name]?:name
}
