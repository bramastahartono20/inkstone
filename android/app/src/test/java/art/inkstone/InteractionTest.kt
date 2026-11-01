package art.inkstone

import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InteractionTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+25_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Interaction timed out")}
    private fun named(v:View,s:String):View?{if(v.contentDescription==s || v is TextView && v.text.toString()==s)return v;if(v is ViewGroup)for(i in 0 until v.childCount)named(v.getChildAt(i),s)?.let{return it};return null}
    private fun click(v:View,s:String){assertTrue(checkNotNull(named(v,s)){"Missing $s"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun render(v:View,name:String){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));v.rootView.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.rootView.layout(0,0,1280,800);v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,800);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));val image=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888);v.draw(Canvas(image));File(System.getProperty("inkstone.artifacts"),"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    private fun done(c:org.robolectric.android.controller.ActivityController<MainActivity>){val s=c.get().session;c.pause().stop().destroy();until{s==null || s.fullyClosed};until{c.get().io.isTerminated}}
    private fun input(v:View,action:Int,points:List<PointF>,tool:Int=MotionEvent.TOOL_TYPE_FINGER){
        val props=Array(points.size){i->MotionEvent.PointerProperties().apply{id=i;toolType=tool}}
        val coords=Array(points.size){i->MotionEvent.PointerCoords().apply{x=points[i].x;y=points[i].y;pressure=1f;size=.05f}}
        val time=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(time,time,action,points.size,props,coords,0,0,1f,1f,0,0,if(tool==MotionEvent.TOOL_TYPE_STYLUS)InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,0);v.dispatchTouchEvent(e);e.recycle()
    }
    private fun single(v:View,action:Int,p:PointF,tool:Int=MotionEvent.TOOL_TYPE_FINGER)=input(v,action,listOf(p),tool)
    private fun navigate(v:View){val x=v.width/2f;val y=v.height/2f
        val a=PointF(x-80,y);val b=PointF(x+80,y);val c=PointF(x-60,y-120);val d=PointF(x+100,y+120)
        single(v,MotionEvent.ACTION_DOWN,a);input(v,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(a,b));input(v,MotionEvent.ACTION_MOVE,listOf(c,d));input(v,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(c,d));single(v,MotionEvent.ACTION_UP,c)
    }
    private fun blockIo(a:MainActivity):CountDownLatch{val started=CountDownLatch(1);val release=CountDownLatch(1);a.io.execute{started.countDown();release.await(20,TimeUnit.SECONDS)};assertTrue(started.await(5,TimeUnit.SECONDS));return release}
    @Test fun drawingThroughEditorStillWorksAfterAddingManyLayers(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        a.newCanvas(2048,1536);until{a.editor?.session?.canvasWidth==2048 && !a.navigationBusy}
        val e=a.editor!!;val s=e.session;s.penOnly=true;s.color=Color.RED;s.size=24f
        render(e,"preview4-before-drawing")
        fun draw(x:Float){
            val first=e.canvas.transformViewPoint(x,500f).apply{offset(e.canvas.left.toFloat(),e.canvas.top.toFloat())}
            val last=e.canvas.transformViewPoint(x+250,600f).apply{offset(e.canvas.left.toFloat(),e.canvas.top.toFloat())}
            single(e,MotionEvent.ACTION_DOWN,first,MotionEvent.TOOL_TYPE_STYLUS)
            single(e,MotionEvent.ACTION_MOVE,last,MotionEvent.TOOL_TYPE_STYLUS)
            single(e,MotionEvent.ACTION_UP,last,MotionEvent.TOOL_TYPE_STYLUS)
            var result:JSONObject?=null;s.command("pick",JSONObject().put("x",x.toInt()).put("y",500)){result=it};until{result!=null}
            assertEquals("Stylus must reach the canvas through all overlays",Color.RED,result!!.getLong("color").toInt())
        }
        draw(400f)
        repeat(70){s.command("add")};until{s.info.getJSONArray("layers").length()==71}
        draw(900f);render(e,"preview4-many-layers-drawing");done(c)
    }
    @Test fun brushSmudgeAndEraserLibrariesOpenOnTheFirstTap(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val e=c.get().editor!!;render(e,"preview2-tool-before")
        click(e,"Smudge");assertEquals("Smudge",e.session.tool);assertNotNull(named(e,"Smudge Library"));val panel=e.findViewWithTag<View>("tool-panel")
        click(e,"Erase");assertEquals("Erase",e.session.tool);assertNotNull(named(e,"Eraser Library"));assertSame(panel,e.findViewWithTag<View>("tool-panel"))
        click(e,"Paint");assertNotNull(named(e,"Brush Library"));assertSame(panel,e.findViewWithTag<View>("tool-panel"))
        click(e,"Paint");shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));assertNull(e.findViewWithTag<View>("tool-panel"))
        click(e,"Erase");assertNotNull(named(e,"Eraser Library"));render(e,"preview2-single-tap-eraser");done(c)
    }
    @Test fun galleryShowsLoadingDeduplicatesTapsAndPresentsTheLoadedFrame(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120);val s=a.session!!;s.color=0xffdd4422.toInt();s.size=20f;s.pressureSize=0f;s.stroke(0,floatArrayOf(80f,60f,1f,0f));s.stroke(2,floatArrayOf());until{s.info.optInt("undo")==1}
        var saved=false;a.saveCurrent{saved=it};until{saved};val art=a.store.list().first{it.id==a.documentId};a.showGallery();val root=a.findViewById<ViewGroup>(android.R.id.content);render(root,"preview2-gallery-ready")
        val release=blockIo(a)
        try{
            val card=checkNotNull(named(root,"${art.name}, 160 by 120 pixels"));card.performClick();repeat(12){a.openArtwork(art)}
            assertTrue(a.navigationBusy);assertFalse(card.isEnabled);assertEquals(.55f,card.alpha,.001f);assertNotNull(named(root,"Opening ${art.name}…"));assertNull(a.editor);render(root,"preview2-gallery-opening")
        }finally{release.countDown()}
        until{!a.navigationBusy && a.editor!=null};assertEquals(art.id,a.documentId);val opened=a.session!!;assertTrue(opened.frameSerial>0);assertEquals(0xffdd4422.toInt(),opened.bitmap.getPixel(80,60));assertNull(named(root,"Opening ${art.name}…"));assertTrue(s.fullyClosed)
        var drained=false;a.io.execute{a.runOnUiThread{drained=true}};until{drained};assertSame(opened,a.session);render(a.editor!!,"preview2-opened-canvas");done(c)
    }
    @Test fun canceledOrBrokenOpenDoesNotReplaceAnotherCanvasAndAllowsRetry(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(96,80);var saved=false;a.saveCurrent{saved=it};until{saved};val art=a.store.list().first{it.id==a.documentId};a.showGallery()
        val release=blockIo(a)
        try{a.openArtwork(art);assertTrue(a.navigationBusy);a.onBackPressed();assertFalse(a.navigationBusy);a.newCanvas(128,96)}finally{release.countDown()}
        val id=a.documentId;val current=a.session;var drained=false;a.io.execute{a.runOnUiThread{drained=true}};until{drained};assertEquals(id,a.documentId);assertSame(current,a.session)
        val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);a.store.save("broken","Broken",byteArrayOf(1,2,3),image);image.recycle();a.showGallery();val broken=a.store.list().first{it.id=="broken"};a.openArtwork(broken);until{!a.navigationBusy};assertNull(a.editor);assertNull(a.session)
        a.openArtwork(art);until{!a.navigationBusy && a.session!=null};assertEquals(art.id,a.documentId);done(c)
    }
    @Test fun savingToGalleryHasOneBusyOperationAndCanBeCanceled(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(64,64,mode="vector");val e=a.vectorEditor!!;render(e,"preview2-save-before");val release=blockIo(a)
        try{a.gallery();repeat(8){a.gallery()};assertTrue(a.navigationBusy);assertNotNull(named(a.findViewById(android.R.id.content),"Saving artwork…"));render(a.findViewById(android.R.id.content),"preview2-saving-artwork");a.onBackPressed();assertFalse(a.navigationBusy)}finally{release.countDown()}
        var drained=false;a.io.execute{a.runOnUiThread{drained=true}};until{drained};assertSame(e,a.vectorEditor);assertEquals(1,a.store.list().count{it.id==a.documentId});done(c)
    }
    @Test fun penOnlyBlocksRasterMarksButKeepsTouchPanZoomRotateToolsAndColorDrop(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120);val e=a.editor!!;val s=a.session!!;render(e,"preview2-touch-before");s.penOnly=true;s.pressureSize=0f;s.stabilization=0f
        for(tool in listOf("Paint","Erase","Smudge")){s.tool=tool;single(e.canvas,MotionEvent.ACTION_DOWN,PointF(400f,300f));single(e.canvas,MotionEvent.ACTION_MOVE,PointF(500f,320f));single(e.canvas,MotionEvent.ACTION_UP,PointF(500f,320f))}
        var read=false;s.command("info"){read=true};until{read};assertEquals(0,s.info.optInt("undo"));s.tool="Paint"
        val p=e.canvas.transformViewPoint(40f,40f);val q=e.canvas.transformViewPoint(100f,40f);navigate(e.canvas);val p2=e.canvas.transformViewPoint(40f,40f);val q2=e.canvas.transformViewPoint(100f,40f)
        assertTrue(abs(q2.y-p2.y)>10);assertTrue(hypot(q2.x-p2.x,q2.y-p2.y)>hypot(q.x-p.x,q.y-p.y)*1.3f)
        val at=e.canvas.transformViewPoint(80f,60f);single(e.canvas,MotionEvent.ACTION_DOWN,at,MotionEvent.TOOL_TYPE_STYLUS);single(e.canvas,MotionEvent.ACTION_MOVE,PointF(at.x+12,at.y+8),MotionEvent.TOOL_TYPE_STYLUS);single(e.canvas,MotionEvent.ACTION_UP,PointF(at.x+12,at.y+8),MotionEvent.TOOL_TYPE_STYLUS);until{s.info.optInt("undo")==1}
        e.canvas.fit();val icon=checkNotNull(named(e,"Erase"));val loc=IntArray(2);val origin=IntArray(2);icon.getLocationInWindow(loc);e.getLocationInWindow(origin);val toolPoint=PointF(loc[0]-origin[0]+icon.width/2f,loc[1]-origin[1]+icon.height/2f)
        single(e,MotionEvent.ACTION_DOWN,toolPoint);single(e,MotionEvent.ACTION_UP,toolPoint);shadowOf(Looper.getMainLooper()).idle();assertNotNull(named(e,"Eraser Library"));e.dismissPanel();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));s.tool="Paint";s.color=0xff20aadd.toInt();e.refreshTools()
        val color=checkNotNull(named(e,"Color"));color.getLocationInWindow(loc);val start=PointF(loc[0]-origin[0]+color.width/2f,loc[1]-origin[1]+color.height/2f);val target=e.canvas.transformViewPoint(30f,30f).apply{offset(e.canvas.left.toFloat(),e.canvas.top.toFloat())}
        single(e,MotionEvent.ACTION_DOWN,start);single(e,MotionEvent.ACTION_MOVE,target);single(e,MotionEvent.ACTION_UP,target);until{s.bitmap.getPixel(30,30)==s.color};assertTrue(s.penOnly);render(e,"preview2-touch-colordrop");done(c)
    }
    @Test fun colorCircleLocksAtFillSeedDuringThresholdAndReturnsAfterCancel(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120);val e=a.editor!!;val s=a.session!!;render(e,"preview3-before-drag")
        until{s.frameSerial>0};s.color=0xffd23b80.toInt();e.refreshTools()
        val control=checkNotNull(named(e,"Color"));val loc=IntArray(2);val origin=IntArray(2);control.getLocationInWindow(loc);e.getLocationInWindow(origin)
        val start=PointF(loc[0]-origin[0]+control.width/2f,loc[1]-origin[1]+control.height/2f)
        val target=e.canvas.transformViewPoint(80f,60f).apply{offset(e.canvas.left.toFloat(),e.canvas.top.toFloat())}
        single(e,MotionEvent.ACTION_DOWN,start);single(e,MotionEvent.ACTION_MOVE,target);assertEquals(0f,control.alpha,0f)
        val overlay=e.findViewWithTag<View>("colordrop-drag-preview")
        fun circleAt(p:PointF){val b=Bitmap.createBitmap(e.width,e.height,Bitmap.Config.ARGB_8888);overlay.draw(Canvas(b));assertEquals("circle at $p overlay ${overlay.width}x${overlay.height}",s.color,b.getPixel(p.x.toInt(),p.y.toInt()));b.recycle()}
        circleAt(target);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));circleAt(target)
        val moved=PointF(target.x+80,target.y+25);single(e,MotionEvent.ACTION_MOVE,moved);circleAt(target);render(e,"preview3-dragging-color")
        single(e,MotionEvent.ACTION_CANCEL,moved);assertEquals(1f,control.alpha,0f)
        val b=Bitmap.createBitmap(e.width,e.height,Bitmap.Config.ARGB_8888);overlay.draw(Canvas(b));assertEquals(0,b.getPixel(moved.x.toInt(),moved.y.toInt()));b.recycle()
        var read=false;s.command("info"){read=true};until{read};assertEquals(0,s.info.optInt("undo"));done(c)
    }
    @Test fun penOnlyVectorKeepsTouchNavigationAndObjectEditing(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.setVectorInputPreference(0,true);a.newCanvas(320,240,mode="vector");val e=a.vectorEditor!!;val canvas=e.canvas;val doc=a.vectorDocument!!;render(e,"preview2-vector-touch-before")
        val at=canvas.viewPoint(60f,60f);single(canvas,MotionEvent.ACTION_DOWN,at);single(canvas,MotionEvent.ACTION_MOVE,PointF(at.x+30,at.y+10));single(canvas,MotionEvent.ACTION_UP,PointF(at.x+30,at.y+10));assertEquals(0,doc.layers[0].objects.size)
        val p=canvas.viewPoint(40f,40f);val q=canvas.viewPoint(100f,40f);navigate(canvas);val p2=canvas.viewPoint(40f,40f);val q2=canvas.viewPoint(100f,40f);assertTrue(abs(q2.y-p2.y)>10);assertTrue(hypot(q2.x-p2.x,q2.y-p2.y)>hypot(q.x-p.x,q.y-p.y)*1.3f)
        click(e,"Line");val start=canvas.viewPoint(60f,60f);val end=canvas.viewPoint(160f,60f);single(canvas,MotionEvent.ACTION_DOWN,start,MotionEvent.TOOL_TYPE_STYLUS);single(canvas,MotionEvent.ACTION_MOVE,end,MotionEvent.TOOL_TYPE_STYLUS);single(canvas,MotionEvent.ACTION_UP,end,MotionEvent.TOOL_TYPE_STYLUS);assertEquals(1,doc.layers[0].objects.size)
        click(e,"Select");val center=canvas.viewPoint(110f,60f);single(canvas,MotionEvent.ACTION_DOWN,center);single(canvas,MotionEvent.ACTION_MOVE,PointF(center.x+20,center.y+15));single(canvas,MotionEvent.ACTION_UP,PointF(center.x+20,center.y+15));assertNotNull(canvas.selected);val values=FloatArray(9);canvas.selected!!.matrix.getValues(values);assertTrue(abs(values[2])+abs(values[5])>1f)
        render(e,"preview2-vector-touch-navigation");done(c)
    }
}
