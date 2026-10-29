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
    val flow: Float = 1f, val pressureSize: Float = .8f, val pressureOpacity: Float = 0f, val fadeLength:Float=0f,val customId:String="",val opacity:Float=1f,val stabilization:Float=.12f,val hardness:Float=1f,val colorMode:String="Solid",val colorCycle:Float=600f,val wetMix:Float=0f,val palette:List<Int> = emptyList(),val colorLayout:String="Along",val wetPickup:Float=.35f,val wetness:Float=.5f)

val brushPresets = listOf(
    BrushPreset("Studio Pen", "Inking", 0, 12f),
    BrushPreset("Pastel Threads","Artistic",14,64f,.8f,.2f,.1f,colorMode="Palette",palette=listOf(0xffd78a99.toInt(),0xffe7c686.toInt(),0xff8bc6b6.toInt(),0xff939fd0.toInt()),colorLayout="Bristles"),
    BrushPreset("Palette Ribbon","Artistic",7,45f,pressureSize=.2f,colorMode="Palette",palette=listOf(0xffed7768.toInt(),0xffebc66a.toInt(),0xff6096cc.toInt())),
    BrushPreset("Wet Paint","Painting",11,55f,.7f,.35f,.2f,wetMix=.45f),
    BrushPreset("Fade Brush", "Inking", 0, 28f, pressureSize=0f,fadeLength=150f),
    BrushPreset("Technical Pen", "Inking", 0, 4f, pressureSize = .35f),
    BrushPreset("Dry Ink", "Inking", 3, 18f, .9f),
    BrushPreset("Graphite", "Sketching", 1, 9f, .8f, .7f, .7f),
    BrushPreset("Soft Pencil", "Sketching", 1, 22f, .65f, .5f, .8f),
    BrushPreset("Compressed Charcoal", "Charcoals", 3, 48f, .8f, .55f, .5f),
    BrushPreset("Willow", "Charcoals", 3, 14f, .65f, .9f, .65f),
    BrushPreset("Round Paint", "Painting", 0, 42f, .55f, .7f, .2f),
    BrushPreset("Water Wash", "Painting", 4, 90f, .8f, .4f, .6f),
    BrushPreset("Flat Brush", "Painting", 7, 55f, .7f, .6f, .2f),
    BrushPreset("Soft Airbrush", "Airbrushing", 2, 110f, .7f, .25f, .7f),
    BrushPreset("Fine Airbrush", "Airbrushing", 2, 32f, .9f, .4f, .5f),
    BrushPreset("Monoline", "Calligraphy", 0, 16f, pressureSize = 0f),
    BrushPreset("Flat Lettering", "Calligraphy", 7, 26f, pressureSize = .9f)
) + additionalPresets()

