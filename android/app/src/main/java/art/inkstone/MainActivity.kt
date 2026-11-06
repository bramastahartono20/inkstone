package art.inkstone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

data class KeyShortcut(val keyCode: Int, val ctrl: Boolean, val alt: Boolean, val shift: Boolean) {
    fun matches(event: KeyEvent): Boolean {
        if (keyCode <= 0 || event.keyCode != keyCode) return false
        val c = event.isCtrlPressed
        val a = event.isAltPressed
        val s = event.isShiftPressed
        return c == ctrl && a == alt && s == shift
    }

    fun display(): String {
        if (keyCode <= 0) return "None"
        val parts = mutableListOf<String>()
        if (ctrl) parts.add("Ctrl")
        if (alt) parts.add("Alt")
        if (shift) parts.add("Shift")
        val keyName = when (keyCode) {
            KeyEvent.KEYCODE_FORWARD_DEL -> "Del"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_TAB -> "Tab"
            else -> {
                val label = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                if (label.length == 1) label else label.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        parts.add(keyName)
        return parts.joinToString("+")
    }
}

class MainActivity : Activity() {
    lateinit var store: DocumentStore
    var session: DrawingSession? = null; private set
    var editor: EditorView? = null; private set
    internal var vectorDocument:VectorDocument?=null; private set
    internal var vectorEditor:VectorEditorView?=null; private set
    internal var vectorStabilization:Float
        get()=prefs.getFloat("vectorStabilization",.25f).coerceIn(0f,1f)
        set(value){prefs.edit().putFloat("vectorStabilization",value.coerceIn(0f,1f)).apply()}
    internal val twoFingerUndo get()=prefs.getBoolean("twoFingerUndo",true)
    internal val threeFingerRedo get()=prefs.getBoolean("threeFingerRedo",true)
    internal val penOnlyInput get()=prefs.getBoolean("penOnly",false)
    internal val protectVectorControls get()=prefs.getBoolean("protectControls",true)
    internal fun setVectorInputPreference(index:Int,enabled:Boolean){prefs.edit().putBoolean(arrayOf("penOnly","protectControls","twoFingerUndo","threeFingerRedo")[index],enabled).apply()}
    internal var atlas:CanvasAtlas?=null;private set
    internal val atlasLinked get()=atlas!=null
    fun showAtlas(fromGesture:Boolean=false){
        if(navigationBusy || (editor==null && vectorEditor==null))return
        editor?.canvas?.finishQuickShape()
        if(atlas==null){atlas=CanvasAtlas(this);editor?.canvas?.captureAtlas();vectorEditor?.canvas?.captureAtlas()}
        atlas?.enterOverview()
        if(!fromGesture){editor?.dismissPanel();vectorEditor?.dismissPanel();editor?.canvas?.atlasOverview();vectorEditor?.canvas?.atlasOverview()}
        editor?.canvas?.invalidate();vectorEditor?.canvas?.invalidate()
    }
    fun activateAtlasArtwork(art:Artwork){
        val ticket=beginNavigation("Switching canvas…",art.id,compact=true)?:return
        saveCurrent{saved->
            if(!currentNavigation(ticket))return@saveCurrent
            if(!saved){atlas?.switchFailed();finishNavigation(ticket);return@saveCurrent}
            io.execute{val data=runCatching{storedDocument(art.id)};runOnUiThread{if(currentNavigation(ticket))data.onSuccess{(bytes,file)->loadedDocument(bytes,art.id,art.name,ticket,file)}.onFailure{navigationFailed(ticket,"Could not open artwork: ${it.message}")}}}
        }
    }
    fun closeAtlas(animated:Boolean=false){atlas?.close();atlas=null;editor?.canvas?.invalidate();vectorEditor?.canvas?.invalidate()}
    var documentId = ""; private set
    var documentName = "Untitled Artwork"
    val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = object:Runnable {
        override fun run(){if(session?.isStrokeActive==true || vectorEditor?.busy==true)handler.postDelayed(this,1000) else saveCurrent()}
    }
    private var pendingExport: ((Uri) -> Unit)? = null
    private var onImported: ((Uri) -> Unit)? = null
    var lastInputDescription = "Draw with your pen to inspect its input capabilities."
    private val prefs by lazy { getSharedPreferences("studio", MODE_PRIVATE) }
    private val timelapseDelegate:Lazy<TimelapseRecorder> =lazy{TimelapseRecorder(this,updated={timelapseExportProgress?.progress(timelapse.progress)},error={message(it)})}
    internal val timelapse:TimelapseRecorder by timelapseDelegate
    private val vectorRecording=object:Runnable{
        override fun run(){
            val id=documentId
            if(vectorDocument==null || !timelapse.shouldCapture(id))return
            if(vectorEditor?.busy==true){handler.postDelayed(this,500);return}
            timelapseImage{image->if(image!=null){if(documentId==id && vectorDocument!=null)timelapse.capture(id,image);image.recycle()}}
        }
    }
    internal fun scheduleVectorRecording(){handler.removeCallbacks(vectorRecording);handler.postDelayed(vectorRecording,500)}
    private fun timelapseImage(then:(Bitmap?)->Unit){
        if(vectorDocument!=null){val bytes=vectorEditor?.snapshotBytes()?:vectorDocument!!.encode();io.execute{val result=runCatching{VectorDocument.decode(bytes).bitmap(960)};runOnUiThread{if(isDestroyed)result.getOrNull()?.recycle()else then(result.getOrNull())}}}
        else session?.imageSnapshot(then)?:then(null)
    }
    private var timelapseExportProgress:TimelapseExportProgress?=null
    private var timelapseExportBusy=false
    internal fun exportTimelapse(){
        val id=documentId;val exportName=documentName
        if(timelapseExportBusy){timelapseExportProgress?.show();return}
        timelapseExportBusy=true
        timelapseExportProgress?.dismiss()
        val progress=TimelapseExportProgress(this).also{timelapseExportProgress=it;it.preparing()}
        session?.beforeSnapshot?.invoke()
        timelapseImage{image->
            if(image==null){timelapseExportBusy=false;progress.state("Could not prepare artwork. Please try again.");return@timelapseImage}
            if(documentId!=id){image.recycle();timelapseExportBusy=false;progress.dismiss();return@timelapseImage}
            timelapse.export(id,image,timelapse.duration(id)){file,error->
                if(error!=null){timelapseExportBusy=false;progress.state("Export failed: $error")}
                else if(file!=null){
                    progress.state("Video ready. Choose where to save it.")
                    chooseExport("mp4","video/mp4",{file.delete()},exportName,onSaveState={state->if(state!="Saving video…")timelapseExportBusy=false;progress.state(state)}){out->file.inputStream().use{it.copyTo(out)}}
                }
            }
            image.recycle()
        }
    }

    var recordingShortcutAction: String? = null; private set
    private var onShortcutRecorded: ((String, KeyShortcut) -> Unit)? = null

    fun startRecordingShortcut(action: String, onRecorded: (String, KeyShortcut) -> Unit) {
        recordingShortcutAction = action
        onShortcutRecorded = onRecorded
    }

    fun cancelRecordingShortcut() {
        recordingShortcutAction = null
        onShortcutRecorded = null
    }

    var lastDrawingTool: String = "Paint"
    private var lastVectorTool="Pen"

    fun toggleEraser() {
        vectorEditor?.let { e ->
            val current=e.canvas.tool
            if(current=="Select")e.canvas.changeTool(lastVectorTool)
            else { lastVectorTool=current;e.canvas.changeTool("Select") }
            return
        }
        val s = session ?: return
        if (s.tool == "Erase") {
            s.tool = lastDrawingTool
        } else {
            if (s.tool in listOf("Paint", "Smudge")) {
                lastDrawingTool = s.tool
            }
            s.tool = "Erase"
        }
        editor?.onToolSwitched() ?: run {
            editor?.refreshTools()
            editor?.canvas?.invalidate()
        }
    }

    fun getShortcut(action: String): KeyShortcut {
        val defCode = when(action) {
            "undo", "redo" -> KeyEvent.KEYCODE_Z
            "toggle_eraser" -> KeyEvent.KEYCODE_E
            else -> 0
        }
        val defCtrl = when(action) {
            "undo", "redo" -> true
            else -> false
        }
        val defShift = action == "redo"
        val code = prefs.getInt("shortcut_${action}_code", defCode)
        val ctrl = prefs.getBoolean("shortcut_${action}_ctrl", defCtrl)
        val alt = prefs.getBoolean("shortcut_${action}_alt", false)
        val shift = prefs.getBoolean("shortcut_${action}_shift", defShift)
        return KeyShortcut(code, ctrl, alt, shift)
    }

    fun resetShortcut(action:String){
        cancelRecordingShortcut()
        prefs.edit().remove("shortcut_${action}_code").remove("shortcut_${action}_ctrl").remove("shortcut_${action}_alt").remove("shortcut_${action}_shift").apply()
    }
    fun setShortcut(action: String, shortcut: KeyShortcut) {
        prefs.edit()
            .putInt("shortcut_${action}_code", shortcut.keyCode)
            .putBoolean("shortcut_${action}_ctrl", shortcut.ctrl)
            .putBoolean("shortcut_${action}_alt", shortcut.alt)
            .putBoolean("shortcut_${action}_shift", shortcut.shift)
            .apply()
    }

    private var navigationGeneration=0L
    internal var navigationBusy=false;private set
    private var navigationOverlay:NavigationOverlay?=null
    private var navigationSession:DrawingSession?=null
    private var navigationExitView:View?=null
    private fun currentNavigation(ticket:Long)=navigationBusy && navigationGeneration==ticket && !isDestroyed
    private fun beginNavigation(message:String,artworkId:String?=null,compact:Boolean=false):Long?{
        if(navigationBusy || isDestroyed)return null
        navigationBusy=true;val ticket=++navigationGeneration;deferAutosave()
        val root=findViewById<ViewGroup>(android.R.id.content)
        (root.getChildAt(0) as? GalleryView)?.setOpening(artworkId)
        navigationOverlay=NavigationOverlay(this,message,compact).also{root.addView(it,ViewGroup.LayoutParams(-1,-1));it.bringToFront();it.requestFocus()}
        return ticket
    }
    private fun finishNavigation(ticket:Long){if(!currentNavigation(ticket))return;navigationSession=null;clearNavigation()}
    private fun clearNavigation(){
        navigationBusy=false
        navigationExitView?.let{it.animate().cancel();it.alpha=1f;it.scaleX=1f;it.scaleY=1f};navigationExitView=null
        navigationOverlay?.let{(it.parent as? ViewGroup)?.removeView(it)};navigationOverlay=null
        (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? GalleryView)?.setOpening(null)
    }
    private fun cancelNavigation(){navigationGeneration++;navigationSession?.close();navigationSession=null;clearNavigation()}
    private fun navigationFailed(ticket:Long,error:String){if(!currentNavigation(ticket))return;atlas?.switchFailed();navigationSession?.close();navigationSession=null;finishNavigation(ticket);message(error)}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs.edit().remove("finger").apply()
        BrushTips.init(this)
        DrawingSession.memoryBudget=DrawingSession.budgetFor(android.app.ActivityManager.MemoryInfo().also{(getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)}.totalMem)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        store = DocumentStore(this)
        if (store.list().isEmpty()) newCanvas(1600, 1200) else showGallery()
    }

    fun newCanvas(width: Int, height: Int, dpi:Int=300, name:String="Untitled Artwork", background:Int=Color.WHITE, visible:Boolean=true, mode:String="raster") {
        cancelNavigation();closeAtlas()
        if(mode=="vector"){
            runCatching{require(width in 16..8192 && height in 16..8192 && width.toLong()*height<=16_777_216);val doc=VectorDocument(width,height,dpi,background,visible);openVector(doc,store.newId(),name);scheduleSave()}.onFailure{message(it.message?:"Cannot create Vector canvas")};return
        }
        runCatching {
            val next = DrawingSession(width, height)
            vectorDocument=null;vectorEditor=null
            session?.close(); session = next; documentId = store.newId(); documentName = name
            next.command("setup",org.json.JSONObject().put("dpi",dpi).put("color",background.toLong() and 0xffffffffL).put("visible",visible))
            configure(next); openEditor(); scheduleSave()
        }.onFailure { message(it.message ?: "Cannot create this canvas") }
    }
    private fun openVector(doc:VectorDocument,id:String,name:String){
        session?.close();session=null;editor=null;canvasSetup=null;canvasPopup?.dismiss();canvasPopup=null
        vectorDocument=doc;documentId=id;documentName=name;timelapse.prepare(id)
        atlas?.refresh();atlas?.activateCurrent()
        vectorEditor=VectorEditorView(this,doc);setContentView(vectorEditor);vectorEditor?.let{if(atlas==null)StudioMotion.canvasEnter(it);it.post{if(vectorEditor===it && it.isAttachedToWindow)Tutorials.welcome(this)}};scheduleVectorRecording()
    }
    /** A binary raster document opens from its file; other formats are read into memory. */
    private fun storedDocument(id:String):Pair<ByteArray?,java.io.File?> =store.binaryDocument(id)?.let{null to it}?:(store.read(id) to null)
    /** Opens [bytes], or the binary document [file] when given; a [temporary] file is deleted after loading. */
    private fun loadedDocument(bytes:ByteArray?,id:String,name:String,existingTicket:Long?=null,file:java.io.File?=null,temporary:Boolean=false){
        val ticket=existingTicket?:beginNavigation("Opening $name…")?:run{if(temporary)file?.delete();return}
        if(!currentNavigation(ticket)){if(temporary)file?.delete();return}
        io.execute{
            val parsed=runCatching{if(file==null && VectorDocument.isVector(checkNotNull(bytes)))VectorDocument.decode(bytes)else null}
            runOnUiThread{
                if(!currentNavigation(ticket))return@runOnUiThread
                parsed.onSuccess{vector->
                    if(vector!=null){
                        openVector(vector,id,name)
                        vectorEditor?.post{finishNavigation(ticket)}
                    }else{
                        val next=runCatching{DrawingSession(32,32)}.getOrElse{navigationFailed(ticket,"Could not open project: ${it.message}");return@onSuccess}
                        navigationSession=next
                        var backendReady=false
                        fun present(){
                            if(!currentNavigation(ticket) || !backendReady || next.frameSerial==0L)return
                            navigationSession=null
                            session?.close();session=next;vectorDocument=null;vectorEditor=null;documentId=id;documentName=name
                            openEditor();finishNavigation(ticket)
                        }
                        next.onFrame={present()}
                        next.onError={navigationFailed(ticket,"Could not open project: $it")}
                        val loaded:(Boolean)->Unit=loaded@{ok->
                            if(!currentNavigation(ticket)){next.close();return@loaded}
                            if(!ok){navigationFailed(ticket,"Could not open project");return@loaded}
                            configure(next)
                            next.command("info"){backendReady=true;present()}
                        }
                        if(file!=null)next.load(file,temporary,loaded) else next.load(checkNotNull(bytes),loaded)
                    }
                }.onFailure{navigationFailed(ticket,"Could not open project: ${it.message}")}
            }
        }
    }
    private fun configure(s: DrawingSession) {
        s.onBrushChosen={tool,preset->RecentBrushes(this).record(tool,preset)}
        s.command("historyDirectory",JSONObject().put("path",java.io.File(cacheDir,"undo-history").absolutePath))
        s.setRenderer(prefs.getString("rendererMode","auto")?:"auto")
        s.setSymmetry(prefs.getString("symmetryMode","Off")?:"Off")
        s.eraserHardness=prefs.getFloat("eraserHardness",1f)
        s.pressureEnabled=prefs.getBoolean("pressureEnabled",true);s.canvasLocked=prefs.getBoolean("canvasLocked",false);s.quickShape=prefs.getBoolean("quickShape",true)
        s.penOnly=prefs.getBoolean("penOnly",false)
        s.protectControls=prefs.getBoolean("protectControls",true)
        s.stabilization=prefs.getFloat("stabilization",.12f).coerceIn(0f,.95f)
        s.rightSidebar = prefs.getBoolean("right", false)
        s.lightInterface = prefs.getBoolean("light", false)
        s.cursor = prefs.getBoolean("cursor", true)
        s.dynamicBrushScaling=prefs.getBoolean("dynamicBrushScaling",true)
        s.rapidUndoDelay=prefs.getFloat("rapidUndoDelay",.47f)
        s.selectionVisibility=prefs.getFloat("selectionVisibility",.3f)
        s.cursorStyle=prefs.getString("cursorStyle","Contrast")?:"Contrast"
        s.cursorOpacity=prefs.getFloat("cursorOpacity",1f)
        s.twoFingerUndo=prefs.getBoolean("twoFingerUndo",true);s.threeFingerRedo=prefs.getBoolean("threeFingerRedo",true)
        s.fourFingerFullScreen=prefs.getBoolean("fourFingerFullScreen",true);s.holdEyedropper=prefs.getBoolean("holdEyedropper",true);s.stylusButtonAction=StylusAction.from(prefs.getString("stylusButtonAction",if(prefs.getBoolean("penButtonErase",true))"erase" else "none"))
        s.pinchToFit=prefs.getBoolean("pinchToFit",true)
        s.color = prefs.getInt("color", 0xff202020.toInt())
        fun restoreBrush(b: DrawingSession.ToolBrush, key: String) {
            val name = prefs.getString("${key}_preset", null)?.let(LegacyNames::brush)
            if (name != null) BrushLibrary(this).all().find { it.name == name }?.let { b.preset = it }
            b.size = prefs.getFloat("${key}_size", b.preset.size)
            b.opacity = prefs.getFloat("${key}_opacity", 1f)
            b.flow=prefs.getFloat("${key}_flow",b.preset.flow)
            b.pressureSize=prefs.getFloat("${key}_pressureSize",b.preset.pressureSize)
            b.pressureOpacity=prefs.getFloat("${key}_pressureOpacity",b.preset.pressureOpacity)
            b.fadeLength=prefs.getFloat("${key}_fadeLength",b.preset.fadeLength)
            b.kind=prefs.getInt("${key}_kind",b.preset.kind).takeIf{it in 0..14 || it in 32..42 || (it==IMAGE_TIP_KIND && b.preset.tip.isNotEmpty())}?:b.preset.kind;b.colorLayout=prefs.getString("${key}_layout",b.preset.colorLayout)?:"Along"
            b.colorMode=prefs.getString("${key}_colorMode",b.preset.colorMode)?:"Solid";b.colorCycle=prefs.getFloat("${key}_cycle",b.preset.colorCycle);b.wetMix=prefs.getFloat("${key}_mix",b.preset.wetMix);b.wetPickup=prefs.getFloat("${key}_pickup",b.preset.wetPickup).coerceIn(0f,1f);b.wetness=prefs.getFloat("${key}_wetness",b.preset.wetness).coerceIn(0f,1f);b.palette=runCatching{val a=org.json.JSONArray(prefs.getString("${key}_palette",org.json.JSONArray(b.preset.palette).toString()));(0 until a.length().coerceAtMost(12)).map{a.getLong(it).toInt()}}.getOrDefault(b.preset.palette)
            b.tipSpacing=prefs.getFloat("${key}_tipSpacing",b.preset.tipSpacing).coerceIn(.02f,4f);b.tipFollow=prefs.getFloat("${key}_tipFollow",b.preset.tipFollow).coerceIn(-1f,1f);b.tipJitter=prefs.getFloat("${key}_tipJitter",b.preset.tipJitter).coerceIn(0f,1f);b.grainScale=prefs.getFloat("${key}_grainScale",b.preset.grainScale).coerceIn(.05f,16f)
            b.tipRotation=prefs.getFloat("${key}_tipRotation",b.preset.tipRotation).coerceIn(-7f,7f);b.tipSizeJitter=prefs.getFloat("${key}_tipSizeJitter",b.preset.tipSizeJitter).coerceIn(0f,1f);b.tipOpacityJitter=prefs.getFloat("${key}_tipOpacityJitter",b.preset.tipOpacityJitter).coerceIn(0f,1f);b.tipScatter=prefs.getFloat("${key}_tipScatter",b.preset.tipScatter).coerceIn(0f,3f);b.grainRolling=prefs.getBoolean("${key}_grainRolling",b.preset.grainRolling);b.tipSpeedOpacity=prefs.getFloat("${key}_tipSpeedOpacity",b.preset.tipSpeedOpacity).coerceIn(0f,1f);b.tipDynamics=prefs.getString("${key}_tipDynamics",null)?.let{runCatching{TipDynamics.from(org.json.JSONObject(it))}.getOrNull()}?:b.preset.tipDynamics
        }
        restoreBrush(s.paintBrush, "paint_brush")
        restoreBrush(s.smudgeBrush, "smudge_brush")
        restoreBrush(s.eraseBrush, "erase_brush")
        s.paintMixingEnabled=prefs.getBoolean("paintMixingEnabled",s.paintBrush.wetMix>0f)
    }
    fun savePreferences() {
        val s = session ?: return
        val edit=prefs.edit().putBoolean("paintMixingEnabled",s.paintMixingEnabled)
        for((key,b) in listOf("paint_brush" to s.paintBrush,"smudge_brush" to s.smudgeBrush,"erase_brush" to s.eraseBrush)){
            edit.putInt("${key}_kind",b.kind).putString("${key}_layout",b.colorLayout)
            edit.putString("${key}_palette",org.json.JSONArray(b.palette).toString()).putString("${key}_colorMode",b.colorMode).putFloat("${key}_cycle",b.colorCycle).putFloat("${key}_mix",b.wetMix).putFloat("${key}_pickup",b.wetPickup).putFloat("${key}_wetness",b.wetness)
            edit.putFloat("${key}_flow",b.flow).putFloat("${key}_pressureSize",b.pressureSize).putFloat("${key}_pressureOpacity",b.pressureOpacity).putFloat("${key}_fadeLength",b.fadeLength)
            edit.putFloat("${key}_tipSpacing",b.tipSpacing).putFloat("${key}_tipFollow",b.tipFollow).putFloat("${key}_tipJitter",b.tipJitter).putFloat("${key}_grainScale",b.grainScale)
            edit.putFloat("${key}_tipRotation",b.tipRotation).putFloat("${key}_tipSizeJitter",b.tipSizeJitter).putFloat("${key}_tipOpacityJitter",b.tipOpacityJitter).putFloat("${key}_tipScatter",b.tipScatter).putBoolean("${key}_grainRolling",b.grainRolling).putFloat("${key}_tipSpeedOpacity",b.tipSpeedOpacity).putString("${key}_tipDynamics",b.tipDynamics.json().toString())
        }
        edit.putString("symmetryMode",s.symmetryMode).putFloat("eraserHardness",s.eraserHardness).putBoolean("penOnly",s.penOnly).putBoolean("protectControls",s.protectControls).putFloat("stabilization",s.stabilization).putString("rendererMode",s.rendererMode).putBoolean("pressureEnabled",s.pressureEnabled).putBoolean("canvasLocked",s.canvasLocked).putBoolean("quickShape",s.quickShape).putString("cursorStyle",s.cursorStyle).putFloat("cursorOpacity",s.cursorOpacity)
            .putBoolean("twoFingerUndo",s.twoFingerUndo).putBoolean("threeFingerRedo",s.threeFingerRedo)
            .putBoolean("fourFingerFullScreen",s.fourFingerFullScreen).putBoolean("holdEyedropper",s.holdEyedropper).putBoolean("penButtonErase",s.penButtonErase).putString("stylusButtonAction",s.stylusButtonAction.key)
            .remove("finger").putBoolean("right", s.rightSidebar)
            .putBoolean("pinchToFit",s.pinchToFit)
            .putBoolean("dynamicBrushScaling",s.dynamicBrushScaling).putFloat("rapidUndoDelay",s.rapidUndoDelay).putFloat("selectionVisibility",s.selectionVisibility)
            .putBoolean("light", s.lightInterface).putBoolean("cursor", s.cursor).putInt("color", editor?.colorBeforeMask?:s.color)
            .putString("paint_brush_preset", s.paintBrush.preset.name).putFloat("paint_brush_size", s.paintBrush.size).putFloat("paint_brush_opacity", s.paintBrush.opacity)
            .putString("smudge_brush_preset", s.smudgeBrush.preset.name).putFloat("smudge_brush_size", s.smudgeBrush.size).putFloat("smudge_brush_opacity", s.smudgeBrush.opacity)
            .putString("erase_brush_preset", s.eraseBrush.preset.name).putFloat("erase_brush_size", s.eraseBrush.size).putFloat("erase_brush_opacity", s.eraseBrush.opacity)
            .apply()
    }
    private fun openEditor() {
        val s = session ?: return
        timelapse.prepare(documentId)
        atlas?.refresh();atlas?.activateCurrent()
        editor = EditorView(this, s)
        setContentView(editor);editor?.let{view->if(atlas==null)StudioMotion.canvasEnter(view);view.post{if(editor===view && view.isAttachedToWindow)Tutorials.welcome(this)}}
        s.onCommittedFrame={image->if(session===s && timelapse.enabled(documentId))timelapse.capture(documentId,image)}
        s.captureNextFrame()
    }
    fun deferAutosave(){handler.removeCallbacks(autosave)}
    fun scheduleSave() { handler.removeCallbacks(autosave); handler.postDelayed(autosave, 8000) }
    fun saveCurrent(then: (Boolean) -> Unit = {}) {
        handler.removeCallbacks(autosave)
        vectorDocument?.let{doc->
            val bytes=vectorEditor?.snapshotBytes()?:doc.encode();val id=documentId;val name=documentName
            io.execute{val result=runCatching{val copy=VectorDocument.decode(bytes);val image=copy.bitmap(480);try{store.save(id,name,bytes,image,doc.width,doc.height,"vector")}finally{image.recycle()}}
                runOnUiThread{result.onFailure{message("Could not save Vector project: ${it.message}")};then(result.isSuccess)}};return
        }
        val s = session ?: run { then(true); return }
        s.persist(store, documentId, documentName) { error ->
            if (error != null) message("Could not save: $error")
            then(error == null)
        }
        savePreferences()
    }
    fun gallery() {
        if(editor==null && vectorEditor==null)return
        val ticket=beginNavigation("Saving artwork…")?:return
        saveCurrent{saved->
            if(!currentNavigation(ticket))return@saveCurrent
            if(!saved){finishNavigation(ticket);return@saveCurrent}
            val view=editor?:vectorEditor
            if(view!=null){navigationExitView=view;StudioMotion.canvasExit(view){if(currentNavigation(ticket))showGallery()}}else showGallery()
        }
    }
    fun showGallery() {
        closeAtlas()
        cancelNavigation()
        canvasSetup=null;canvasPopup?.dismiss();canvasPopup=null
        session?.close(); session = null
        editor = null;vectorDocument=null;vectorEditor=null
        setContentView(GalleryView(this).also{view->StudioMotion.canvasEnter(view);view.post{if(view.isAttachedToWindow)Tutorials.welcome(this)}})
    }
    fun openArtwork(art: Artwork) {
        val ticket=beginNavigation("Opening ${art.name}…",art.id)?:return
        io.execute {
            val data=runCatching{storedDocument(art.id)}
            runOnUiThread{
                if(!currentNavigation(ticket))return@runOnUiThread
                data.onSuccess{(bytes,file)->loadedDocument(bytes,art.id,art.name,ticket,file)}.onFailure{navigationFailed(ticket,"Could not open artwork: ${it.message}")}
            }
        }
    }
    internal var canvasPopup:PopupWindow?=null
    internal var canvasSetup:CanvasSetupView?=null
    fun createDialog(anchor:View?=null) {
        canvasPopup?.dismiss()
        val d=resources.displayMetrics.density
        val content=NewCanvasMenu(this){canvasPopup?.dismiss();canvasPopup=null}
        val popup=PopupWindow(content,minOf((400*d).toInt(),resources.displayMetrics.widthPixels-(24*d).toInt()),minOf((730*d).toInt(),resources.displayMetrics.heightPixels-(98*d).toInt()),true)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));popup.isOutsideTouchable=true;popup.elevation=12*d
        canvasPopup=popup
        popup.showAtLocation(window.decorView,Gravity.TOP or Gravity.RIGHT,(18*d).toInt(),(82*d).toInt())
        StudioMotion.reveal(content,1f)
    }
    fun customCanvas(mode:String="raster"){canvasSetup=CanvasSetupView(this,mode);setContentView(canvasSetup)}
    fun renameCurrent() {
        textDialog("Artwork name", documentName) { name -> documentName = name; scheduleSave() }
    }
    fun textDialog(title: String, initial: String, then: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); selectAll(); isSingleLine = true }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply { setPadding(pad, 12, pad, 0); addView(input) }
        AlertDialog.Builder(this).setTitle(title).setView(container).setPositiveButton("Done") { _, _ ->
            input.text.toString().trim().take(100).takeIf { it.isNotEmpty() }?.let(then)
        }.setNegativeButton("Cancel", null).show()
    }
    private var pendingExportCleanup:(()->Unit)?=null
    private var pendingGalleryKind:String?=null
    private fun chooseExport(suffix:String,mime:String,cleanup:()->Unit={},nameOverride:String?=null,onSaveState:(String)->Unit={},write:(java.io.OutputStream)->Unit){
        if(isDestroyed){cleanup();return}
        pendingExportCleanup?.invoke();pendingExportCleanup={cleanup();onSaveState("Save cancelled")}
        val name=nameOverride?:documentName
        pendingExport={uri->onSaveState("Saving video…");io.execute{
            val result=runCatching{contentResolver.openOutputStream(uri,"wt").use{write(checkNotNull(it){"No output stream"})}}
            cleanup()
            runOnUiThread{if(!isDestroyed)result.onSuccess{onSaveState("Saved · $name.$suffix");message("Exported $name.$suffix")}.onFailure{onSaveState("Save failed: ${it.message}");message("Export failed: ${it.message}")}}
        }}
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,"$name.$suffix")
        try{
            @Suppress("DEPRECATION")
            startActivityForResult(intent,100)
        }catch(e:Exception){pendingExport=null;pendingExportCleanup=null;cleanup();onSaveState("Could not open file picker: ${e.message}");message("Could not open file picker: ${e.message}")}
    }
    fun export(kind:String){
        if(vectorDocument!=null){exportVector(kind);return}
        val s=session?:return
        when(kind){
            "PSD"->{
                val file=runCatching{java.io.File.createTempFile("inkstone-export-",".psd",cacheDir)}.getOrElse{message("Export failed: ${it.message}");return}
                editor?.showNotice("Preparing layered PSD…")
                s.writePsd(file){error->
                    if(error!=null)message("PSD export failed: $error")
                    else chooseExport("psd","image/vnd.adobe.photoshop",{file.delete()}){output->file.inputStream().use{it.copyTo(output)}}
                }
            }
            "Inkstone"->{
                val file=runCatching{java.io.File.createTempFile("inkstone-export-",".inkstone",cacheDir)}.getOrElse{message("Export failed: ${it.message}");return}
                s.documentFile(file){error->
                    if(error!=null){file.delete();message("Could not export artwork: $error")}
                    else chooseExport("inkstone","application/octet-stream",{file.delete()}){output->file.inputStream().use{it.copyTo(output)}}
                }
            }
            "PNG","JPEG"->s.imageSnapshot{image->
                if(image==null)message("Could not export artwork")
                else chooseExport(if(kind=="JPEG")"jpg" else "png",if(kind=="JPEG")"image/jpeg" else "image/png",{image.recycle()}){output->ImageExports.encode(image,kind,output)}
            }
            else->message("Unsupported export format")
        }
    }
    private fun vectorImage(then:(Bitmap?)->Unit){
        val bytes=vectorEditor?.snapshotBytes()?:vectorDocument?.encode()?:return
        io.execute{val result=runCatching{VectorDocument.decode(bytes).bitmap()};runOnUiThread{if(isDestroyed)result.getOrNull()?.recycle() else then(result.getOrNull())}}
    }
    private fun exportVector(kind:String){
        val bytes=vectorEditor?.snapshotBytes()?:vectorDocument?.encode()?:return
        when(kind){
            "Inkstone"->chooseExport("inkstone","application/octet-stream"){output->GZIPOutputStream(output).use{it.write(bytes)}}
            "SVG"->io.execute{val result=runCatching{VectorDocument.decode(bytes).svg().toByteArray(Charsets.UTF_8)};runOnUiThread{result.onSuccess{svg->chooseExport("svg","image/svg+xml"){it.write(svg)}}.onFailure{message("SVG export failed: ${it.message}")}}}
            "PNG","JPEG"->vectorImage{image->if(image==null)message("Could not render Vector artwork")else chooseExport(if(kind=="JPEG")"jpg" else "png",if(kind=="JPEG")"image/jpeg" else "image/png",{image.recycle()}){ImageExports.encode(image,kind,it)}}
            "PSD"->{val file=runCatching{java.io.File.createTempFile("inkstone-export-",".psd",cacheDir)}.getOrElse{message(it.message?:"Could not create PSD");return}
                message("Preparing rasterized Vector layers…")
                io.execute{val result=runCatching{VectorDocument.decode(bytes).writePsd(file)};runOnUiThread{result.onSuccess{chooseExport("psd","image/vnd.adobe.photoshop",{file.delete()}){output->file.inputStream().use{it.copyTo(output)}}}.onFailure{file.delete();message("PSD export failed: ${it.message}")}}}
            }
        }
    }
    fun saveImageToGallery(kind:String){
        if(kind !in listOf("PNG","JPEG"))return
        if(android.os.Build.VERSION.SDK_INT<29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingGalleryKind=kind;requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),102);return
        }
        val name=documentName
        val save:(Bitmap?)->Unit={image->
            if(image==null)message("Could not render artwork")
            else io.execute{val result=runCatching{ImageExports.saveToGallery(this,image,kind,name)};image.recycle();runOnUiThread{result.onSuccess{message("Saved to Pictures/Inkstone")}.onFailure{message("Gallery save failed: ${it.message}")}}}
        }
        if(vectorDocument!=null){vectorImage(save);return}
        session?.imageSnapshot{image->
            if(image==null){message("Could not render artwork");return@imageSnapshot}
            io.execute{
                val result=runCatching{ImageExports.saveToGallery(this,image,kind,name)}
                image.recycle();runOnUiThread{result.onSuccess{message("Saved to Pictures/Inkstone")}.onFailure{message("Gallery save failed: ${it.message}")}}
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==102){val kind=pendingGalleryKind;pendingGalleryKind=null
            if(kind!=null && grantResults.firstOrNull()==android.content.pm.PackageManager.PERMISSION_GRANTED)saveImageToGallery(kind)
            else message("Gallery access denied. You can still export PNG or JPG to a folder.")
        }
    }
    fun importImage() {
        if(vectorDocument!=null){message("Photo insertion belongs to Raster canvases. Vector mode stores paths and shapes.");return}
        onImported = { uri ->
            io.execute {
                val result = runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image format" }
                    var sample = 1
                    while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 8_388_608) sample *= 2
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    contentResolver.openInputStream(uri).use { checkNotNull(BitmapFactory.decodeStream(it, null, opts)) }
                }
                runOnUiThread {
                    result.onSuccess { image ->
                        if (editor == null) newCanvas(image.width.coerceAtLeast(16), image.height.coerceAtLeast(16))
                        session?.import(image) { editor?.startLayerTransform();scheduleSave() }; image.recycle()
                    }.onFailure { message("Import failed: ${it.message}") }
                }
            }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), 101)
    }
    private fun openPsd(file:java.io.File){
        val next=DrawingSession(32,32);message("Opening layered PSD…")
        next.importPsd(file){error,warnings->
            if(isDestroyed){next.close();return@importPsd}
            if(error!=null){next.close();message("PSD import failed: $error")}
            else{session?.close();session=next;vectorDocument=null;vectorEditor=null;documentId=store.newId();documentName="Imported PSD";configure(next);openEditor();scheduleSave()
                if(warnings.isNotEmpty())AlertDialog.Builder(this).setTitle("PSD import notes").setMessage(warnings.joinToString("\n\n")).setPositiveButton("OK",null).show()}
        }
    }
    fun importDocument() {
        saveCurrent{saved->if(saved){
            onImported={uri->io.execute{
                val result=runCatching{
                    contentResolver.openInputStream(uri).use{stream->
                        val input=java.io.BufferedInputStream(checkNotNull(stream));input.mark(4);val signature=ByteArray(4);val count=input.read(signature);input.reset()
                        if(count==4 && String(signature,Charsets.US_ASCII)=="8BPS"){
                            val file=java.io.File.createTempFile("inkstone-import-",".psd",cacheDir)
                            try{file.outputStream().use{output->val buffer=ByteArray(65536);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=128L*1024*1024){"PSD files must be smaller than 128 MiB"};output.write(buffer,0,n)}};null to file}catch(e:Exception){file.delete();throw e}
                        }else if(DocumentStore.isBinary(input)){
                            // Binary documents can exceed the Java heap, so the engine reads a copy from disk.
                            val file=java.io.File.createTempFile("inkstone-import-",".inkstone",cacheDir)
                            try{file.outputStream().use{input.copyTo(it)};null to file}catch(e:Exception){file.delete();throw e}
                        }else DocumentStore.decode(input) to null
                    }
                }
                runOnUiThread{result.onSuccess{(bytes,file)->when{
                    isDestroyed->file?.delete()
                    file!=null && file.extension=="psd"->openPsd(file)
                    file!=null->loadedDocument(null,store.newId(),"Imported Artwork",file=file,temporary=true)
                    else->loadedDocument(checkNotNull(bytes),store.newId(),"Imported Artwork")
                }}.onFailure{message("Import failed: ${it.message}")}}
            }}
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),101)
        }}
    }
    /** Imports Procreate .brush or .brushset files into the brush library. [then] gets the category used. */
    internal fun importBrushes(then:(String)->Unit){
        onImported={uri->
            val fileName=runCatching{contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0) else null}}.getOrNull()
            message("Importing brushes…")
            io.execute{
                val result=runCatching{
                    val read=contentResolver.openInputStream(uri).use{ProcreateBrushes.read(checkNotNull(it){"Cannot open this file"})}
                    require(BrushLibrary(this).custom().size+read.brushes.size<=500){"Brush library is full"}
                    val group=(read.setName?:fileName?.substringBeforeLast('.')?.takeIf{read.brushes.size>1}?:"Imported").trim().ifEmpty{"Imported"}.take(60)
                    try{group to BrushLibrary(this).addAll(read.brushes.map{ProcreateBrushes.preset(it,group)},group)}
                    finally{read.brushes.forEach{it.shape?.recycle();it.grain?.recycle()}}
                }
                runOnUiThread{result.onSuccess{(group,added)->
                    message(if(added.size==1)"Imported ${added[0].name}" else "Imported ${added.size} brushes into $group");if(!isDestroyed)then(group)
                }.onFailure{message("Brush import failed: ${it.message}")}}
            }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),101)
    }
    internal fun chooseReference(then:(Bitmap)->Unit){
        onImported={uri->io.execute{val result=runCatching{
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};contentResolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,bounds)}
            require(bounds.outWidth>0 && bounds.outHeight>0){"Unsupported reference image"};var sample=1
            while(bounds.outWidth.toLong()/sample*(bounds.outHeight/sample)>2_097_152 || bounds.outWidth/sample>4096 || bounds.outHeight/sample>4096)sample*=2
            contentResolver.openInputStream(uri).use{checkNotNull(BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}))}
        };runOnUiThread{result.onSuccess{if(isDestroyed)it.recycle()else then(it)}.onFailure{message("Reference import failed: ${it.message}")}}}}
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),101)
    }
    @Deprecated("Activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==100){
            val action=pendingExport;val cleanup=pendingExportCleanup;pendingExport=null;pendingExportCleanup=null
            val uri=data?.data
            if(resultCode==RESULT_OK && uri!=null)action?.invoke(uri) else cleanup?.invoke()
        }else if(requestCode==101){if(resultCode==RESULT_OK)data?.data?.let{onImported?.invoke(it)};onImported=null}
    }
    fun message(text: String) { if (!isDestroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(!hasFocus)editor?.canvas?.stylusButtons?.reset()}
    override fun onPause() { cancelRecordingShortcut();editor?.canvas?.stylusButtons?.reset();super.onPause(); saveCurrent() }
    override fun onDestroy() { timelapseExportProgress?.dismiss();timelapseExportProgress=null;Tutorials.dismiss(this);closeAtlas();cancelNavigation();pendingExportCleanup?.invoke();pendingExportCleanup=null;pendingExport=null;handler.removeCallbacks(autosave);handler.removeCallbacks(vectorRecording);if(timelapseDelegate.isInitialized())timelapse.close();session?.close(); io.shutdown(); super.onDestroy() }
    @Deprecated("Native back handling")
    override fun onBackPressed() { if(navigationBusy){cancelNavigation();return};if(atlas!=null){closeAtlas(true);editor?.canvas?.animateFit();vectorEditor?.canvas?.fit();return};if(canvasSetup!=null){showGallery();return};if(canvasPopup?.isShowing==true){canvasPopup?.dismiss();return}; if(vectorEditor!=null){if(vectorEditor?.dismissPanel()!=true)gallery();return};if (editor != null) { if (!editor!!.dismissPanel()) gallery() } else {val gallery=(findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0) as? GalleryView);if(gallery?.handleBack()!=true)super.onBackPressed()} }
    internal fun recordShortcutKey(event:KeyEvent):Boolean {
        if(recordingShortcutAction==null)return false
        if(event.action!=KeyEvent.ACTION_DOWN)return true
        if(event.repeatCount>0)return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            recordingShortcutAction?.let { action ->
                val keyCode = event.keyCode
                if (keyCode in listOf(
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT
                )) {
                    return true
                }
                val shortcut = KeyShortcut(
                    keyCode = keyCode,
                    ctrl = event.isCtrlPressed,
                    alt = event.isAltPressed,
                    shift = event.isShiftPressed
                )
                setShortcut(action, shortcut)
                val cb = onShortcutRecorded
                recordingShortcutAction = null
                onShortcutRecorded = null
                cb?.invoke(action, shortcut)
                message("Shortcut for ${action.replaceFirstChar { it.uppercase() }} set to ${shortcut.display()}")
                return true
            }
        }
        return true
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(recordShortcutKey(event))return true
        if(atlas?.editing!=false && editor?.canvas?.stylusButtons?.key(event)==true){lastInputDescription="Stylus key ${event.keyCode}, action ${event.action}, source ${event.source}";return true}
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(atlas?.editing==false)return super.onKeyDown(keyCode,event)
        val undoShortcut = getShortcut("undo")
        val redoShortcut = getShortcut("redo")
        val eraserShortcut = getShortcut("toggle_eraser")

        if (undoShortcut.matches(event) && (editor!=null || vectorEditor!=null)) {
            if (vectorEditor != null) {
                vectorEditor?.canvas?.undo()
            } else {
                session?.let { s -> s.command("undo"); scheduleSave() }
            }
            return true
        }
        if (redoShortcut.matches(event) && (editor!=null || vectorEditor!=null)) {
            if (vectorEditor != null) {
                vectorEditor?.canvas?.redo()
            } else {
                session?.let { s -> s.command("redo"); scheduleSave() }
            }
            return true
        }
        if (eraserShortcut.matches(event) && (editor!=null || vectorEditor!=null)) {
            if(event.repeatCount==0)toggleEraser()
            return true
        }

        if(vectorEditor?.key(keyCode,event)==true)return true
        val s = session
        if (s != null && editor != null) {
            if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_S) { saveCurrent { if (it) message("Artwork saved") }; return true }
            if (keyCode == KeyEvent.KEYCODE_B) { s.tool = "Paint"; lastDrawingTool = "Paint"; editor?.refreshTools(); return true }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) { editor?.dismissPanel(); s.command("deselect"); editor?.canvas?.deselect(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
