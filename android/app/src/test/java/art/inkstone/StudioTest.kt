package art.inkstone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class StudioTest {
    private fun until(test:()->Boolean) {
        val end=System.nanoTime()+20_000_000_000
        while(System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)}
        error("Timed out waiting for drawing worker")
    }
    private fun shutdown(controller:org.robolectric.android.controller.ActivityController<MainActivity>){
        val session=controller.get().session
        controller.pause().stop().destroy()
        until{session==null || session.fullyClosed}
    }
    private fun click(root:View,description:String) {
        fun find(v:View):View? {
            if(v.contentDescription?.toString()==description || v is TextView && v.text.toString()==description)return v
            if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it}
            return null
        }
        if(find(root)==null && root is EditorView && root.session.tool=="Transform" && description in listOf("Smaller","Larger","Flip H","Flip V","Rotate 45°","Fit to Canvas")){fun group(v:View):View?{if(v.contentDescription=="Adjust")return v;if(v is ViewGroup)for(i in 0 until v.childCount)group(v.getChildAt(i))?.let{return it};return null};assertTrue(checkNotNull(group(root)).performClick());shadowOf(Looper.getMainLooper()).idle()}
        val v=checkNotNull(find(root)){"No control named $description"};assertTrue(v.performClick());shadowOf(Looper.getMainLooper()).idle()
    }
    private fun render(view:View,name:String,w:Int=1280,h:Int=800) {
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        view.rootView.takeIf{it!==view}?.let{root->root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)};view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
        view.layout(0,0,w,h)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        val file=File(System.getProperty("inkstone.artifacts"),"$name.png");file.parentFile?.mkdirs()
        file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        assertTrue(file.length()>2000);bitmap.recycle()
    }
    private fun landscape(s:DrawingSession) {
        fun polygon(color:Long,vararg xy:Int){
            val points=JSONArray();for(i in xy.indices step 2)points.put(JSONArray().put(xy[i]).put(xy[i+1]))
            s.command("select",JSONObject().put("shape","Freehand").put("points",points))
            s.command("fill",JSONObject().put("color",color));s.command("deselect")
        }
        s.command("background",JSONObject().put("color",0xffe8d9c2L))
        s.command("layer",JSONObject().put("name","Distant ridges"))
        polygon(0xffa5b4acL,0,820,240,470,400,640,640,310,850,640,1060,430,1600,870,1600,1200,0,1200)
        s.command("add");s.command("layer",JSONObject().put("name","Evening hills"))
        polygon(0xff507a75L,0,940,250,760,510,900,820,650,1080,850,1400,690,1600,760,1600,1200,0,1200)
        s.command("add");s.command("layer",JSONObject().put("name","Foreground"))
        polygon(0xff244d4cL,0,1100,300,980,600,1090,1000,960,1290,1010,1600,910,1600,1200,0,1200)
        s.color=0xffe8c384.toInt();s.size=125f;s.pressureSize=0f
        s.stroke(0,floatArrayOf(1140f,270f,1f,0f));s.stroke(2,floatArrayOf())
        var done=false;s.command("info"){done=true};until{done};until{s.bitmap.getPixel(100,100)==0xffe8d9c2.toInt() && s.bitmap.getPixel(1140,270)==0xffe8c384.toInt()}
    }
    @Test fun nativeJniDocumentAndPixelRoundtrip() {
        val handle=NativeBridge.create(64,64);assertNotEquals(0L,handle)
        NativeBridge.stroke(handle,0,floatArrayOf(32f,32f,1f,0f),0,0xffff0000.toInt(),12f,1f,1f,0f,1f,0f)
        NativeBridge.stroke(handle,2,floatArrayOf(),0,0,12f,1f,1f,0f,1f,0f)
        val before=checkNotNull(NativeBridge.save(handle))
        assertEquals(1,JSONObject(NativeBridge.command(handle,"{\"op\":\"info\"}")).getInt("undo"))
        val restored=NativeBridge.create(32,32);assertFalse(JSONObject(NativeBridge.load(restored,before)).has("error"))
        assertArrayEquals(before,NativeBridge.save(restored))
        assertEquals(64*64,NativeBridge.thumbnails(restored,64)?.size)
        NativeBridge.destroy(handle);NativeBridge.destroy(restored)
    }
    @Test fun editorPanelsAndGalleryRenderAndPersist() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.newCanvas(1600,1200)
        val s=checkNotNull(activity.session);val editor=checkNotNull(activity.editor)
        landscape(s);render(editor,"editor-tablet-framework")
        click(editor,"Paint");render(editor,"brush-library")
        click(editor,"Airbrushing");click(editor,"Soft Brush, Airbrushing");render(editor,"brush-library-airbrushing")
        editor.dismissPanel();click(editor,"Color");render(editor,"colors")
        editor.dismissPanel();click(editor,"Layers");until{s.layerThumbnails.size==3};shadowOf(Looper.getMainLooper()).idle();render(editor,"layers")
        editor.dismissPanel();click(editor,"Actions");render(editor,"actions")
        click(editor,"Prefs");render(editor,"preferences")
        editor.dismissPanel();click(editor,"Select");render(editor,"selection")
        var saved=false;activity.saveCurrent{assertTrue(it);saved=true};until{saved}
        assertTrue(activity.store.list().any{it.id==activity.documentId})
        val bytes=activity.store.read(activity.documentId);assertTrue(bytes.size>100)
        activity.showGallery()
        val gallery=activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        fun hasThumbnail(v:View):Boolean = when(v) {
            is android.widget.ImageView -> v.drawable != null
            is ViewGroup -> (0 until v.childCount).any{hasThumbnail(v.getChildAt(it))}
            else -> false
        }
        until{hasThumbnail(gallery)}
        render(gallery,"gallery")
        shutdown(controller)
    }
    private fun named(root:View,description:String):View? {
        if(root.contentDescription?.toString()==description || root is TextView && root.text.toString()==description)return root
        if(root is ViewGroup)for(i in 0 until root.childCount)named(root.getChildAt(i),description)?.let{return it}
        return null
    }
    @Test fun airbrushAndSmudgeEngineRender(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        a.newCanvas(1000,600);val e=checkNotNull(a.editor);val session=checkNotNull(a.session)
        session.stabilization=0f;session.pressureEnabled=false;session.color=0xff174f90.toInt()
        for((row,name) in listOf("Soft Brush","Medium Brush","Medium Hard Brush","Hard Brush").withIndex()){
            session.choose(brushPresets.first{it.name==name});session.size=80f
            session.stroke(0,floatArrayOf(100f,90f+row*130,1f,0f))
            session.stroke(1,floatArrayOf(400f,90f+row*130,1f,0f));session.stroke(2,floatArrayOf())
        }
        session.command("select",JSONObject().put("shape","Rectangle").put("points",JSONArray().put(JSONArray().put(570).put(80)).put(JSONArray().put(720).put(520))))
        session.command("fill",JSONObject().put("color",0xffe04b42L));session.command("deselect")
        session.command("select",JSONObject().put("shape","Rectangle").put("points",JSONArray().put(JSONArray().put(720).put(80)).put(JSONArray().put(870).put(520))))
        session.command("fill",JSONObject().put("color",0xff2466c2L));session.command("deselect")
        until{session.info.optInt("undo")==6};render(e,"brush-engine-before-smudge")
        session.tool="Smudge";session.choose(brushPresets.first{it.name=="Soft Brush"});session.size=100f;session.flow=1f
        session.stroke(0,floatArrayOf(680f,300f,1f,0f));session.stroke(1,floatArrayOf(790f,300f,1f,0f));session.stroke(2,floatArrayOf())
        until{session.info.optInt("undo")==7};render(e,"brush-engine-after-smudge")
        shutdown(controller)
    }
    @Test fun dragColorDropPreviewsCommitsAndCancels(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        a.newCanvas(320,240);val e=checkNotNull(a.editor);val session=checkNotNull(a.session)
        session.command("fill",JSONObject().put("color",0xffffffffL));until{session.info.optInt("undo")==1}
        render(e,"colordrop-before");session.color=0xffed5c40.toInt()
        val color=checkNotNull(named(e,"Color"));val iconAt=IntArray(2);val own=IntArray(2)
        color.getLocationInWindow(iconAt);e.getLocationInWindow(own)
        fun touch(action:Int,x:Float,y:Float){val ev=MotionEvent.obtain(10,20,action,x-iconAt[0]+own[0],y-iconAt[1]+own[1],0);color.dispatchTouchEvent(ev);ev.recycle()}
        fun down(){touch(MotionEvent.ACTION_DOWN,iconAt[0]-own[0]+color.width/2f,iconAt[1]-own[1]+color.height/2f)}
        down();touch(MotionEvent.ACTION_MOVE,640f,400f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(230))
        until{session.bitmap.getPixel(160,120)==0xffed5c40.toInt()}
        assertTrue(session.isStrokeActive);assertEquals(1,session.info.optInt("undo"))
        render(e,"colordrop-threshold")
        touch(MotionEvent.ACTION_MOVE,740f,400f);touch(MotionEvent.ACTION_UP,740f,400f)
        until{session.info.optInt("undo")==2 && !session.isStrokeActive}
        session.color=0xff2040ef.toInt();down();touch(MotionEvent.ACTION_MOVE,640f,400f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(230))
        until{session.bitmap.getPixel(160,120)==0xff2040ef.toInt()}
        touch(MotionEvent.ACTION_CANCEL,640f,400f)
        until{!session.isStrokeActive && session.bitmap.getPixel(160,120)==0xffed5c40.toInt()}
        assertEquals(2,session.info.optInt("undo"));session.command("undo")
        until{session.info.optInt("undo")==1 && session.bitmap.getPixel(160,120)==android.graphics.Color.WHITE}
        render(e,"colordrop-undone");shutdown(controller)
    }
    @Test fun heldShapeIsOneUndoStepAndCanvasResizeStaysStable(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val e=checkNotNull(a.editor);val session=checkNotNull(a.session)
        render(e,"quick-shape-before")
        fun findCanvas(v:View):PaintCanvas?{if(v is PaintCanvas)return v;if(v is ViewGroup)for(i in 0 until v.childCount)findCanvas(v.getChildAt(i))?.let{return it};return null}
        val canvas=checkNotNull(findCanvas(e));session.stabilization=0f
        fun touch(action:Int,x:Float,y:Float){val event=MotionEvent.obtain(0,10,action,x,y,0);canvas.dispatchTouchEvent(event);event.recycle()}
        for(i in 0..80){val t=i*2*Math.PI/80;touch(if(i==0)MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,500+90*kotlin.math.cos(t).toFloat(),350+90*kotlin.math.sin(t).toFloat())}
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        touch(MotionEvent.ACTION_UP,590f,350f);assertTrue(canvas.quickShapeEdit.released);click(e,"Done");until{session.info.optInt("undo")==1};assertEquals("Shape",session.info.getString("undoAction"))
        render(e,"quick-shape-circle");session.command("undo");until{session.info.optInt("undo")==0}
        a.customCanvas();val setup=checkNotNull(a.canvasSetup)
        render(setup,"custom-canvas-wide",1280,800);click(setup,"Colour profile");click(setup,"Dimensions")
        render(setup,"custom-canvas-narrow",800,1280);click(setup,"Canvas properties");click(setup,"Dimensions")
        render(setup,"custom-canvas-wide-again",1280,800)
        assertTrue(checkNotNull(named(setup,"Create custom canvas")).right<=1280)
        shutdown(controller)
    }
    @Test fun shapeRecognitionAndPreferences(){
        fun oval(rx:Float,ry:Float)= (0..100).map{val t=it*2*Math.PI/100;android.graphics.PointF(200+rx*kotlin.math.cos(t).toFloat(),200+ry*kotlin.math.sin(t).toFloat())}
        assertEquals("Circle",QuickShape.fit(oval(90f,90f))?.name)
        assertEquals("Ellipse",QuickShape.fit(oval(100f,55f))?.name)
        val square=mutableListOf<android.graphics.PointF>()
        for(edge in 0..3)for(i in 0..25){val t=i/25f;square.add(when(edge){0->android.graphics.PointF(100+100*t,100f);1->android.graphics.PointF(200f,100+100*t);2->android.graphics.PointF(200-100*t,200f);else->android.graphics.PointF(100f,200-100*t)})}
        assertEquals("Square",QuickShape.fit(square)?.name)
        assertEquals("Line",QuickShape.fit((0..20).map{android.graphics.PointF(it*10f,it*3f)})?.name)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val e=checkNotNull(a.editor)
        render(e,"shape-options-before");click(e,"Actions");click(e,"Prefs");click(e,"Pen pressure")
        assertFalse(checkNotNull(a.session).pressureEnabled)
        click(e,"Canvas");click(e,"Lock canvas position");assertTrue(checkNotNull(a.session).canvasLocked)
        assertFalse(a.getSharedPreferences("studio",0).getBoolean("pressureEnabled",true))
        render(e,"canvas-lock-settings");shutdown(controller)
    }
    @Test fun exteriorDrawingRendersAndUndoesThroughJni(){
        val session=DrawingSession(64,64);session.stabilization=0f;session.size=12f
        session.stroke(0,floatArrayOf(10f,20f,1f,0f));session.stroke(1,floatArrayOf(-20f,20f,1f,0f));session.stroke(2,floatArrayOf())
        until{session.info.optInt("undo")==1 && session.exteriorTiles.isNotEmpty()}
        assertTrue(session.exteriorTiles.any{it.x<0})
        session.command("undo");until{session.info.optInt("undo")==0 && session.exteriorTiles.isEmpty()}
        session.command("redo");until{session.exteriorTiles.isNotEmpty()};session.close()
    }
    @Test fun quickShapeRecognizesUnevenRotatedPolygonsAndEllipses(){
        fun polygon(vertices:List<android.graphics.PointF>):List<android.graphics.PointF>{
            val points=mutableListOf<android.graphics.PointF>()
            for(i in vertices.indices){val a=vertices[i];val b=vertices[(i+1)%vertices.size];val count=if(i==0)180 else 25
                for(j in 0 until count){val t=j.toFloat()/count;val x=a.x+(b.x-a.x)*t;val y=a.y+(b.y-a.y)*t
                    points.add(android.graphics.PointF(300+x*.866f-y*.5f,300+x*.5f+y*.866f))}}
            points.add(points.first());return points
        }
        assertEquals("Triangle",QuickShape.fit(polygon(listOf(android.graphics.PointF(0f,0f),android.graphics.PointF(160f,0f),android.graphics.PointF(80f,140f))))?.name)
        assertEquals("Rectangle",QuickShape.fit(polygon(listOf(android.graphics.PointF(0f,0f),android.graphics.PointF(180f,0f),android.graphics.PointF(180f,90f),android.graphics.PointF(0f,90f))))?.name)
        for(ratio in listOf(.35f,.65f,1f)){
            val points=(0..200).map{val t=it*2*Math.PI/200;val x=100*kotlin.math.cos(t).toFloat();val y=100*ratio*kotlin.math.sin(t).toFloat();android.graphics.PointF(300+x*.866f-y*.5f,300+x*.5f+y*.866f)}
            assertEquals(if(ratio==1f)"Circle"else"Ellipse",QuickShape.fit(points)?.name)
        }
    }
    @Test fun panelSectionsKeepTheirContainer(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val e=checkNotNull(a.editor)
        render(e,"panel-before");click(e,"Paint");render(e,"brush-before-switch")
        val panel=e.findViewWithTag<View>("tool-panel")
        click(e,"Airbrushing");assertSame(panel,e.findViewWithTag<View>("tool-panel"));render(e,"brush-after-switch")
        e.dismissPanel();click(e,"Color");click(e,"Classic");render(e,"classic-square-palette")
        shutdown(controller)
    }
    @Test fun canvasMenuAndCustomDimensionsCreateCorrectDocument() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.showGallery();val gallery=activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        render(gallery,"gallery-empty");activity.createDialog()
        val menu=checkNotNull(activity.canvasPopup).contentView
        render(menu,"new-canvas-menu",400,650)
        click(menu,"Custom Canvas")
        val setup=checkNotNull(activity.canvasSetup);render(setup,"custom-canvas",1280,800)
        click(setup,"Canvas DPI");for(key in listOf("1","5","0"))click(setup,"Key $key")
        click(setup,"Millimetres");click(setup,"Canvas Width");click(setup,"Key 5");click(setup,"Key 0")
        click(setup,"Canvas Height");click(setup,"Key 7");click(setup,"Key 0")
        render(setup,"custom-canvas-units",1280,800)
        click(setup,"Canvas properties");render(setup,"custom-canvas-properties",1280,800)
        click(setup,"Create custom canvas")
        val session=checkNotNull(activity.session);until{session.info.optInt("dpi")==150}
        assertEquals(295,session.canvasWidth);assertEquals(413,session.canvasHeight)
        assertTrue(CanvasPresets.custom(activity).any{it.width==295&&it.height==413&&it.dpi==150})
        var saved=false;activity.saveCurrent{assertTrue(it);saved=true};until{saved}
        val doc=JSONObject(String(activity.store.read(activity.documentId)));assertEquals(150,doc.getInt("dpi"))
        shutdown(controller)
    }
    @Test fun layerBlendExpandsInlineAndSwipeActionsWork() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.newCanvas(1600,1200);val session=checkNotNull(activity.session);val editor=checkNotNull(activity.editor)
        landscape(session);render(editor,"workspace-grid");click(editor,"Layers");until{session.layerThumbnails.size==3}
        render(editor,"layers");click(editor,"Blend options for Foreground");render(editor,"layer-blend-dropdown")
        click(editor,"Blend Multiply for Foreground");until{session.info.getJSONArray("layers").getJSONObject(2).getString("blend")=="Multiply"}
        click(editor,"Blend options for Foreground");render(editor,"layers-closed-dropdown")
        fun swipe(name:String){
            val row=checkNotNull(named(editor,"Layer row $name")) as SwipeLayerRow
            fun touch(action:Int,x:Float){val e=MotionEvent.obtain(10,20,action,x,30f,0);row.dispatchTouchEvent(e);e.recycle()}
            touch(MotionEvent.ACTION_DOWN,row.width-10f);touch(MotionEvent.ACTION_MOVE,row.width-210f);touch(MotionEvent.ACTION_UP,row.width-210f)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));assertTrue(row.revealed)
        }
        swipe("Foreground");render(editor,"layer-swipe-actions")
        click(editor,"Lock Foreground");until{session.info.getJSONArray("layers").getJSONObject(2).optBoolean("locked")}
        until{named(editor,"Unlock Foreground")!=null};render(editor,"layer-locked")
        swipe("Foreground");click(editor,"Unlock Foreground");until{!session.info.getJSONArray("layers").getJSONObject(2).optBoolean("locked")}
        until{named(editor,"Lock Foreground")!=null};render(editor,"layers-unlocked")
        swipe("Foreground");click(editor,"Duplicate Foreground");until{session.info.getJSONArray("layers").length()==4&&named(editor,"Layer row Foreground copy")!=null}
        render(editor,"layers-duplicated");swipe("Foreground copy");click(editor,"Delete Foreground copy")
        until{session.info.getJSONArray("layers").length()==3}
        shutdown(controller)
    }
    @Test fun groupedPreferencesAndCursorGesturesPersist() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        val editor=checkNotNull(activity.editor);val session=checkNotNull(activity.session)
        landscape(session);render(editor,"workspace-before-prefs");click(editor,"Actions");click(editor,"Prefs");render(editor,"preferences")
        click(editor,"Advanced cursor settings");render(editor,"advanced-cursor-settings");click(editor,"Light")
        assertEquals("Light",session.cursorStyle)
        editor.dismissPanel();click(editor,"Actions");click(editor,"Gesture controls");render(editor,"gesture-settings")
        click(editor,"Two-finger tap Undo");assertFalse(session.twoFingerUndo)
        assertFalse(activity.getSharedPreferences("studio",0).getBoolean("twoFingerUndo",true))
        shutdown(controller)
    }

    @Test fun repeatedStrokesAndMidStrokeSaveKeepEveryEndpoint() {
        val session=DrawingSession(1600,1200)
        val store=DocumentStore(RuntimeEnvironment.getApplication())
        session.color=0xffd02020.toInt();session.size=8f;session.pressureSize=0f;session.stabilization=0f
        var frames=0;session.onFrame={frames++}
        session.requestRender();until{frames>0}
        val started=System.nanoTime();var saved=false
        val id=store.newId()
        for(line in 0 until 60){
            val y=20f+line*18f
            session.stroke(0,floatArrayOf(20f,y,1f,0f))
            for(point in 1..64){
                session.stroke(1,floatArrayOf(20f+point*22f,y,1f,0f))
                if(line==30&&point==32)session.persist(store,id,"Mid-stroke"){assertNull(it);saved=true}
            }
            session.stroke(2,floatArrayOf())
        }
        until{session.info.getInt("undo")==60 && session.bitmap.getPixel(1420,1082)==0xffd02020.toInt() && saved}
        for(line in 0 until 60)assertEquals("Lost endpoint for stroke $line",0xffd02020.toInt(),session.bitmap.getPixel(1420,20+line*18))
        assertEquals(1600,store.list().first{it.id==id}.width)
        assertEquals(1200,store.list().first{it.id==id}.height)
        println("60 strokes / 3840 move samples, including mid-stroke disk save: ${(System.nanoTime()-started)/1_000_000} ms (desktop host)")
        session.close();until{session.fullyClosed}
    }

    @Test fun longStrokeDefersIdleAutosaveUntilPenIsLifted() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.newCanvas(320,240);val editor=checkNotNull(activity.editor);val session=checkNotNull(activity.session)
        render(editor,"autosave-long-stroke")
        val view=editor.canvas
        fun touch(action:Int,x:Float){val e=MotionEvent.obtain(100,110,action,x,350f,0);view.onTouchEvent(e);e.recycle()}
        touch(MotionEvent.ACTION_DOWN,500f);touch(MotionEvent.ACTION_MOVE,550f)
        assertTrue(session.isStrokeActive)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2500))
        assertFalse(activity.store.list().any{it.id==activity.documentId})
        touch(MotionEvent.ACTION_MOVE,700f);touch(MotionEvent.ACTION_UP,750f)
        until{session.info.getInt("undo")==1}
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
        until{activity.store.list().any{it.id==activity.documentId}}
        assertFalse(session.isStrokeActive)
        shutdown(controller)
    }

    @Test fun sliderShowsLivePreviewAndCancels() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val editor=checkNotNull(controller.get().editor);controller.get().session?.choose(brushPresets.first{it.name=="Soft Brush"});render(editor,"slider-before")
        fun find(v:View):View? {if(v.contentDescription=="Brush size")return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        val slider=checkNotNull(find(editor))
        val down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,12f,slider.height*.7f,0);slider.dispatchTouchEvent(down);down.recycle()
        render(editor,"brush-size-preview")
        assertTrue((0 until editor.childCount).map{editor.getChildAt(it)}.any{it is BrushHud && it.visibility==View.VISIBLE})
        val cancel=MotionEvent.obtain(0,10,MotionEvent.ACTION_CANCEL,12f,slider.height*.7f,0);slider.dispatchTouchEvent(cancel);cancel.recycle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000))
        assertTrue((0 until editor.childCount).map{editor.getChildAt(it)}.filterIsInstance<BrushHud>().all{it.visibility!=View.VISIBLE})
        shutdown(controller)
    }
    @Test fun rapidPanelSwitchingCleansUpOutgoingViews() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        val editor=checkNotNull(controller.get().editor)
        click(editor,"Color");editor.dismissPanel();click(editor,"Actions");click(editor,"Color")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        val panels=(0 until editor.childCount).map{editor.getChildAt(it)}.filter{it.tag=="tool-panel"}
        assertEquals(1,panels.size);assertTrue(panels.single().isEnabled)
        assertEquals(1f,panels.single().alpha,.01f)
        editor.dismissPanel()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertFalse((0 until editor.childCount).any{editor.getChildAt(it).tag=="tool-panel"})
        shutdown(controller)
    }
    @Test fun fingerStrokeAndCancelledPalmAreUndoSafe() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.newCanvas(320,240);val editor=checkNotNull(activity.editor);val s=checkNotNull(activity.session)
        render(editor,"input-test")
        val view=editor.canvas
        fun touch(action:Int,x:Float,y:Float){val e=MotionEvent.obtain(100,110,action,x,y,0);view.onTouchEvent(e);e.recycle()}
        touch(MotionEvent.ACTION_DOWN,600f,350f);touch(MotionEvent.ACTION_MOVE,640f,350f);touch(MotionEvent.ACTION_UP,660f,350f)
        until{s.info.getInt("undo")==1}
        touch(MotionEvent.ACTION_DOWN,500f,300f);touch(MotionEvent.ACTION_MOVE,520f,300f);touch(MotionEvent.ACTION_CANCEL,520f,300f)
        var done=false;s.command("info"){done=true};until{done};assertEquals(1,s.info.getInt("undo"))
        shutdown(controller)
    }
    @Test fun phoneLayoutAndImportRejectInvalidDocument() {
        RuntimeEnvironment.setQualifiers("w400dp-h820dp-mdpi")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.newCanvas(400,400);val s=checkNotNull(activity.session)
        until{s.bitmap.getPixel(0,0) ushr 24==255}
        render(checkNotNull(activity.editor),"editor-phone",400,820)
        click(checkNotNull(activity.editor),"Color");render(checkNotNull(activity.editor),"colors-phone",400,820)
        activity.editor?.dismissPanel();click(checkNotNull(activity.editor),"Select")
        render(checkNotNull(activity.editor),"selection-phone",400,820)
        val handle=NativeBridge.create(32,32);assertTrue(JSONObject(NativeBridge.load(handle,byteArrayOf(1,2,3))).has("error"));NativeBridge.destroy(handle)
        shutdown(controller)
    }
    @Test fun rendererPreferenceAndDocumentRoundTrip(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get()
        activity.newCanvas(96,64);val session=checkNotNull(activity.session);val editor=checkNotNull(activity.editor)
        var configured=false;session.setRenderer("cpu"){configured=true};until{configured}
        assertEquals("cpu",session.info.getString("renderer"));activity.savePreferences()
        assertEquals("cpu",activity.getSharedPreferences("studio",0).getString("rendererMode",null))
        session.stroke(0,floatArrayOf(10f,30f,1f,0f));session.stroke(2,floatArrayOf(80f,30f,1f,0f));until{session.info.getInt("undo")==1}
        var saved:ByteArray?=null;session.snapshot{bytes,image->saved=bytes;image?.recycle()};until{saved!=null}
        var loaded=false;session.load(checkNotNull(saved)){loaded=it};until{loaded}
        assertEquals("cpu",session.info.getString("renderer"));assertEquals(96,session.canvasWidth)
        render(editor,"renderer-preferences");click(editor,"Actions");click(editor,"Prefs");click(editor,"Drawing renderer")
        val dialog=checkNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(dialog.isShowing);dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shutdown(controller)
    }

    private fun input(root:View,tool:Int,action:Int,x:Float,y:Float,hover:Boolean=false){
        val props=MotionEvent.PointerProperties().apply{id=0;toolType=tool}
        val coords=MotionEvent.PointerCoords().apply{this.x=x;this.y=y;pressure=1f;size=.05f}
        val time=android.os.SystemClock.uptimeMillis()
        val e=MotionEvent.obtain(time,time,action,1,arrayOf(props),arrayOf(coords),0,0,1f,1f,0,0,
            if(tool==MotionEvent.TOOL_TYPE_STYLUS)android.view.InputDevice.SOURCE_STYLUS else android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
        if(hover)root.dispatchGenericMotionEvent(e) else root.dispatchTouchEvent(e);e.recycle()
    }
    @Test fun penOnlyAndPalmProtectionPreserveDrawingAndDock(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        a.newCanvas(320,240);val e=checkNotNull(a.editor);val s=checkNotNull(a.session)
        until{s.bitmap.getPixel(0,0) ushr 24==255}
        render(e,"feedback-docked-editor");val dock=checkNotNull(named(e,"Docked brush controls")) as ViewGroup
        assertEquals(e.height,dock.bottom);assertEquals(dock.width,e.canvas.left);assertEquals(44,dock.top)
        val save=checkNotNull(named(e,"Save artwork")) as TextView;assertEquals(1,save.lineCount)
        click(e,"Save artwork");val saveDialog=checkNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(saveDialog.isShowing);assertNotNull(named(saveDialog.window!!.decorView,"Export PNG to folder"))
        saveDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        s.penOnly=true;a.savePreferences();assertTrue(a.getSharedPreferences("studio",0).getBoolean("penOnly",false))
        fun touch(tool:Int,action:Int,x:Float,y:Float)=input(e,tool,action,x,y)
        touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_DOWN,600f,350f)
        touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_MOVE,680f,350f)
        touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_UP,700f,350f)
        var read=false;s.command("info"){read=true};until{read};assertEquals(0,s.info.getInt("undo"))
        val slider=checkNotNull(named(e,"Brush size"));val x=dock.left+slider.left+12f;val y=dock.top+slider.top+slider.height*.25f
        val original=s.size
        touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_UP,x,y)
        assertTrue(s.size>original);s.size=original
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))
        touch(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_UP,x,y)
        assertTrue(s.size>original);s.size=12f
        touch(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_DOWN,600f,350f)
        touch(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_MOVE,680f,350f)
        touch(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_UP,700f,350f)
        until{s.info.getInt("undo")==1}
        s.penOnly=false
        val before=s.size
        touch(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_DOWN,600f,400f)
        touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_UP,x,y)
        assertEquals(before,s.size,.001f)
        touch(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_UP,600f,400f)
        input(e,MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.ACTION_HOVER_MOVE,300f,300f,true)
        touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_DOWN,x,y);touch(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.ACTION_UP,x,y)
        assertTrue(s.size>before)
        assertTrue(checkNotNull(named(e,"Undo")).top<dock.top)
        e.dismissPanel();click(e,"Actions");click(e,"Prefs");click(e,"Right-hand interface");render(e,"feedback-right-dock")
        assertEquals(e.width,dock.right);assertEquals(0,e.canvas.left);assertEquals(e.width-dock.width,e.canvas.right)
        shutdown(controller)
    }
    @Test fun stabilizerKeepsFullValueAcrossPanelsAndNewCanvases(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val e=checkNotNull(a.editor);val s=checkNotNull(a.session)
        render(e,"feedback-before-settings");click(e,"Actions");click(e,"Prefs");click(e,"Pressure and Smoothing")
        val seek=checkNotNull(named(e,"Stabilization")) as BrushRange
        repeat(100){seek.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT))}
        assertEquals(.95f,s.stabilization,.001f)
        click(e,"Done");assertNotNull(named(e,"Prefs"));e.dismissPanel();click(e,"Actions");click(e,"Prefs");click(e,"Pressure and Smoothing")
        assertEquals(1f,(checkNotNull(named(e,"Stabilization")) as BrushRange).value,.001f)
        render(e,"feedback-stabilizer-100");e.dismissPanel();a.newCanvas(320,240)
        assertEquals(.95f,checkNotNull(a.session).stabilization,.001f)
        shutdown(controller)
    }

    @Test fun eraserLibrarySharesPresetsAndKeepsDrawingBrushSeparate(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(160,120)
        val e=checkNotNull(a.editor);val s=checkNotNull(a.session);s.choose(brushPresets.first{it.name=="Technical Pen"});s.size=9f
        render(e,"eraser-library-before");click(e,"Erase");assertNotNull(named(e,"Eraser Library"))
        click(e,"Airbrushing");click(e,"Soft Brush, Airbrushing, eraser");assertEquals("Soft Brush",s.preset.name);s.size=70f
        render(e,"eraser-library-soft");e.dismissPanel();click(e,"Paint");assertEquals("Technical Pen",s.preset.name);assertEquals(9f,s.size,0f)
        click(e,"Erase");assertEquals("Soft Brush",s.preset.name);assertEquals(70f,s.size,0f)
        shutdown(controller)
    }

    @Test fun rotationRelayoutEraserAndImportedLayerTransform(){
        RuntimeEnvironment.setQualifiers("w400dp-h820dp-mdpi")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(160,120)
        val e=checkNotNull(a.editor);val s=checkNotNull(a.session)
        render(e,"feedback2-raster-portrait",400,820)
        assertEquals(88,e.canvas.top);val history=checkNotNull(named(e,"History and save"));assertTrue(history.top>700)
        RuntimeEnvironment.setQualifiers("w1280dp-h800dp-land-mdpi");controller.configurationChange();render(e,"feedback2-raster-rotated",1280,800);assertEquals(44,e.canvas.top);assertEquals(0,checkNotNull(named(e,"History and save")).top)
        click(e,"Erase");click(e,"Add");click(e,"Brush Studio");shadowOf(Looper.getMainLooper()).idle();click(e,"Edge hardness");assertNotNull(named(e,"Hardness"));click(e,"Soft");assertEquals(0f,s.eraserHardness,0f)
        render(e,"feedback2-eraser");e.dismissPanel()
        val image=Bitmap.createBitmap(30,20,Bitmap.Config.ARGB_8888).apply{eraseColor(0xffef8040.toInt())};var imported=false
        s.import(image){e.startLayerTransform();imported=true};image.recycle();until{imported};assertEquals("Transform",s.tool)
        val active=s.info.getInt("active");assertTrue(active>0)
        var before:ByteArray?=null;s.documentSnapshot{before=it};until{before!=null}
        var moved=false;s.command("transform",JSONObject().put("dx",20).put("dy",10)){moved=true};until{moved}
        var after:ByteArray?=null;s.documentSnapshot{after=it};until{after!=null};assertFalse(before!!.contentEquals(after!!))
        render(e,"feedback2-import-transform");shutdown(controller)
    }

    @Test fun iconTooltipsAndActiveColorPreviewStayLive(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val e=checkNotNull(a.editor);render(e,"preview3-before-colors")
        fun icons(v:View){if(v is ToolIcon)assertFalse(v.tooltipText.isNullOrBlank());if(v is ViewGroup)for(i in 0 until v.childCount)icons(v.getChildAt(i))};icons(e)
        click(e,"Color")
        fun disc(v:View):ColorDisc?{if(v is ColorDisc)return v;if(v is ViewGroup)for(i in 0 until v.childCount)disc(v.getChildAt(i))?.let{return it};return null}
        val picker=checkNotNull(disc(e));picker.changed(0xffc12b89.toInt());val header=checkNotNull(named(e,"Active color preview"))
        assertEquals(0xffc12b89.toInt(),(header.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(0xffc12b89.toInt(),(checkNotNull(named(e,"Color")) as ToolIcon).swatch)
        picker.changed(0xff3685a1.toInt());assertEquals(0xff3685a1.toInt(),(header.background as android.graphics.drawable.ColorDrawable).color)
        render(e,"preview3-live-color-header");shutdown(controller)
    }

    @Test fun nativeColorDropClosesAntialiasedPixelSeam(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(64,64);val s=checkNotNull(a.session)
        val image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        for(y in 14..49)for(x in 14..49){val inner=x in 16..47 && y in 16..47;val fringe=x in 15..48 && y in 15..48;image.setPixel(x,y,if(inner)0 else if(fringe)0x80000000.toInt()else android.graphics.Color.BLACK)}
        var imported=false;s.import(image){imported=true};image.recycle();until{imported};var filled=false
        s.command("flood",JSONObject().put("x",30).put("y",30).put("threshold",.1).put("color",0xff20c060L)){filled=true};until{filled}
        var snapshot:Bitmap?=null;s.imageSnapshot{snapshot=it};until{snapshot!=null}
        val output=checkNotNull(snapshot);assertEquals(0xff20c060.toInt(),output.getPixel(16,30));assertEquals(android.graphics.Color.BLACK,output.getPixel(14,30));assertEquals(255,output.getPixel(15,30) ushr 24)
        assertTrue(android.graphics.Color.green(output.getPixel(15,30)) in 80..120)
        File(System.getProperty("inkstone.artifacts"),"preview3-colordrop-fringe.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)};output.recycle()
        render(checkNotNull(a.editor),"preview3-colordrop-editor");shutdown(controller)
    }

    private fun photoTransform(controller:org.robolectric.android.controller.ActivityController<MainActivity>):EditorView{
        val a=controller.get();a.newCanvas(320,240);val e=checkNotNull(a.editor);val s=checkNotNull(a.session)
        val photo=Bitmap.createBitmap(80,100,Bitmap.Config.ARGB_8888)
        for(y in 0 until 100)for(x in 0 until 80)photo.setPixel(x,y,if(x<40)0xff2586d2.toInt()else 0xffe65c41.toInt())
        var loaded=false;s.import(photo){e.startLayerTransform();loaded=true};photo.recycle();until{loaded};render(e,"preview4-transform-before");until{e.canvas.rasterTransform.ready};return e
    }
    private fun transformTouch(c:PaintCanvas,action:Int,x:Float,y:Float){val p=c.transformViewPoint(x,y);val now=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,action,p.x,p.y,0);c.onTouchEvent(e);e.recycle()}
    private fun transformDrag(c:PaintCanvas,x:Float,y:Float,xx:Float,yy:Float,cancel:Boolean=false){transformTouch(c,MotionEvent.ACTION_DOWN,x,y);transformTouch(c,MotionEvent.ACTION_MOVE,xx,yy);transformTouch(c,if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,xx,yy)}
    @Test fun photoCornerHandlesHaveLiveBoundsAndOneCommit(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val e=photoTransform(controller);val s=e.session;val c=e.canvas;val preview=c.rasterTransform
        assertEquals(80f,preview.bounds!!.width(),0f);assertEquals(100f,preview.bounds!!.height(),0f);val undo=s.info.getInt("undo")
        transformDrag(c,120f,70f,160f,120f);assertEquals(40f,preview.bounds!!.width(),.01f);assertEquals(50f,preview.bounds!!.height(),.01f)
        assertEquals(160f,preview.bounds!!.left,.01f);assertEquals(undo,s.info.getInt("undo"));render(e,"preview4-corner-resize-live")
        click(e,"Larger");assertEquals(44f,preview.bounds!!.width(),.02f);click(e,"Smaller");assertEquals(39.6f,preview.bounds!!.width(),.02f);assertEquals(undo,s.info.getInt("undo"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4));assertEquals(undo,s.info.getInt("undo"));click(e,"Cancel");assertEquals("Paint",s.tool);assertEquals(undo,s.info.getInt("undo"));e.startLayerTransform();until{preview.ready};assertEquals(80f,preview.bounds!!.width(),0f)
        transformDrag(c,120f,70f,160f,120f);click(e,"Done");until{s.info.getInt("undo")==undo+1};var result:Bitmap?=null;s.imageSnapshot{result=it};until{result!=null}
        assertEquals(android.graphics.Color.WHITE,result!!.getPixel(120,80));assertEquals(0xff2586d2.toInt(),result!!.getPixel(165,130));result!!.recycle()
        render(e,"preview4-transform-applied");s.command("undo");until{s.info.getInt("undo")==undo};e.startLayerTransform();until{preview.ready};assertEquals(80f,preview.bounds!!.width(),0f)
        shutdown(controller)
    }
    @Test fun twoFingerObjectRotationKeepsCanvasStillAndCanCancel(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val e=photoTransform(controller);val c=e.canvas;val preview=c.rasterTransform;val viewport=c.transformViewPoint(0f,0f);val undo=e.session.info.getInt("undo")
        fun multi(action:Int,ax:Float,ay:Float,bx:Float,by:Float){
            val props=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val points=arrayOf(c.transformViewPoint(ax,ay),c.transformViewPoint(bx,by));val coords=Array(2){i->MotionEvent.PointerCoords().apply{x=points[i].x;y=points[i].y;pressure=1f;size=.1f}}
            val time=android.os.SystemClock.uptimeMillis();val event=MotionEvent.obtain(time,time,action,2,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);c.onTouchEvent(event);event.recycle()
        }
        transformTouch(c,MotionEvent.ACTION_DOWN,140f,120f);multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),140f,120f,180f,120f)
        val dx=(kotlin.math.cos(Math.PI/6)*26).toFloat();val dy=13f
        multi(MotionEvent.ACTION_MOVE,165f-dx,130f-dy,165f+dx,130f+dy)
        val matrix=FloatArray(9);preview.currentMatrix().getValues(matrix);assertEquals((1.3*kotlin.math.cos(Math.PI/6)).toFloat(),matrix[0],.01f);assertEquals(.65f,matrix[3],.01f)
        assertEquals(viewport.x,c.transformViewPoint(0f,0f).x,0f);assertEquals(viewport.y,c.transformViewPoint(0f,0f).y,0f);assertEquals(undo,e.session.info.getInt("undo"));render(e,"preview4-two-finger-rotation")
        multi(MotionEvent.ACTION_CANCEL,165f-dx,130f-dy,165f+dx,130f+dy);assertTrue(preview.currentMatrix().isIdentity)
        transformTouch(c,MotionEvent.ACTION_DOWN,160f,120f);transformTouch(c,MotionEvent.ACTION_MOVE,180f,140f);transformTouch(c,MotionEvent.ACTION_UP,180f,140f)
        assertEquals(140f,preview.bounds!!.left,.01f);click(e,"Done");until{e.session.info.getInt("undo")==undo+1};shutdown(controller)
    }
    @Test fun transformPreviewCommitsBeforeSavingAndLayerChange(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val e=photoTransform(controller);val s=e.session;val preview=e.canvas.rasterTransform;val undo=s.info.getInt("undo")
        click(e,"Smaller");click(e,"Smaller");assertEquals(64.8f,preview.bounds!!.width(),.02f)
        var saved=false;a.saveCurrent{assertTrue(it);saved=true};until{saved};assertEquals(undo+1,s.info.getInt("undo"))
        val file=a.store.read(a.documentId);val root=JSONObject(file.toString(Charsets.UTF_8));assertEquals(320,root.getInt("width"))
        until{preview.ready};click(e,"Larger");var added=false;s.command("add"){added=true};until{added};assertEquals(undo+3,s.info.getInt("undo"));assertEquals(2,s.info.getInt("active"));shutdown(controller)
    }

    @Test fun transformPreviewKeepsLayerOrderClippingAndOpacity(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(160,120);val s=checkNotNull(a.session);val e=checkNotNull(a.editor)
        var ready=false;s.command("fill",JSONObject().put("color",0xff4b9955L)){ready=true};until{ready}
        var imported=false;val photo=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff397ed1.toInt())};s.import(photo){imported=true};photo.recycle();until{imported}
        var opacity=false;s.command("layer",JSONObject().put("opacity",.6)){opacity=true};until{opacity}
        imported=false;val clip=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).apply{eraseColor(0xffc8614a.toInt())};s.import(clip){imported=true};clip.recycle();until{imported}
        var layer=false;s.command("layer",JSONObject().put("clipping",true).put("blend","Multiply").put("opacity",.5)){layer=true};until{layer}
        var active=false;s.command("active",JSONObject().put("index",1)){active=true};until{active}
        var baseline:Bitmap?=null;s.imageSnapshot{baseline=it};until{baseline!=null};s.tool="Transform";e.refreshTools();render(e,"preview4-layered-transform");until{e.canvas.rasterTransform.ready}
        val preview=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888);assertTrue(e.canvas.rasterTransform.draw(Canvas(preview)))
        fun near(expected:Int,actual:Int){for(shift in listOf(0,8,16,24))assertTrue(kotlin.math.abs((expected ushr shift and 255)-(actual ushr shift and 255))<=2)}
        near(baseline!!.getPixel(60,50),preview.getPixel(60,50));near(baseline!!.getPixel(10,10),preview.getPixel(10,10));baseline!!.recycle()
        e.canvas.rasterTransform.scale(.5f);val scaled=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888);e.canvas.rasterTransform.draw(Canvas(scaled));click(e,"Done")
        var result:Bitmap?=null;s.imageSnapshot{result=it};until{result!=null};near(result!!.getPixel(80,60),scaled.getPixel(80,60));near(result!!.getPixel(45,35),scaled.getPixel(45,35))
        preview.recycle();scaled.recycle();result!!.recycle();shutdown(controller)
    }

    @Test fun layeredPsdJniAndCancelledExportCleanUp(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        a.newCanvas(96,64);val s=checkNotNull(a.session);var ready=false;s.setRenderer("cpu"){ready=true};until{ready}
        s.stroke(0,floatArrayOf(20f,20f,1f,0f));s.stroke(2,floatArrayOf(70f,30f,1f,0f));until{s.info.getInt("undo")==1}
        s.command("add");var named=false;s.command("layer",JSONObject().put("name","Separate layer")){named=true};until{named}
        val file=File(a.cacheDir,"layer-test.psd");var exported=false
        s.writePsd(file){assertNull(it);exported=true};until{exported}
        assertArrayEquals("8BPS".toByteArray(),file.readBytes().copyOfRange(0,4));assertTrue(file.length()>1000);file.delete()
        a.export("PSD")
        var launched:org.robolectric.shadows.ShadowActivity.IntentForResult?=null
        until{launched=shadowOf(a).nextStartedActivityForResult;launched!=null}
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT,launched!!.intent.action)
        assertEquals("image/vnd.adobe.photoshop",launched!!.intent.type)
        assertTrue(a.cacheDir.listFiles()!!.any{it.name.startsWith("inkstone-export-")})
        @Suppress("DEPRECATION")
        shadowOf(a).receiveResult(launched!!.intent,android.app.Activity.RESULT_CANCELED,null)
        assertFalse(a.cacheDir.listFiles()!!.any{it.name.startsWith("inkstone-export-")})
        shutdown(controller)
    }
    private class GalleryProvider(private val file:File):android.content.ContentProvider(){
        var inserted:android.content.ContentValues?=null;var published=false;var deleted=false;var fail=false
        override fun onCreate()=true
        override fun insert(uri:android.net.Uri,values:android.content.ContentValues?):android.net.Uri{inserted=android.content.ContentValues(values);return android.net.Uri.parse("content://media/external/images/media/42")}
        override fun update(uri:android.net.Uri,values:android.content.ContentValues?,selection:String?,args:Array<out String>?):Int{published=values?.getAsInteger("is_pending")==0;return 1}
        override fun delete(uri:android.net.Uri,selection:String?,args:Array<out String>?):Int{deleted=true;file.delete();return 1}
        override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):android.database.Cursor?=null
        override fun getType(uri:android.net.Uri)="image/png"
        override fun openFile(uri:android.net.Uri,mode:String):android.os.ParcelFileDescriptor{
            if(fail)throw java.io.FileNotFoundException("Full disk")
            return android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }
    @Test fun galleryPngJpegAndFailedWriteCleanup(){
        val context=RuntimeEnvironment.getApplication();val file=File(context.cacheDir,"gallery-output")
        val provider=GalleryProvider(file);provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="media"})
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider)
        val image=android.graphics.Bitmap.createBitmap(24,24,android.graphics.Bitmap.Config.ARGB_8888)
        image.setPixel(12,12,0x80ff0000.toInt())
        ImageExports.saveToGallery(context,image,"PNG","Test")
        assertEquals(1,provider.inserted!!.getAsInteger("is_pending"));assertEquals("Pictures/Inkstone",provider.inserted!!.getAsString("relative_path"));assertTrue(provider.published)
        val png=android.graphics.BitmapFactory.decodeFile(file.absolutePath);assertEquals(0,png.getPixel(0,0) ushr 24);assertEquals(128,png.getPixel(12,12) ushr 24);png.recycle()
        ImageExports.saveToGallery(context,image,"JPEG","Test")
        assertEquals("image/jpeg",provider.inserted!!.getAsString("mime_type"))
        val jpg=android.graphics.BitmapFactory.decodeFile(file.absolutePath);assertEquals(255,jpg.getPixel(0,0) ushr 24);assertTrue(android.graphics.Color.red(jpg.getPixel(0,0))>245);jpg.recycle()
        provider.fail=true
        assertTrue(runCatching{ImageExports.saveToGallery(context,image,"PNG","Failure")}.isFailure);assertTrue(provider.deleted);assertFalse(file.exists());image.recycle()
    }

}