val brushCategories=listOf("Recent","Sketching","Inking","Drawing","Painting","Artistic","Calligraphy","Airbrushing","Textures","Abstract","Charcoals","Elements","Spraypaints","Materials","Vintage","Luminance","Industrial","Organic","Water","Imported")
private fun additionalPresets():List<BrushPreset> {
    val air=listOf(
        BrushPreset("Soft Brush","Airbrushing",2,120f,.8f,.15f,.35f),
        BrushPreset("Medium Brush","Airbrushing",8,100f,.8f,.2f,.3f),
        BrushPreset("Medium Hard Brush","Airbrushing",9,90f,.8f,.2f,.25f),
        BrushPreset("Hard Brush","Airbrushing",0,80f,1f,.25f,0f),
        BrushPreset("Soft Blend","Airbrushing",2,140f,.25f,.1f,.15f),
        BrushPreset("Medium Blend","Airbrushing",8,120f,.25f,.1f,.15f),
        BrushPreset("Medium Hard Blend","Airbrushing",9,100f,.25f,.1f,.15f),
        BrushPreset("Hard Blend","Airbrushing",0,80f,.18f,.1f,.1f),
        BrushPreset("Medium Airbrush","Airbrushing",8,75f,.55f,.2f,.5f)
    )
    val originals=listOf(
        BrushPreset("Expressive Line","Drawing",0,14f,1f,.9f),
        BrushPreset("Chalk","Drawing",13,28f,.8f,.55f,.4f),
        BrushPreset("Pastel","Drawing",14,38f,.8f,.35f,.3f),
        BrushPreset("Bristle","Artistic",11,54f,.8f,.5f,.2f),
        BrushPreset("Wide Bristle","Painting",11,90f,.65f,.35f,.2f),
        BrushPreset("Opaque Round","Painting",0,70f,1f,.5f),
        BrushPreset("Soft Glaze","Painting",2,160f,.3f,.15f,.3f),
        BrushPreset("Chisel Marker","Inking",12,28f,.85f,.2f),
        BrushPreset("Fine Liner","Inking",0,3f,1f,.1f),
        BrushPreset("Soft Charcoal","Charcoals",13,65f,.65f,.5f,.5f),
        BrushPreset("Rough Grain","Textures",14,75f,.65f,.4f,.4f),
        BrushPreset("Broken Marks","Abstract",3,48f,.6f,.7f,.3f),
        BrushPreset("Dust","Elements",10,130f,.6f,.1f,.4f),
        BrushPreset("Fine Mist","Spraypaints",10,100f,.75f,.15f,.4f),
        BrushPreset("Wide Spray","Spraypaints",10,220f,.6f,.1f,.3f),
        BrushPreset("Dry Surface","Materials",14,60f,.7f,.4f,.3f),
        BrushPreset("Faded Ink","Vintage",13,20f,.5f,.6f,.5f),
        BrushPreset("Soft Glow","Luminance",2,160f,.45f,.2f,.5f),
        BrushPreset("Hard Edge","Industrial",12,48f,1f,.1f),
        BrushPreset("Soft Grain","Organic",14,65f,.55f,.5f,.3f),
        BrushPreset("Diluted Wash","Water",4,130f,.6f,.3f,.5f),
        BrushPreset("Soft Wash","Water",2,180f,.25f,.15f,.5f),
        BrushPreset("Broad Lettering","Calligraphy",7,65f,.8f,.7f)
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
    private data class StrokeSettings(val kind:Int,val color:Int,val size:Float,val opacity:Float,val flow:Float,val smooth:Float,val pressureSize:Float,val pressureOpacity:Float,val fadeLength:Float,val sampleStride:Int=4,val effects:String="{}")
    private class MoveBatch(val settings:StrokeSettings,val chunks:MutableList<FloatArray>,var size:Int)
    private var pendingMoves:MoveBatch?=null
    private val main = Handler(Looper.getMainLooper())
    private var handle = NativeBridge.create(width, height).also { check(it != 0L) { "Cannot create canvas" } }
    @Volatile private var closed = false
    private var disposed = false
    @Volatile internal var fullyClosed=false; private set
    @Volatile var info = JSONObject(NativeBridge.command(handle, "{\"op\":\"info\"}")); private set
    var bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); private set
    private val revision = AtomicLong(0)
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
    }
    var paintMixingEnabled=false
    val paintBrush=ToolBrush(brushPresets[0])
    val smudgeBrush=ToolBrush(brushPresets.find { it.name == "Medium Blend" } ?: brushPresets[0])
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
        if(keepMix){currentBrush.wetMix=mixing.first;currentBrush.wetPickup=mixing.second;currentBrush.wetness=mixing.third}
        onBrushChosen(tool,p)
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
        val settings=StrokeSettings(kind,ink,if(dynamicBrushScaling)brush.size else brush.size/viewZoom.coerceAtLeast(.02f),brush.opacity,brush.flow,stabilization,if(pressureEnabled)brush.pressureSize else 0f,if(pressureEnabled)brush.pressureOpacity else 0f,brush.fadeLength,if(timed)5 else 4,org.json.JSONObject().put("op","brushEffects").put("mode",if(erasing || tool=="Smudge")"Solid" else brush.colorMode).put("layout",brush.colorLayout).put("cycle",brush.colorCycle.toDouble()).put("mix",if(erasing || tool=="Smudge" || !paintMixingEnabled)0.0 else brush.wetMix.toDouble()).put("pickup",brush.wetPickup.toDouble()).put("wetness",brush.wetness.toDouble()).put("palette",org.json.JSONArray(brush.palette.map{it.toLong() and 0xffffffffL})).toString())
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
                    if(!disposed){sendStroke(1,merged,batch.settings);changed()}
                }
            }else{
                pendingMoves=null
                worker.execute{
                    if(!disposed){sendStroke(phase,samples,settings);if(phase==2||phase==3)readInfo()
                        if(phase==2){val used=strokeRecentColor;strokeRecentColor=null;if(used!=null && info.isNull("inputError"))main.post{if(!closed)onColorUsed(used)}}
                        if(phase==3)strokeRecentColor=null
                        changed()}
                }
            }
        }
    }
    private var shapeStroke=false
    private fun sendStroke(phase:Int,samples:FloatArray,s:StrokeSettings){
        if(phase==0){strokeRecentColor=if(s.kind in 0..63 && s.kind !in listOf(5,6) && s.opacity>0 && s.flow>0 && !info.optBoolean("maskEditing") && info.optJSONArray("layers")?.optJSONObject(info.optInt("active"))?.optBoolean("locked")==false)s.color else null}
        if(phase==3)strokeRecentColor=null
        if(phase==0 || phase==3)shapeStroke=false
        if(phase==0 && s.effects!="{}")NativeBridge.command(handle,s.effects)
        NativeBridge.stroke(handle,phase,samples,s.kind,s.color,s.size,s.opacity,s.flow,s.smooth,s.pressureSize,s.pressureOpacity,s.fadeLength,s.sampleStride)
        if(phase==2 && shapeStroke){NativeBridge.command(handle,"{\"op\":\"nameLastUndo\",\"name\":\"Shape\"}");shapeStroke=false}
    }

    private var pendingShape:Pair<StrokeSettings,FloatArray>?=null
    private var shapeQueued=false
    internal fun previewQuickShape(samples:FloatArray){
        if(closed || samples.size<4)return
        penActive=true
        val settings=StrokeSettings(currentBrush.kind,color,if(dynamicBrushScaling)size else size/viewZoom.coerceAtLeast(.02f),opacity,flow,0f,if(pressureEnabled)pressureSize else 0f,if(pressureEnabled)pressureOpacity else 0f,fadeLength)
        synchronized(inputLock){
            pendingMoves=null;pendingShape=settings to samples
            if(shapeQueued)return
            shapeQueued=true
            worker.execute{
                val latest=synchronized(inputLock){val value=pendingShape;pendingShape=null;shapeQueued=false;value}
                if(!disposed && latest!=null){
                    val (s,points)=latest;sendStroke(3,floatArrayOf(),s);sendStroke(0,points.copyOfRange(0,4),s)
                    if(points.size>4)sendStroke(1,points.copyOfRange(4,points.size),s)
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

    fun command(op: String, values: JSONObject = JSONObject(), then: (JSONObject) -> Unit = {}) {
        if (closed) return
        beforeCommand(op)
        values.put("op", op)
        synchronized(inputLock){pendingMoves=null}
        worker.execute {
            if (disposed) return@execute
            val result = JSONObject(NativeBridge.command(handle, values.toString()))
            if (result.has("error")) main.post { if (!closed) onError(result.getString("error")) }
            else if (result.has("layers")) info = result
            changed()
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

    private fun changed() { revision.incrementAndGet(); requestRender() }

    internal data class ExteriorTile(val x:Float,val y:Float,val bitmap:Bitmap)
    internal var exteriorTiles=listOf<ExteriorTile>();private set

    fun requestRender() {
        if (closed || !renderPending.compareAndSet(false, true)) return
        worker.execute {
            if (closed) { renderPending.set(false); return@execute }
            val version = revision.get()
            val w = canvasWidth; val h = canvasHeight
            val resize=bitmap.width!=w || bitmap.height!=h
            val patch=NativeBridge.renderPatch(handle,resize)
            var exterior:List<ExteriorTile>?=null;var exteriorFull=false
            if(patch!=null){var offset=4+patch[2]*patch[3]
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
                    if(resize)bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    if(patch!=null && patch[2]>0 && patch[3]>0)bitmap.setPixels(patch,4,patch[2],patch[0],patch[1],patch[2],patch[3])
                    frameSerial++;onFrame()
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
    fun documentSnapshot(then:(ByteArray?)->Unit){
        beforeSnapshot()
        if(closed)return
        worker.execute{val bytes=NativeBridge.save(handle);main.post{if(!closed)then(bytes)}}
    }
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
                val bytes=checkNotNull(NativeBridge.save(handle)){"Could not save drawing"}
                val preview=checkNotNull(NativeBridge.preview(handle))
                val image=Bitmap.createBitmap(preview,2,preview[0],preview[0],preview[1],Bitmap.Config.ARGB_8888)
                Triple(bytes,image,canvasWidth to canvasHeight)
            }
            if(snapshot.isFailure){main.post{then(snapshot.exceptionOrNull()?.message?:"Could not save drawing")};return@execute}
            val (bytes,image,dimensions)=snapshot.getOrThrow()
            storage.execute{
                val failure=runCatching{store.save(id,name,bytes,image,dimensions.first,dimensions.second)}.exceptionOrNull()?.message
                image.recycle();main.post{then(failure)}
            }
        }
    }

    fun load(bytes: ByteArray, then: (Boolean) -> Unit) {
        worker.execute {
            val result = JSONObject(NativeBridge.load(handle, bytes))
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
            main.post { if (!closed) { layerThumbnails = images; then() } }
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
