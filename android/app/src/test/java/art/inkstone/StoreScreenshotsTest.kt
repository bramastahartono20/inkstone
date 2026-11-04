package art.inkstone

import android.graphics.Bitmap
import android.graphics.Canvas
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

// Renders the Google Play listing screenshots into artifacts/store at the sizes Play accepts.
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class StoreScreenshotsTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+30_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Store screenshot test timed out")}
    private fun named(v:View,s:String):View?{if(v.contentDescription?.toString()==s || v is TextView && v.text.toString()==s)return v;if(v is ViewGroup)for(i in 0 until v.childCount)named(v.getChildAt(i),s)?.let{return it};return null}
    private fun click(v:View,s:String){assertTrue(checkNotNull(named(v,s)){"Missing $s"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun render(view:View,name:String,w:Int,h:Int){
        val spec={n:Int->View.MeasureSpec.makeMeasureSpec(n,View.MeasureSpec.EXACTLY)}
        repeat(2){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));view.rootView.measure(spec(w),spec(h));view.rootView.layout(0,0,w,h)}
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.rootView.draw(Canvas(b))
        val file=File(System.getProperty("inkstone.artifacts"),"store/$name.png");file.parentFile?.mkdirs()
        file.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()
    }
    private fun settle(s:DrawingSession){var done=false;s.command("info"){done=true};until{done};shadowOf(Looper.getMainLooper()).idle()}
    private fun line(s:DrawingSession,color:Long,size:Float,vararg xy:Float){
        s.color=color.toInt();s.size=size;s.pressureSize=0f
        val pts=FloatArray(xy.size*2);for(i in xy.indices step 2){pts[i*2]=xy[i];pts[i*2+1]=xy[i+1];pts[i*2+2]=1f;pts[i*2+3]=0f}
        s.stroke(0,pts.copyOfRange(0,4));if(pts.size>4)s.stroke(1,pts.copyOfRange(4,pts.size));s.stroke(2,floatArrayOf())
    }
    // An evening landscape built from filled shapes and brush strokes on five named layers.
    private fun artwork(s:DrawingSession){
        fun polygon(color:Long,vararg xy:Int){
            val points=JSONArray();for(i in xy.indices step 2)points.put(JSONArray().put(xy[i]).put(xy[i+1]))
            s.command("select",JSONObject().put("shape","Freehand").put("points",points))
            s.command("fill",JSONObject().put("color",color));s.command("deselect")
        }
        s.command("background",JSONObject().put("color",0xfff1dcc0L))
        s.command("layer",JSONObject().put("name","Sky glow"))
        polygon(0xfff3c39aL,0,520,1600,380,1600,760,0,800)
        line(s,0xfff7e3b0,140f,1180f,300f)
        s.command("add");s.command("layer",JSONObject().put("name","Distant ridges"))
        polygon(0xffa9b7b0L,0,820,240,470,400,640,640,310,850,640,1060,430,1600,870,1600,1200,0,1200)
        s.command("add");s.command("layer",JSONObject().put("name","Evening hills"))
        polygon(0xff5a817bL,0,940,250,760,510,900,820,650,1080,850,1400,690,1600,760,1600,1200,0,1200)
        s.command("add");s.command("layer",JSONObject().put("name","Foreground"))
        polygon(0xff27504eL,0,1100,300,980,600,1090,1000,960,1290,1010,1600,910,1600,1200,0,1200)
        s.command("add");s.command("layer",JSONObject().put("name","Birds"))
        for((x,y) in listOf(520f to 260f,600f to 220f,680f to 290f)) line(s,0xff3a3a44,7f,x-26,y-10,x-12,y-2,x,y+6,x+12,y-2,x+26,y-10)
        settle(s);until{s.bitmap.getPixel(10,10)==0xfff1dcc0.toInt()}
        // Host graphics keeps the first upload of each tile bitmap, so hand the tiles the finished canvas once more.
        val w=s.bitmap.width;val h=s.bitmap.height;val px=IntArray(w*h);s.bitmap.getPixels(px,0,w,0,0,w,h);s.display.update(px,0,0,w,h)
    }
    private fun skipWelcome()=RuntimeEnvironment.getApplication().getSharedPreferences("tutorials",0).edit().putBoolean("welcomeSeen",true).commit()

    @Test fun tabletScreenshots(){
        skipWelcome();val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        a.newCanvas(1600,1200);val e=a.editor!!;val s=e.session
        artwork(s);val (w,h)=2560 to 1600
        render(e,"tablet-1-canvas",w,h)
        click(e,"Paint");render(e,"tablet-2-brushes",w,h)
        e.dismissPanel();click(e,"Layers");until{s.layerThumbnails.size==5};render(e,"tablet-3-layers",w,h)
        e.dismissPanel();click(e,"Color");render(e,"tablet-4-colors",w,h)
        e.dismissPanel()
        var saved=false;a.saveCurrent{saved=true};until{saved}
        a.showGallery();val g=a.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        fun thumb(v:View):Boolean=when(v){is android.widget.ImageView->v.drawable!=null;is ViewGroup->(0 until v.childCount).any{thumb(v.getChildAt(it))};else->false}
        until{thumb(g)};named(g,"Not now")?.performClick();render(g,"tablet-5-gallery",w,h)
        val s2=a.session;c.pause().stop().destroy();until{s2==null || s2.fullyClosed}
    }

    @Test fun phoneScreenshots(){
        RuntimeEnvironment.setQualifiers("w360dp-h720dp-xxhdpi");skipWelcome()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        a.newCanvas(1600,1200);val e=a.editor!!;val s=e.session
        artwork(s);val (w,h)=1080 to 2160
        render(e,"phone-1-canvas",w,h)
        click(e,"Color");render(e,"phone-2-colors",w,h)
        e.dismissPanel();click(e,"Layers");until{s.layerThumbnails.size==5};render(e,"phone-3-layers",w,h)
        e.dismissPanel();click(e,"Paint");render(e,"phone-4-brushes",w,h)
        e.dismissPanel();c.pause().stop().destroy();until{s.fullyClosed}
    }

    private fun save(b:Bitmap,name:String){val f=File(System.getProperty("inkstone.artifacts"),"store/$name.png");f.parentFile?.mkdirs();f.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))};b.recycle()}
    // The launcher icon's pen (drawable/ic_launcher.xml, a 108-unit square) drawn at [scale] from [x], [y].
    private fun pen(c:Canvas,x:Float,y:Float,scale:Float){
        val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun shape(color:Int,vararg xy:Float){val path=android.graphics.Path();path.moveTo(x+xy[0]*scale,y+xy[1]*scale);for(i in 2 until xy.size step 2)path.lineTo(x+xy[i]*scale,y+xy[i+1]*scale);path.close();p.color=color;c.drawPath(path,p)}
        shape(0xfff4f0e9.toInt(),28f,79f,37f,52f,70f,19f,88f,37f,55f,70f)
        shape(0xff248ef0.toInt(),28f,79f,37f,52f,55f,70f)
    }

    @Test fun iconAndFeatureGraphic(){
        val icon=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas(icon).apply{drawColor(0xff202020.toInt());pen(this,0f,0f,512f/108f)};save(icon,"icon-512")
        val banner=Bitmap.createBitmap(1024,500,Bitmap.Config.ARGB_8888);val c=Canvas(banner);c.drawColor(0xff202020.toInt())
        pen(c,40f,105f,320f/108f)
        val t=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=0xfff4f0e9.toInt();textSize=104f;typeface=android.graphics.Typeface.DEFAULT_BOLD}
        c.drawText("Inkstone",400f,240f,t)
        t.apply{textSize=38f;typeface=android.graphics.Typeface.DEFAULT;color=0xffc9c4bc.toInt()}
        c.drawText("Draw and paint on your tablet",404f,310f,t)
        t.apply{textSize=22f;color=0xff248ef0.toInt();letterSpacing=.12f}
        c.drawText("PRESSURE PENS · LAYERS · PAINT MIXING",404f,370f,t)
        save(banner,"feature-graphic")
    }
}
