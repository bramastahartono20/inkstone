package art.inkstone

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class BrushPreset(val name: String, val group: String, val kind: Int, val size: Float,
    val flow: Float = 1f, val pressureSize: Float = .8f, val pressureOpacity: Float = 0f, val fadeLength:Float=0f,val customId:String="",val opacity:Float=1f,val stabilization:Float=.12f,val hardness:Float=1f,val colorMode:String="Solid",val colorCycle:Float=600f,val wetMix:Float=0f,val palette:List<Int> = emptyList(),val colorLayout:String="Along",val wetPickup:Float=.35f,val wetness:Float=.5f,
    /** Image tip id in [BrushTips] and its settings, for kind 43 brushes. */
    val tip:String="",val tipSpacing:Float=.1f,val tipFollow:Float=0f,val tipRotation:Float=0f,val tipJitter:Float=0f,val grainScale:Float=1f,
    val tipSizeJitter:Float=0f,val tipOpacityJitter:Float=0f,val tipScatter:Float=0f,val grainRolling:Boolean=false,val tipSpeedOpacity:Float=0f,val tipDynamics:TipDynamics=TipDynamics())

val brushPresets = listOf(
    BrushPreset("Ink Pen", "Pens", 0, 12f),
    BrushPreset("Threads","Expressive",14,64f,.8f,.2f,.1f,colorMode="Palette",palette=listOf(0xffd78a99.toInt(),0xffe7c686.toInt(),0xff8bc6b6.toInt(),0xff939fd0.toInt()),colorLayout="Bristles"),
    BrushPreset("Ribbon","Expressive",7,45f,pressureSize=.2f,colorMode="Palette",palette=listOf(0xffed7768.toInt(),0xffebc66a.toInt(),0xff6096cc.toInt())),
    BrushPreset("Wet Mix","Paints",11,55f,.7f,.35f,.2f,wetMix=.45f),
    BrushPreset("Fading Pen", "Pens", 0, 28f, pressureSize=0f,fadeLength=150f),
    BrushPreset("Drafting Pen", "Pens", 0, 4f, pressureSize = .35f),
    BrushPreset("Dry Nib", "Pens", 3, 18f, .9f),
    BrushPreset("Graphite Stick", "Pencils", 1, 9f, .8f, .7f, .7f),
    BrushPreset("Soft Lead", "Pencils", 1, 22f, .65f, .5f, .8f),
    BrushPreset("Pressed Charcoal", "Charcoal", 3, 48f, .8f, .55f, .5f),
    BrushPreset("Twig Charcoal", "Charcoal", 3, 14f, .65f, .9f, .65f),
    BrushPreset("Round Sable", "Paints", 41, 42f, .55f, .7f, .2f),
    BrushPreset("Clear Wash", "Paints", 4, 90f, .8f, .4f, .6f),
    BrushPreset("Flat Shader", "Paints", 42, 55f, .7f, .6f, .2f),
    BrushPreset("Mist Soft", "Airbrush", 2, 110f, .7f, .25f, .7f),
    BrushPreset("Mist Fine", "Airbrush", 2, 32f, .9f, .4f, .5f),
    BrushPreset("Even Line", "Lettering", 0, 16f, pressureSize = 0f),
    BrushPreset("Flat Nib", "Lettering", 7, 26f, pressureSize = .9f)
) + additionalPresets()

