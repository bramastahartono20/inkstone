package art.inkstone

import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AdjustmentLayerTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+25_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Adjustment layer test timed out")}
    private fun named(v:View,s:String):View?{if(v.contentDescription==s || v is TextView && v.text.toString()==s)return v;if(v is ViewGroup)for(i in 0 until v.childCount)named(v.getChildAt(i),s)?.let{return it};return null}
    private fun texts(v:View):List<String> = if(v is TextView) listOf(v.text.toString()) else if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))} else emptyList()
    private fun click(v:View,s:String){assertTrue(checkNotNull(named(v,s)){"Missing $s"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun command(s:DrawingSession,op:String,values:JSONObject=JSONObject()){var done=false;s.command(op,values){assertFalse(it.toString(),it.has("error"));done=true};until{done}}
    private fun fill(s:DrawingSession,color:Long){
        command(s,"select",JSONObject().put("shape","Rectangle").put("mode","Replace").put("points",JSONArray().put(JSONArray().put(40).put(40)).put(JSONArray().put(200).put(160))))
        command(s,"selectionFill",JSONObject().put("color",color));command(s,"deselect")
    }
    private fun shown(s:DrawingSession,x:Int,y:Int):Int{shadowOf(Looper.getMainLooper()).idle();return s.bitmap.getPixel(x,y)}
    private fun snapshot(v:View,name:String){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));v.rootView.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.rootView.layout(0,0,1280,800);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));val b=android.graphics.Bitmap.createBitmap(1280,800,android.graphics.Bitmap.Config.ARGB_8888);v.rootView.draw(android.graphics.Canvas(b));java.io.File(System.getProperty("inkstone.artifacts"),"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
    private fun layers(s:DrawingSession)=s.info.getJSONArray("layers").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    private fun addAdjustment(e:EditorView,kind:String){
        if(named(e,"Add adjustment layer")==null)click(e,"Layers")
        click(e,"Add adjustment layer");click(e,kind)
    }

    @Test fun adjustmentLayersAreNumberedAndChangeEverythingBelow(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(240,200,visible=false);val e=a.editor!!;val s=e.session
        fill(s,0xffd02020);command(s,"add");fill(s,0xff2040d0)
        command(s,"select",JSONObject().put("shape","Rectangle").put("mode","Replace").put("points",JSONArray().put(JSONArray().put(40).put(40)).put(JSONArray().put(120).put(160))))
        command(s,"clear");command(s,"deselect")
        val red=shown(s,60,100);val blue=shown(s,180,100)
        addAdjustment(e,"Invert");until{layers(s).size==3}
        assertEquals("Adjustment Layer 1",layers(s)[2].getString("name"))
        click(e,"Layers");shadowOf(Looper.getMainLooper()).idle()
        assertTrue("The new layer is listed: ${texts(e)}",texts(e).any{it=="Adjustment Layer 1 · Invert"})
        // Unclipped, it changes both painted layers below it.
        until{shown(s,60,100)!=red && shown(s,180,100)!=blue}
        assertEquals((255-Color.red(red)).toFloat(),Color.red(shown(s,60,100)).toFloat(),2f)
        assertEquals((255-Color.blue(blue)).toFloat(),Color.blue(shown(s,180,100)).toFloat(),2f)
        command(s,"layer",JSONObject().put("clipping",true));until{shown(s,60,100)==red}
        click(e,"Layers");click(e,"Layers");shadowOf(Looper.getMainLooper()).idle();snapshot(e,"clipping-indicator")
        assertNotNull("Clipped layer shows its arrow",named(e,"Clipping Mask enabled for Adjustment Layer 1"))
        assertNotEquals("Clipped, it changes only the layer directly below",blue,shown(s,180,100))
        addAdjustment(e,"Grayscale");until{layers(s).size==4}
        assertEquals("Adjustment Layer 2",layers(s)[3].getString("name"))
        // The same kind again still adds a layer rather than editing the active one.
        addAdjustment(e,"Grayscale");until{layers(s).size==5}
        assertEquals("Adjustment Layer 3",layers(s)[4].getString("name"))
        val c2=a;c.pause().stop().destroy();until{s.fullyClosed};until{c2.io.isTerminated}
    }
}
