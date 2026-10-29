package art.inkstone

import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.util.concurrent.*

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class BugHuntTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+25_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Bug-hunt test timed out")}
    private fun named(v:View,s:String):View?{if(v.contentDescription==s)return v;if(v is ViewGroup)for(i in 0 until v.childCount)named(v.getChildAt(i),s)?.let{return it};return null}
    private fun click(v:View,s:String){assertTrue(checkNotNull(named(v,s)){s}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun command(s:DrawingSession,op:String,values:JSONObject=JSONObject()){var ready=false;s.command(op,values){assertFalse(it.toString(),it.has("error"));ready=true};until{ready}}
    private fun done(c:org.robolectric.android.controller.ActivityController<MainActivity>){val s=c.get().session;c.pause().stop().destroy();until{s==null || s.fullyClosed};until{c.get().io.isTerminated}}
    @Test fun newEmptyGroupCanToggleFromItsName(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128);val e=a.editor!!;val s=e.session
        command(s,"group",JSONObject().put("action","create").put("name","Empty").put("empty",true));click(e,"Layers")
        until{named(e,"Toggle group Empty")!=null};click(e,"Toggle group Empty")
        until{s.info.getJSONArray("groups").getJSONObject(0).getBoolean("collapsed")};command(s,"info")
        until{named(e,"Toggle group Empty")!=null};click(e,"Toggle group Empty")
        until{!s.info.getJSONArray("groups").getJSONObject(0).getBoolean("collapsed")};done(c)
    }
    @Test fun referenceHoldPicksWithoutPickMode(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();var color=0
        val image=ReferenceImage(c.get()){color=it};image.layout(0,0,100,100)
        val host=FrameLayout(c.get());host.addView(image,FrameLayout.LayoutParams(100,100));c.get().setContentView(host);shadowOf(Looper.getMainLooper()).idle()
        image.replace(Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)})
        val now=android.os.SystemClock.uptimeMillis();val event=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,50f,50f,0);image.onTouchEvent(event)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500));assertEquals(Color.RED,color)
        val preview=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);image.draw(Canvas(preview));assertNotEquals("Picker ring is missing",Color.RED,preview.getPixel(50,67));preview.recycle()
        image.onTouchEvent(MotionEvent.obtain(0,700,MotionEvent.ACTION_UP,50f,50f,0));image.replace(null);done(c)
    }
    @Test fun groupOpacityUpdatesLiveWithoutReplacingLayers(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128);val e=a.editor!!;val s=e.session
        command(s,"group",JSONObject().put("action","create").put("name","Live"));click(e,"Layers");until{named(e,"Options for group Live")!=null}
        click(e,"Options for group Live")
        val menu=shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).latestPopupWindow
        click(menu.contentView,"Blend & opacity")
        val child=shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).latestPopupWindow
        assertTrue(menu.isShowing);assertNotSame(menu,child)
        val seek=named(child.contentView,"Opacity") as SeekBar
        seek.progress=37
        val listener=SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener").apply{isAccessible=true}.get(seek) as SeekBar.OnSeekBarChangeListener;listener.onProgressChanged(seek,37,true)
        until{kotlin.math.abs(s.info.getJSONArray("groups").getJSONObject(0).getDouble("opacity")-.37)<.001}
        assertTrue(child.isShowing);assertNotNull(named(e,"Group row Live"));click(child.contentView,"← Back");assertTrue(menu.isShowing)
        menu.dismiss();done(c)
    }
    @Test fun collapsingLowGroupPreservesScroll(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128);val e=a.editor!!;val s=e.session
        repeat(22){command(s,"group",JSONObject().put("action","create").put("name","Folder $it").put("empty",true))}
        click(e,"Layers");until{named(e,"Toggle group Folder 0")!=null}
        fun layout(){e.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));e.layout(0,0,1280,800);shadowOf(Looper.getMainLooper()).idle()}
        layout()
        fun scroll():ScrollView{var v:android.view.ViewParent?=named(e,"Toggle group Folder 0")!!.parent;while(v !is ScrollView)v=v!!.parent;return v}
        scroll().scrollTo(0,400);val before=scroll().scrollY;assertTrue(before>0)
        val old=named(e,"Toggle group Folder 0");click(e,"Toggle group Folder 0")
        until{named(e,"Toggle group Folder 0")!==old};layout();assertEquals(before,scroll().scrollY);done(c)
    }
    @Test fun groupDragPreviewsAndCommitsOneUndo(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128,visible=false);val e=a.editor!!;val s=e.session
        command(s,"fill",JSONObject().put("color",0xffff0000L));command(s,"group",JSONObject().put("action","create").put("name","Moving"))
        command(s,"add");command(s,"deselect");s.tool="Transform";val t=e.canvas.rasterTransform;t.beginGroup(1);until{t.ready}
        val before=s.info.getInt("undo");val at=PointF(30f,30f);val to=PointF(50f,45f);val start=e.canvas.transformDocumentPoint(at.x,at.y);val end=e.canvas.transformDocumentPoint(to.x,to.y)
        fun touch(action:Int,x:Float,y:Float){t.touch(MotionEvent.obtain(0,0,action,x,y,0))}
        touch(MotionEvent.ACTION_DOWN,at.x,at.y);touch(MotionEvent.ACTION_MOVE,to.x,to.y);touch(MotionEvent.ACTION_UP,to.x,to.y)
        assertEquals(before,s.info.getInt("undo"));val m=FloatArray(9);t.currentMatrix().getValues(m);assertEquals(end.x-start.x,m[Matrix.MTRANS_X],.1f);assertEquals(end.y-start.y,m[Matrix.MTRANS_Y],.1f)
        t.finish();s.tool="Paint";until{s.info.getInt("undo")==before+1};command(s,"info");command(s,"undo");assertEquals(before,s.info.getInt("undo"));done(c)
    }
    @Test fun delayedAddCannotReplaceTheNewGroupForm(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128);val e=a.editor!!;val s=e.session
        click(e,"Layers");command(s,"info")
        val f=DrawingSession::class.java.getDeclaredField("worker").apply{isAccessible=true};val worker=f.get(s) as ExecutorService
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        worker.execute{started.countDown();release.await(10,TimeUnit.SECONDS)};assertTrue(started.await(2,TimeUnit.SECONDS))
        try{click(e,"Add");click(e,"Create layer group")}finally{release.countDown()}
        until{s.info.getJSONArray("layers").length()==2};command(s,"info");command(s,"info")
        assertNotNull("A delayed add result replaced the group name form",named(e,"Group name"));done(c)
    }
    @Test fun transformPreviewRetainsNestedGroupOpacity(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128,visible=false);val e=a.editor!!;val s=e.session
        command(s,"fill",JSONObject().put("color",0xffff0000L));command(s,"group",JSONObject().put("action","create").put("name","Inner"))
        command(s,"group",JSONObject().put("action","create").put("name","Outer").put("empty",true))
        command(s,"group",JSONObject().put("action","move").put("id",1).put("parentId",2))
        for(id in 1..2)command(s,"group",JSONObject().put("action","update").put("id",id).put("opacity",.5).put("blend","Normal"))
        s.tool="Transform";e.canvas.rasterTransform.ensure();until{e.canvas.rasterTransform.ready}
        val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);assertTrue(e.canvas.rasterTransform.draw(Canvas(bitmap)))
        assertTrue("Transform preview lost nested group opacity: ${Color.alpha(bitmap.getPixel(40,40))}",Color.alpha(bitmap.getPixel(40,40)) in 62..66);bitmap.recycle();done(c)
    }
    @Test fun renamedCustomBrushRemainsInRecentBrushes(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val lib=BrushLibrary(c.get());val p=lib.copy(brushPresets[0],"Before rename");RecentBrushes(c.get()).record("Paint",p)
        val renamed=lib.save(p.copy(name="After rename"));val recent=RecentBrushes(c.get()).list("Paint")
        assertEquals("Recent brush identity was lost during rename",renamed.customId,recent.firstOrNull()?.customId);assertEquals("After rename",recent.first().name);done(c)
    }

    @Test fun oversizedVectorLayerMetadataIsRejected(){
        val doc=VectorDocument(16,16);val json=JSONObject(String(doc.encode()));val original=json.getJSONArray("layers").getJSONObject(0);val layers=org.json.JSONArray()
        repeat(4097){layers.put(JSONObject(original.toString()).put("id",it+1))};json.put("layers",layers)
        assertTrue(runCatching{VectorDocument.decode(json.toString().toByteArray())}.isFailure)
    }
}