val brushCategories=listOf("Recent","Pencils","Pens","Lines","Paints","Expressive","Lettering","Airbrush","Texture","Shapes","Charcoal","Nature","Spray","Surfaces","Retro","Glow","Grit","Botanical","Watercolour","Imported")
private fun additionalPresets():List<BrushPreset> {
    val air=listOf(
        BrushPreset("Cloud Soft","Airbrush",2,120f,.8f,.15f,.35f),
        BrushPreset("Cloud Medium","Airbrush",8,100f,.8f,.2f,.3f),
        BrushPreset("Cloud Medium Firm","Airbrush",9,90f,.8f,.2f,.25f),
        BrushPreset("Cloud Firm","Airbrush",0,80f,1f,.25f,0f),
        BrushPreset("Blend Soft","Airbrush",2,140f,.25f,.1f,.15f),
        BrushPreset("Blend Medium","Airbrush",8,120f,.25f,.1f,.15f),
        BrushPreset("Blend Medium Firm","Airbrush",9,100f,.25f,.1f,.15f),
        BrushPreset("Blend Firm","Airbrush",0,80f,.18f,.1f,.1f),
        BrushPreset("Mist Medium","Airbrush",8,75f,.55f,.2f,.5f)
    )
    val originals=listOf(
        BrushPreset("Loose Line","Lines",0,14f,1f,.9f),
        BrushPreset("Sidewalk Chalk","Lines",13,28f,.8f,.55f,.4f),
        BrushPreset("Pastel Stick","Lines",14,38f,.8f,.35f,.3f),
        BrushPreset("Hog Bristle","Expressive",11,54f,.8f,.5f,.2f),
        BrushPreset("House Brush","Paints",11,90f,.65f,.35f,.2f),
        BrushPreset("Opaque Sable","Paints",0,70f,1f,.5f),
        BrushPreset("Thin Glaze","Paints",2,160f,.3f,.15f,.3f),
        BrushPreset("Chisel Tip","Pens",12,28f,.85f,.2f),
        BrushPreset("Fineliner","Pens",0,3f,1f,.1f),
        BrushPreset("Smoky Charcoal","Charcoal",13,65f,.65f,.5f,.5f),
        BrushPreset("Rough Tooth","Texture",14,75f,.65f,.4f,.4f),
        BrushPreset("Scatter Marks","Shapes",3,48f,.6f,.7f,.3f),
        BrushPreset("Stardust","Nature",10,130f,.6f,.1f,.4f),
        BrushPreset("Fine Spray","Spray",10,100f,.75f,.15f,.4f),
        BrushPreset("Wide Can","Spray",10,220f,.6f,.1f,.3f),
        BrushPreset("Dry Plaster","Surfaces",33,60f,.7f,.4f,.3f),
        BrushPreset("Old Ink","Retro",40,20f,.5f,.6f,.5f),
        BrushPreset("Halo","Glow",2,160f,.45f,.2f,.5f),
        BrushPreset("Steel Edge","Grit",12,48f,1f,.1f),
        BrushPreset("Pollen","Botanical",35,65f,.55f,.5f,.3f),
        BrushPreset("Pale Wash","Watercolour",4,130f,.6f,.3f,.5f),
        BrushPreset("Wet Wash","Watercolour",4,180f,.45f,.15f,.5f,wetMix=.45f,wetPickup=.3f,wetness=.65f),
        BrushPreset("Linen","Surfaces",32,85f,.8f,.25f,.2f),
        BrushPreset("Leather","Surfaces",34,90f,.8f,.3f,.2f),
        BrushPreset("Etched Line","Retro",38,45f,.75f,.2f,.2f),
        BrushPreset("Halftone","Retro",39,70f,.8f,.2f,.1f),
        BrushPreset("Moss","Botanical",36,85f,.85f,.3f,.2f),
        BrushPreset("Falling Leaves","Botanical",37,110f,.8f,.15f,.2f),
        BrushPreset("Scumble Bristle","Paints",42,70f,.8f,.3f,.15f),
        BrushPreset("Matte Gouache","Paints",41,65f,.9f,.35f,.15f),
        BrushPreset("Broad Nib","Lettering",7,65f,.8f,.7f)
    )
    return air+originals
}

