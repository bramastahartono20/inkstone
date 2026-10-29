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
