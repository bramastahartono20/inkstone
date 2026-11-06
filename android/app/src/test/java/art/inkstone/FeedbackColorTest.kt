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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FeedbackColorTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+20_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Timed out")}
    private fun layout(v:View,w:Int=1280,h:Int=800){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))}
    private fun find(v:View,label:String):View?{if(v.contentDescription==label || v is TextView && v.text.toString()==label)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),label)?.let{return it};return null}
    private fun render(v:View,name:String){layout(v);val b=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getProperty("inkstone.artifacts"),"feedback13-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun event(view:View,action:Int,p:PointF,tool:Int=MotionEvent.TOOL_TYPE_FINGER){val prop=MotionEvent.PointerProperties().apply{id=0;toolType=tool};val xy=MotionEvent.PointerCoords().apply{x=p.x;y=p.y;pressure=1f};val time=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(time,time,action,1,arrayOf(prop),arrayOf(xy),0,0,1f,1f,0,0,if(tool==MotionEvent.TOOL_TYPE_STYLUS)InputDevice.SOURCE_STYLUS else if(tool==MotionEvent.TOOL_TYPE_MOUSE)InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN,0);if(action in listOf(MotionEvent.ACTION_HOVER_ENTER,MotionEvent.ACTION_HOVER_EXIT,MotionEvent.ACTION_HOVER_MOVE))view.onHoverEvent(e) else view.onTouchEvent(e);e.recycle()}
    @Test fun galleryRemainsNormalAndOnlyPenHoverGlows(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(120,100);val root=a.editor!!;layout(root);val gallery=find(root,"Home") as GalleryButton
        assertFalse(gallery.typeface.isBold);event(gallery,MotionEvent.ACTION_HOVER_ENTER,PointF(20f,20f),MotionEvent.TOOL_TYPE_MOUSE);assertFalse(gallery.penHovered)
        event(gallery,MotionEvent.ACTION_HOVER_ENTER,PointF(20f,20f),MotionEvent.TOOL_TYPE_STYLUS);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));assertTrue(gallery.penHovered);assertTrue(gallery.shadowRadius>0);assertFalse(gallery.typeface.isBold)
        event(gallery,MotionEvent.ACTION_HOVER_EXIT,PointF(20f,20f),MotionEvent.TOOL_TYPE_STYLUS);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250));assertFalse(gallery.penHovered);assertEquals(0f,gallery.shadowRadius,.01f)
        c.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun largeFontsDoNotClipGalleryHeadingOrApplyButtonInEitherEditor(){
        RuntimeEnvironment.setFontScale(1.7f)
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(120,100);val root=a.editor!!;layout(root);root.backgroundSettings();layout(root)
        fun check(v:View){val gallery=find(v,"Home") as GalleryButton;assertTrue(gallery.width>=gallery.requiredWidth);assertEquals(1,gallery.lineCount);assertFalse(gallery.typeface.isBold)
            val save=(find(v,"Save artwork")?:find(v,"Save")) as TextView;assertTrue(save.width>=kotlin.math.ceil(save.paint.measureText(save.text.toString())).toInt()+save.paddingLeft+save.paddingRight)
            val title=find(v,"Background colour") as TextView;assertEquals(title.text.length,title.layout.getLineEnd(title.lineCount-1));assertTrue(title.height>=title.layout.height)
            val apply=find(v,"Apply color") as TextView;assertEquals(1,apply.lineCount);assertEquals(apply.text.length,apply.layout.getLineEnd(0));val hex=find(v,"Background hex color") as EditText
            val p=IntArray(2);val q=IntArray(2);apply.getLocationInWindow(p);hex.getLocationInWindow(q);assertEquals((q[1]+hex.baseline).toFloat(),(p[1]+apply.baseline).toFloat(),1.5f)}
        check(root);render(root,"large-font-raster")
        a.newCanvas(120,100,mode="vector");val vector=a.vectorEditor!!;layout(vector);vector.backgroundSettings();layout(vector);check(vector);render(vector,"large-font-vector")
        c.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun holdEyedropperWorksWithPenOnlyAndCancelsTheTemporaryStroke(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(120,100,background=0xff347a68.toInt());val s=a.session!!;val root=a.editor!!;until{s.frameSerial>0};layout(root);s.penOnly=true;val p=root.canvas.transformViewPoint(40f,40f);val before=s.info.getInt("undo")
        for(tool in listOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_STYLUS)){
            s.color=Color.RED;event(root.canvas,MotionEvent.ACTION_DOWN,p,tool);event(root.canvas,MotionEvent.ACTION_MOVE,PointF(p.x+2,p.y+2),tool);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));until{s.color==0xff347a68.toInt()};event(root.canvas,MotionEvent.ACTION_UP,p,tool)
        }
        var finished=false;s.imageSnapshot{image->assertEquals(0xff347a68.toInt(),image!!.getPixel(40,40));image.recycle();finished=true};until{finished};assertEquals(before,s.info.getInt("undo"));assertTrue(RecentColors(a).list().isEmpty())
        c.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun usedColorsPersistWithoutChangingCustomPalettesAndCanceledInkIsExcluded(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(120,100);val s=a.session!!;until{s.frameSerial>0};val palette=PaletteStore(a).active.colors.toList()
        s.color=Color.RED;s.stroke(0,floatArrayOf(20f,20f,1f,0f));s.stroke(2,floatArrayOf(40f,20f,1f,0f));until{RecentColors(a).list().contains(Color.RED)}
        s.color=Color.GREEN;s.stroke(0,floatArrayOf(20f,40f,1f,0f));s.stroke(3,floatArrayOf());var read=false;s.command("info"){read=true};until{read};assertFalse(RecentColors(a).list().contains(Color.GREEN))
        var fill=false;s.command("fill",JSONObject().put("color",Color.BLUE.toLong() and 0xffffffffL)){fill=true};until{fill};assertEquals(Color.BLUE,RecentColors(a).list().first());assertEquals(palette,PaletteStore(a).active.colors)
        val store=RecentColors(a);repeat(40){store.record(Color.rgb(it,20,60))};store.record(Color.rgb(39,20,60));assertEquals(30,RecentColors(a).list().size);assertEquals(30,RecentColors(a).list().distinct().size)
        c.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun vectorHoldPicksDirectlyAndCompletedPathsPopulateRecentColors(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.getSharedPreferences("studio",0).edit().putBoolean("penOnly",true).apply();a.newCanvas(120,100,background=0xff458ab4.toInt(),mode="vector");val root=a.vectorEditor!!;layout(root);val canvas=root.canvas;val p=canvas.viewPoint(40f,40f)
        event(canvas,MotionEvent.ACTION_DOWN,p);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));assertEquals(0xff458ab4.toInt(),canvas.ink);event(canvas,MotionEvent.ACTION_UP,p);assertTrue(root.document.layers[0].objects.isEmpty())
        canvas.ink=0xffac42b7.toInt();event(canvas,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS);val end=canvas.viewPoint(80f,80f);event(canvas,MotionEvent.ACTION_MOVE,end,MotionEvent.TOOL_TYPE_STYLUS);event(canvas,MotionEvent.ACTION_UP,end,MotionEvent.TOOL_TYPE_STYLUS);assertTrue(root.document.layers[0].objects.isNotEmpty());assertEquals(0xffac42b7.toInt(),RecentColors(a).list().first())
        c.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun paintMixingControlsPersistInPreferencesAndSavedPresets(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(120,100);val s=a.session!!;s.paintBrush.wetMix=.7f;s.paintBrush.wetPickup=.8f;s.paintBrush.wetness=.9f;a.savePreferences()
        val preset=BrushLibrary(a).copy(s.preset.copy(wetMix=.7f,wetPickup=.8f,wetness=.9f),"Loaded paint");assertEquals(preset,BrushLibrary(a).custom().single());a.newCanvas(120,100);assertEquals(.8f,a.session!!.paintBrush.wetPickup,0f);assertEquals(.9f,a.session!!.paintBrush.wetness,0f)
        c.pause().stop().destroy();until{a.io.isTerminated}
    }
}