class DrawingSession(width: Int, height: Int) : Closeable {
    private val worker = Executors.newSingleThreadExecutor()
    private val storage = Executors.newSingleThreadExecutor()
    @Volatile private var penActive=false
    @Volatile private var dropActive=false
    @Volatile internal var transformInteracting=false
    @Volatile internal var transformEditing=false
    @Volatile internal var liquifyEditing=false
    val isStrokeActive get()=penActive||dropActive||transformInteracting||transformEditing||liquifyEditing
    internal var beforeCommand:(String)->Unit={}
    internal var beforeSnapshot:()->Unit={}
    internal var onToolChange:()->Unit={}
    private val dropGeneration=AtomicLong(0)
    private val inputLock=Any()
    private data class StrokeSettings(val kind:Int,val color:Int,val size:Float,val opacity:Float,val flow:Float,val smooth:Float,val pressureSize:Float,val pressureOpacity:Float,val fadeLength:Float,val sampleStride:Int=4,val effects:String="{}",val tip:TipSettings?=null)
    private class MoveBatch(val settings:StrokeSettings,val chunks:MutableList<FloatArray>,var size:Int)
    private var pendingMoves:MoveBatch?=null
    private val main = Handler(Looper.getMainLooper())
    private var handle = NativeBridge.create(width, height).also {
        check(it != 0L) { "Cannot create canvas" }
        if (memoryBudget > 0) NativeBridge.command(it, "{\"op\":\"memoryBudget\",\"bytes\":$memoryBudget}")
    }
    @Volatile private var closed = false
    private var disposed = false
    @Volatile internal var fullyClosed=false; private set
    @Volatile var info = JSONObject(NativeBridge.command(handle, "{\"op\":\"info\"}")); private set
    var bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); private set
    /** Screen copy of [bitmap] in tiles; only touched on the main thread. */
    internal var display=DisplayTiles(width,height); private set
    private val revision = AtomicLong(0)
    private val captureRevision=AtomicLong(0)
    private val forceCapture=AtomicBoolean(false)
    internal var onCommittedFrame:(Bitmap)->Unit={}
    private val renderPending = AtomicBoolean(false)
    internal var frameSerial=0L;private set
    var onFrame: () -> Unit = {}
    internal var onColorUsed:(Int)->Unit={}
    private var strokeRecentColor:Int?=null
    private var dropRecentColor:Int?=null
    var onInfo: () -> Unit = {}
    var onError: (String) -> Unit = {}
    var onHistoryNotice:(String)->Unit={}
    var layerThumbnails: List<Bitmap> = emptyList(); private set
    val canvasWidth get() = info.getInt("width")
    val canvasHeight get() = info.getInt("height")

    class ToolBrush(var preset:BrushPreset){
        var size=preset.size;var opacity=1f;var flow=preset.flow
        var pressureSize=preset.pressureSize;var pressureOpacity=preset.pressureOpacity;var fadeLength=preset.fadeLength
        var kind=preset.kind;var colorLayout=preset.colorLayout;var colorMode=preset.colorMode;var colorCycle=preset.colorCycle;var wetMix=preset.wetMix;var wetPickup=preset.wetPickup;var wetness=preset.wetness;var palette=preset.palette
        var tipSpacing=preset.tipSpacing;var tipFollow=preset.tipFollow;var tipRotation=preset.tipRotation;var tipJitter=preset.tipJitter;var grainScale=preset.grainScale
        var tipSizeJitter=preset.tipSizeJitter;var tipOpacityJitter=preset.tipOpacityJitter;var tipScatter=preset.tipScatter;var grainRolling=preset.grainRolling;var tipSpeedOpacity=preset.tipSpeedOpacity;var tipDynamics=preset.tipDynamics
        internal val tipSettings get()=if(kind==IMAGE_TIP_KIND && preset.tip.isNotEmpty())TipSettings(preset.tip,tipSpacing,tipFollow,tipRotation,tipJitter,grainScale,tipSizeJitter,tipOpacityJitter,tipScatter,grainRolling,tipSpeedOpacity,tipDynamics) else null
    }
    var paintMixingEnabled=false
    val paintBrush=ToolBrush(brushPresets[0])
    val smudgeBrush=ToolBrush(brushPresets.find { it.name == "Blend Medium" } ?: brushPresets[0])
    val eraseBrush=ToolBrush(brushPresets[0])
    val currentBrush get()=when(tool) {
        "Erase" -> eraseBrush
        "Smudge" -> smudgeBrush
        else -> paintBrush
    }
    var preset:BrushPreset
        get()=currentBrush.preset
        set(value){currentBrush.preset=value}
    var size:Float
        get()=currentBrush.size
        set(value){currentBrush.size=value}
    var opacity:Float
        get()=currentBrush.opacity
        set(value){currentBrush.opacity=value}
    var flow:Float
        get()=currentBrush.flow
        set(value){currentBrush.flow=value}
    var fadeLength:Float
        get()=currentBrush.fadeLength
        set(value){currentBrush.fadeLength=value.coerceIn(0f,2000f)}
    internal fun cursorSize(eraser:Boolean)=if(eraser)eraseBrush.size else currentBrush.size
    var stabilization = .12f
    var rendererMode="auto"; private set
    fun setRenderer(mode:String,then:()->Unit={}) {
        rendererMode=mode
        command("renderer",JSONObject().put("mode",mode)){then()}
    }
    var penOnly=false
    var protectControls=true
    var eraserHardness=1f
    var pressureEnabled=true
    var canvasLocked=false
    var quickShape=true
    var pressureSize:Float
        get()=currentBrush.pressureSize
        set(value){currentBrush.pressureSize=value}
    var pressureOpacity:Float
        get()=currentBrush.pressureOpacity
        set(value){currentBrush.pressureOpacity=value}
    var color = 0xff202020.toInt()
    var tool = "Paint"
        set(value){if(field==value)return;field=value;onToolChange()}
    var guide = false
    var symmetryMode="Off";private set
    fun setSymmetry(mode:String){symmetryMode=mode;command("symmetry",JSONObject().put("mode",mode))}
    var cursor = true
    var cursorStyle="Contrast"
    var cursorOpacity=1f
    var twoFingerUndo=true
    var threeFingerRedo=true
    var fourFingerFullScreen=true
    var holdEyedropper=true
    internal var stylusButtonAction=StylusAction.ERASE
    var penButtonErase:Boolean
        get()=stylusButtonAction==StylusAction.ERASE
        set(value){stylusButtonAction=if(value)StylusAction.ERASE else StylusAction.NONE}
    var pinchToFit=true
    var rightSidebar = false
    var lightInterface = false
    var dynamicBrushScaling=true
    var viewZoom=1f
    var rapidUndoDelay=.47f
    var selectionVisibility=.3f

    var onBrushChosen: (String,BrushPreset)->Unit = {_,_->}
    fun choose(p: BrushPreset) {
        val keepMix=tool=="Paint" && paintMixingEnabled && p.wetMix==0f
        val mixing=Triple(currentBrush.wetMix,currentBrush.wetPickup,currentBrush.wetness)
        if(tool=="Paint")paintMixingEnabled=paintMixingEnabled || p.wetMix>0f
        preset = p; size = p.size; flow = p.flow; pressureSize = p.pressureSize; pressureOpacity = p.pressureOpacity;fadeLength=p.fadeLength
        if(p.customId.isNotEmpty()){opacity=p.opacity;stabilization=p.stabilization;if(tool=="Erase")eraserHardness=p.hardness}
        currentBrush.kind=p.kind;currentBrush.colorLayout=p.colorLayout;currentBrush.colorMode=p.colorMode;currentBrush.colorCycle=p.colorCycle;currentBrush.wetMix=p.wetMix;currentBrush.wetPickup=p.wetPickup;currentBrush.wetness=p.wetness;currentBrush.palette=p.palette
        currentBrush.tipSpacing=p.tipSpacing;currentBrush.tipFollow=p.tipFollow;currentBrush.tipRotation=p.tipRotation;currentBrush.tipJitter=p.tipJitter;currentBrush.grainScale=p.grainScale
        currentBrush.tipSizeJitter=p.tipSizeJitter;currentBrush.tipOpacityJitter=p.tipOpacityJitter;currentBrush.tipScatter=p.tipScatter;currentBrush.grainRolling=p.grainRolling;currentBrush.tipSpeedOpacity=p.tipSpeedOpacity;currentBrush.tipDynamics=p.tipDynamics
        if(keepMix){currentBrush.wetMix=mixing.first;currentBrush.wetPickup=mixing.second;currentBrush.wetness=mixing.third}
        onBrushChosen(tool,p)
    }

    private data class EffectKey(val mode:String,val layout:String,val cycle:Float,val mix:Float,val pickup:Float,val wetness:Float,val palette:List<Int>)
    private var effectKey:EffectKey?=null
    private var effectJson="{}"
    private fun strokeEffects(brush:ToolBrush,solid:Boolean):String {
        val key=EffectKey(if(solid)"Solid" else brush.colorMode,brush.colorLayout,brush.colorCycle,
            if(solid || !paintMixingEnabled)0f else brush.wetMix,brush.wetPickup,brush.wetness,brush.palette)
        if(key!=effectKey){
            effectKey=key.copy(palette=key.palette.toList())
            effectJson=JSONObject().put("op","brushEffects").put("mode",key.mode).put("layout",key.layout)
                .put("cycle",key.cycle.toDouble()).put("mix",key.mix.toDouble()).put("pickup",key.pickup.toDouble())
                .put("wetness",key.wetness.toDouble()).put("palette",org.json.JSONArray(key.palette.map{it.toLong() and 0xffffffffL})).toString()
        }
        return effectJson
    }

    fun stroke(phase: Int, input: FloatArray, eraser: Boolean = false, times:FloatArray?=null) {
        if (closed) return
        if(info.optBoolean("maskEditing") && tool=="Smudge"){if(phase==0)onError("Use Paint or Erase to edit a layer mask");return}
        if(phase==0)penActive=true
        if(phase==2||phase==3)penActive=false
        val erasing=eraser||tool=="Erase"
        val brush=if(erasing)eraseBrush else if(tool=="Smudge")smudgeBrush else paintBrush
        val kind=if(erasing)64+brush.kind else if(tool=="Smudge")128+brush.kind else brush.kind
        // Erasing uses alpha coverage; the unused color carries edge hardness to both renderers.
        val ink=if(erasing)(eraserHardness.coerceIn(0f,1f)*255+.5f).toInt() else color
        val timed=times!=null && times.size==input.size/4
        val samples=if(timed)FloatArray(input.size/4*5){i->if(i%5==4)times!![i/5] else input[i/5*4+i%5]}else input
        val settings=StrokeSettings(kind,ink,if(dynamicBrushScaling)brush.size else brush.size/viewZoom.coerceAtLeast(.02f),brush.opacity,brush.flow,stabilization,if(pressureEnabled)brush.pressureSize else 0f,if(pressureEnabled)brush.pressureOpacity else 0f,brush.fadeLength,if(timed)5 else 4,strokeEffects(brush,erasing || tool=="Smudge"),brush.tipSettings)
        synchronized(inputLock){
            if(phase==1){
                val pending=pendingMoves
                if(pending!=null && pending.settings==settings && pending.size+samples.size<=65536){pending.chunks.add(samples);pending.size+=samples.size;return}
                val batch=MoveBatch(settings,mutableListOf(samples),samples.size);pendingMoves=batch
                worker.execute{
                    val merged=synchronized(inputLock){
                        if(pendingMoves===batch)pendingMoves=null
                        FloatArray(batch.size).also{out->var offset=0;for(chunk in batch.chunks){chunk.copyInto(out,offset);offset+=chunk.size}}
                    }
                    if(!disposed){sendStroke(1,merged,batch.settings);changed(record=true)}
                }
            }else{
                pendingMoves=null
                worker.execute{
                    if(!disposed){sendStroke(phase,samples,settings);if(phase==2||phase==3)readInfo()
                        if(phase==2){val used=strokeRecentColor;strokeRecentColor=null;if(used!=null && info.isNull("inputError"))main.post{if(!closed)onColorUsed(used)}}
                        if(phase==3)strokeRecentColor=null
                        changed(record=phase==2)}
                }
            }
        }
    }
    private var shapeStroke=false
    private fun sendStroke(phase:Int,samples:FloatArray,s:StrokeSettings){
        if(phase==0 || phase==4){strokeRecentColor=if(s.kind in 0..63 && s.kind !in listOf(5,6) && s.opacity>0 && s.flow>0 && !info.optBoolean("maskEditing") && info.optJSONArray("layers")?.optJSONObject(info.optInt("active"))?.optBoolean("locked")==false)s.color else null}
        if(phase==3)strokeRecentColor=null
        if(phase==0 || phase==3)shapeStroke=false
        if(phase==0 && s.effects!="{}")NativeBridge.command(handle,s.effects)
        if((phase==0 || phase==4) && s.tip!=null)sendTip(s.tip)
        NativeBridge.stroke(handle,phase,samples,s.kind,s.color,s.size,s.opacity,s.flow,s.smooth,s.pressureSize,s.pressureOpacity,s.fadeLength,s.sampleStride)
        if(phase==2 && shapeStroke){NativeBridge.command(handle,"{\"op\":\"nameLastUndo\",\"name\":\"Shape\"}");shapeStroke=false}
    }

    /** Gives the engine this stroke's image tip, sending its pixels only when the engine lacks them. */
    private fun sendTip(t:TipSettings){
        val reply=NativeBridge.command(handle,t.command(false)!!)
        if(reply.contains("\"missing\"")){val full=t.command(true);if(full!=null)NativeBridge.command(handle,full) else main.post{if(!closed)onError("This brush's tip image is missing")}}
    }

    private var pendingShape:Pair<StrokeSettings,FloatArray>?=null
    private var shapeQueued=false
    internal fun previewQuickShape(samples:FloatArray){
        if(closed || samples.size<4)return
        penActive=true
        val settings=StrokeSettings(currentBrush.kind,color,if(dynamicBrushScaling)size else size/viewZoom.coerceAtLeast(.02f),opacity,flow,0f,if(pressureEnabled)pressureSize else 0f,if(pressureEnabled)pressureOpacity else 0f,fadeLength,tip=currentBrush.tipSettings)
        synchronized(inputLock){
            pendingMoves=null;pendingShape=settings to samples
            if(shapeQueued)return
            shapeQueued=true
            worker.execute{
                val latest=synchronized(inputLock){val value=pendingShape;pendingShape=null;shapeQueued=false;value}
                if(!disposed && latest!=null){
                    val (s,points)=latest;sendStroke(4,points,s)
                    shapeStroke=true
                    changed()
                }
            }
        }
    }

    private var pendingDrop:JSONObject?=null
    private var dropQueued=false
    fun previewColorDrop(values:JSONObject){
        if(closed)return
        dropRecentColor=values.optLong("color",color.toLong() and 0xffffffffL).toInt()
        dropActive=true;dropGeneration.incrementAndGet()
        synchronized(inputLock){
            pendingDrop=values
            if(dropQueued)return
            dropQueued=true
            worker.execute{
                val latest=synchronized(inputLock){val value=pendingDrop;pendingDrop=null;dropQueued=false;value}
                if(!disposed && latest!=null){latest.put("op","floodPreview")
                    val result=JSONObject(NativeBridge.command(handle,latest.toString()))
                    if(result.has("error"))main.post{if(!closed)onError(result.getString("error"))}
                    changed()
                }
            }
        }
    }
    fun finishColorDrop(cancel:Boolean,then:()->Unit){
        val generation=dropGeneration.get()
        val used=dropRecentColor
        command(if(cancel)"floodCancel" else "floodCommit"){result->if(dropGeneration.get()==generation){dropActive=false;dropRecentColor=null};if(!cancel && !result.has("error") && used!=null)onColorUsed(used);then()}
    }

    private var pendingAdjustment:JSONObject?=null
    private var adjustmentQueued=false
    private fun previewAdjustment(values:JSONObject){
        beforeCommand("adjustPreview")
        synchronized(inputLock){pendingAdjustment=values;if(adjustmentQueued)return;adjustmentQueued=true}
        worker.execute{
            val latest=synchronized(inputLock){val v=pendingAdjustment;pendingAdjustment=null;adjustmentQueued=false;v}
            if(!disposed && latest!=null){latest.put("op","adjustPreview");val result=JSONObject(NativeBridge.command(handle,latest.toString()));if(result.has("error"))main.post{if(!closed)onError(result.getString("error"))}else{info=result;changed();main.post{if(!closed)onInfo()}}}
        }
    }
    private var pendingLayerAdjust:JSONObject?=null
    private var layerAdjustQueued=false
    /** Adjustment-layer slider: keep only the newest value so drags never queue up behind slow composites. */
    private fun adjustLayer(values:JSONObject){
        beforeCommand("layer")
        synchronized(inputLock){pendingLayerAdjust=values;pendingMoves=null;if(layerAdjustQueued)return;layerAdjustQueued=true}
        worker.execute{
            val latest=synchronized(inputLock){val v=pendingLayerAdjust;pendingLayerAdjust=null;layerAdjustQueued=false;v}
            if(!disposed && latest!=null){latest.put("op","layer");val result=JSONObject(NativeBridge.command(handle,latest.toString()));if(result.has("error"))main.post{if(!closed)onError(result.getString("error"))}else{info=result;changed(record=true);main.post{if(!closed)onInfo()}}}
        }
    }
    fun command(op: String, values: JSONObject = JSONObject(), then: (JSONObject) -> Unit = {}) {
        if (closed) return
        if(op=="adjustPreview"){previewAdjustment(values);return}
        if(op=="layer" && values.has("adjustmentKind") && values.length()<=3 && values.keys().asSequence().all{it in setOf("adjustmentKind","amount","index")}){adjustLayer(values);return}
        beforeCommand(op)
        values.put("op", op)
        synchronized(inputLock){pendingMoves=null}
        worker.execute {
            if (disposed) return@execute
            val result = JSONObject(NativeBridge.command(handle, values.toString()))
            if (result.has("error")) main.post { if (!closed) onError(result.getString("error")) }
            else if (result.has("layers")) info = result
            changed(record=!result.has("error") && op !in listOf("info","pick","selectionPreview","exteriorPreview","historyDirectory","renderer","symmetry","brushEffects","nameLastUndo"))
            main.post { if (!closed) { onInfo();if(op in listOf("flood","fill") && !result.has("error") && !info.optBoolean("maskEditing"))onColorUsed(values.optLong("color",color.toLong() and 0xffffffffL).toInt());if(op in listOf("undo","redo") && !result.has("error"))onHistoryNotice(result.optString("historyNotice"));then(result) } }
        }
    }

    internal fun previewLiquify(values:JSONObject){
        if(closed)return;values.put("op","liquifyDab")
        worker.execute{if(!disposed){val result=JSONObject(NativeBridge.command(handle,values.toString()));if(result.has("error"))main.post{if(!closed)onError(result.getString("error"))};changed()}}
    }
    internal fun selectionPreview(then:(JSONObject)->Unit){
        if(closed)return
        worker.execute{if(disposed)return@execute;val value=JSONObject(NativeBridge.command(handle,"{\"op\":\"selectionPreview\"}"));main.post{if(!closed)then(value)}}
    }

    private fun readInfo() {
        info = JSONObject(NativeBridge.command(handle, "{\"op\":\"info\"}"))
        main.post { if (!closed) { if(!info.isNull("inputError"))onError(info.getString("inputError"));onInfo() } }
    }

    private fun changed(record:Boolean=false) {val version=revision.incrementAndGet();if(record)captureRevision.set(version);requestRender()}
    internal fun captureNextFrame(){if(closed)return;forceCapture.set(true);captureRevision.set(revision.incrementAndGet());requestRender()}

    // Reused across frames: only touched by the render worker while renderPending is set.
    private var patchPixels=IntArray(0)
    internal companion object {
        private const val SHRINK_ABOVE_PIXELS = 1 shl 20
        /** Layer memory budget in bytes for new canvases; 0 keeps the engine default. */
        @Volatile var memoryBudget = 0L
        /** About an eighth of the device's RAM, within 256 MB and 1.5 GB. The GPU keeps a second
         *  copy of the layers, so the app uses roughly twice this at a full budget. */
        fun budgetFor(totalMemory: Long) = (totalMemory * 12 / 100).coerceIn(256L shl 20, 1536L shl 20)
    }
    internal data class ExteriorTile(val x:Float,val y:Float,val bitmap:Bitmap)
    internal var exteriorTiles=listOf<ExteriorTile>();private set

    fun requestRender() {
        if (closed || !renderPending.compareAndSet(false, true)) return
        worker.execute {
            if (closed) { renderPending.set(false); return@execute }
            val version = revision.get()
            val w = canvasWidth; val h = canvasHeight
            val resize=bitmap.width!=w || bitmap.height!=h
            val patch=try{
                // Size the buffer to this frame's patch, not the whole canvas, and let oversized
                // buffers from full-canvas redraws go, so low-memory tablets keep the heap free.
                val force=resize || forceCapture.getAndSet(false)
                var result:IntArray?=null
                var attempts=0
                while(true){
                    val needed=NativeBridge.patchCapacity(handle,force)
                    if(patchPixels.size<needed || (patchPixels.size>needed*4 && patchPixels.size>SHRINK_ABOVE_PIXELS)){
                        patchPixels=IntArray(0);patchPixels=IntArray(needed)
                    }
                    val out=NativeBridge.renderPatchInto(handle,force,patchPixels)
                    if(out!=null && out.size==2 && out[0]==-1){
                        // Buffer still too small after two grows: skip this frame rather than parse the sentinel.
                        if(attempts>=2)break
                        attempts++;patchPixels=IntArray(0);patchPixels=IntArray(out[1]);continue
                    }
                    result=out;break
                }
                result
            }catch(e:OutOfMemoryError){
                patchPixels=IntArray(0)
                renderPending.set(false)
                main.post{if(!closed)onError("Memori perangkat hampir penuh. Gabungkan beberapa layer atau gunakan kanvas yang lebih kecil.")}
                return@execute
            }
            val pixels=patchPixels
            var exterior:List<ExteriorTile>?=null;var exteriorFull=false
            if(patch!=null){var offset=4
                if(offset+3<=patch.size && patch[offset]==0x45585431){
                    exteriorFull=patch[offset+1]!=0;val count=patch[offset+2];offset+=3
                    exterior=(0 until count).map{val x=patch[offset];val y=patch[offset+1];offset+=2;val tile=ExteriorTile(x.toFloat(),y.toFloat(),Bitmap.createBitmap(patch,offset,64,64,64,Bitmap.Config.ARGB_8888));offset+=4096;tile}
                }
            }
            main.post {
                if(!closed && exterior!=null){
                    val updates=exterior!!;val changed=updates.map{it.x to it.y}.toSet()
                    val kept=exteriorTiles.filter{tile->if(exteriorFull || (tile.x to tile.y) in changed){tile.bitmap.recycle();false}else true}
                    exteriorTiles=kept+updates
                }else if(closed){exterior?.forEach{it.bitmap.recycle()}}
                if (!closed && (patch!=null || exterior!=null)) {
                    if(resize){bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);display=DisplayTiles(w,h)}
                    if(patch!=null && patch[2]>0 && patch[3]>0){
                        bitmap.setPixels(pixels,0,patch[2],patch[0],patch[1],patch[2],patch[3])
                        display.update(pixels,patch[0],patch[1],patch[2],patch[3])
                    }
                    frameSerial++;onFrame()
                }
                if(!closed){
                    val capture=captureRevision.get()
                    if(capture>0 && version>=capture && captureRevision.compareAndSet(capture,0))onCommittedFrame(bitmap)
                }
                renderPending.set(false)
                if (!closed && revision.get() != version) requestRender()
            }
        }
    }

    fun writePsd(file:java.io.File,then:(String?)->Unit){
        beforeSnapshot()
        if(closed){file.delete();then("Drawing engine is closed");return}
        worker.execute {
            val result=runCatching {JSONObject(NativeBridge.writePsd(handle,file.absolutePath))}
            val error=result.exceptionOrNull()?.message?:result.getOrNull()?.optString("error")?.takeIf{it.isNotEmpty()}
            main.post {if(closed){file.delete()}else{if(error!=null)file.delete();then(error)}}
        }
    }
    /** The document in memory; [documentFile] suits artwork too large for the Java heap. */
    fun documentSnapshot(then:(ByteArray?)->Unit){
        beforeSnapshot()
        if(closed)return
        worker.execute{val bytes=NativeBridge.save(handle);main.post{if(!closed)then(bytes)}}
    }
    /** Writes the document into [file]; then gets null on success or the error. */
    fun documentFile(file:java.io.File,then:(String?)->Unit){
        beforeSnapshot()
        if(closed){file.delete();then("Drawing engine is closed");return}
        worker.execute{
            val error=saveInto(file)
            main.post{if(closed)file.delete() else then(error)}
        }
    }
    private fun saveInto(file:java.io.File):String?=runCatching{JSONObject(NativeBridge.saveToFile(handle,file.absolutePath)).optString("error").takeIf{it.isNotEmpty()}}.getOrElse{it.message?:"Could not save drawing"}
    fun imageSnapshot(then:(Bitmap?)->Unit){
        beforeSnapshot()
        if(closed)return
        worker.execute{
            val result=runCatching{
                val w=canvasWidth;val h=canvasHeight
                val buffer=ByteBuffer.allocateDirect(w*h*4)
                check(NativeBridge.render(handle,buffer,true)){"Could not render artwork"}
                Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{copyRgba(it,buffer)}
            }.getOrNull()
            main.post{if(closed)result?.recycle() else then(result)}
        }
    }
    fun snapshot(then: (ByteArray?, Bitmap?) -> Unit) {
        beforeSnapshot()
        if (closed) return
        worker.execute {
            val bytes = NativeBridge.save(handle)
            val buffer = ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 4)
            NativeBridge.render(handle, buffer, true)
            val image = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
            copyRgba(image, buffer)
            main.post { if (!closed) then(bytes, image) }
        }
    }

    fun persist(store: DocumentStore, id: String, name: String, then: (String?) -> Unit = {}) {
        beforeSnapshot()
        if (closed) return
        synchronized(inputLock){pendingMoves=null}
        worker.execute {
            val snapshot=runCatching{
                val saved=store.newSaveFile()
                saveInto(saved)?.let{saved.delete();error(it)}
                val preview=checkNotNull(NativeBridge.preview(handle))
                val image=Bitmap.createBitmap(preview,2,preview[0],preview[0],preview[1],Bitmap.Config.ARGB_8888)
                Triple(saved,image,canvasWidth to canvasHeight)
            }
            if(snapshot.isFailure){main.post{then(snapshot.exceptionOrNull()?.message?:"Could not save drawing")};return@execute}
            val (saved,image,dimensions)=snapshot.getOrThrow()
            storage.execute{
                val failure=runCatching{store.commitRaster(id,name,saved,image,dimensions.first,dimensions.second)}.exceptionOrNull()?.message
                image.recycle();main.post{then(failure)}
            }
        }
    }

    fun load(bytes: ByteArray, then: (Boolean) -> Unit) = load({NativeBridge.load(handle, bytes)}, then)
    /** Opens a binary document file; [deleteAfter] removes a temporary copy once it is read. */
    fun load(file: java.io.File, deleteAfter: Boolean, then: (Boolean) -> Unit) =
        load({try{NativeBridge.loadFile(handle, file.absolutePath)}finally{if(deleteAfter)file.delete()}}, then)
    private fun load(read: () -> String, then: (Boolean) -> Unit) {
        worker.execute {
            val result = JSONObject(read())
            if (result.has("error")) main.post { onError(result.getString("error")); then(false) }
            else { info = result; changed(); main.post { if (!closed) { onInfo(); then(true) } } }
        }
    }

    fun importPsd(file:java.io.File,then:(String?,List<String>)->Unit){
        worker.execute{
            val result=runCatching{JSONObject(NativeBridge.importPsd(handle,file.absolutePath))};file.delete()
            val value=result.getOrNull();val error=result.exceptionOrNull()?.message?:value?.optString("error")?.takeIf{it.isNotEmpty()}
            if(error==null && value!=null){info=value;changed()}
            val warnings=value?.optJSONArray("importWarnings")?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList()
            main.post{if(!closed)then(error,warnings)}
        }
    }
    fun import(image: Bitmap, then: () -> Unit) {
        beforeSnapshot()
        val data = IntArray(image.width * image.height)
        image.getPixels(data, 0, image.width, 0, 0, image.width, image.height)
        worker.execute {
            val result = JSONObject(NativeBridge.importPixels(handle, image.width, image.height, data))
            if (result.has("error")) main.post { onError(result.getString("error")) }
            else { info = result; changed(); main.post { if (!closed) { onInfo(); then() } } }
        }
    }

    internal fun transformSnapshot(then:(TransformSnapshot?)->Unit){
        if(closed)return
        worker.execute{
            val result=runCatching{val data=checkNotNull(NativeBridge.transformSnapshot(handle));val state=JSONObject(NativeBridge.command(handle,"{\"op\":\"info\"}"));TransformSnapshot.decode(state,data)}
            main.post{if(closed)result.getOrNull()?.recycle()else{result.onFailure{onError(it.message?:"Cannot prepare transform")};then(result.getOrNull())}}
        }
    }
    fun thumbnails(then: () -> Unit) {
        if (closed) return
        worker.execute {
            val size = 64
            val data = NativeBridge.thumbnails(handle, size)
            val images = if (data == null) emptyList() else (0 until data.size / (size * size)).map { index ->
                Bitmap.createBitmap(data, index * size * size, size, size, size, Bitmap.Config.ARGB_8888)
            }
            main.post {
                if (!closed) {val previous=layerThumbnails;layerThumbnails=images;then();previous.forEach{it.recycle()}}
                else images.forEach{it.recycle()}
            }
        }
    }

    override fun close() {
        if(closed)return
        closed = true
        worker.execute { NativeBridge.destroy(handle); handle = 0; disposed=true; storage.execute{fullyClosed=true}; storage.shutdown() }
        worker.shutdown()
    }
}

// Native Skia on desktop test hosts may use BGRA; Android devices normally use RGBA.
private val bitmapUsesBgra: Boolean by lazy {
    val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    probe.setPixel(0, 0, 0xffff0000.toInt())
    val bytes = ByteBuffer.allocateDirect(4)
    probe.copyPixelsToBuffer(bytes)
    probe.recycle()
    bytes.get(0).toInt() == 0
}
private fun copyRgba(bitmap: Bitmap, buffer: ByteBuffer) {
    if (bitmapUsesBgra) {
        for (i in 0 until bitmap.width * bitmap.height * 4 step 4) {
            val red = buffer.get(i)
            buffer.put(i, buffer.get(i + 2)); buffer.put(i + 2, red)
        }
    }
    buffer.rewind()
    bitmap.copyPixelsFromBuffer(buffer)
}
