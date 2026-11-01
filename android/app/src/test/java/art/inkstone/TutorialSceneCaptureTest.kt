package art.inkstone

import android.graphics.*
import android.os.Looper
import android.view.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File
import kotlin.math.*

/** Regenerates the packaged demonstrations from real editor controls in an isolated test app. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TutorialSceneCaptureTest {
    private fun until(check:()->Boolean){val end=System.nanoTime()+30_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};error("Demo did not settle")}
    private fun find(v:View,name:String):View?{if(v.contentDescription==name)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),name)?.let{return it};return null}
    private fun layout(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,800);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(800))}
    @Test fun captureRealMenuAndGestureScenes(){
        for(light in listOf(false,true)){
            val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.getSharedPreferences("timelapse",0).edit().putBoolean("defaultEnabled",false).apply();a.newCanvas(400,300);val e=a.editor!!;val s=e.session
            val dir=File(System.getProperty("inkstone.artifacts"),"tutorial-scenes/${if(light)"light"else"dark"}").apply{mkdirs()};val metadata=JSONObject()
            fun command(op:String,values:JSONObject=JSONObject()){var ready=false;s.command(op,values){assertFalse(it.toString(),it.has("error"));ready=true};until{ready}}
            fun camera():Matrix{val p=e.canvas.transformViewPoint(0f,0f);val x=e.canvas.transformViewPoint(400f,0f);val y=e.canvas.transformViewPoint(0f,300f);return Matrix().apply{setPolyToPoly(floatArrayOf(0f,0f,400f,0f,0f,300f),0,floatArrayOf(p.x,p.y,x.x,x.y,y.x,y.y),0,3)}}
            fun tap(name:String,root:View=e){assertTrue("Missing $name",checkNotNull(find(root,name)){name}.performClick());layout(e)}
            fun scene(name:String,popup:android.widget.PopupWindow?=null){
                layout(e);val b=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888);val c=Canvas(b);e.draw(c)
                val item=JSONObject();val origin=IntArray(2);e.getLocationInWindow(origin)
                fun targets(v:View){v.contentDescription?.toString()?.takeIf{it.length<100}?.let{key->val at=IntArray(2);v.getLocationInWindow(at);item.put(key,JSONArray(listOf(at[0]-origin[0]+v.width/2f,at[1]-origin[1]+v.height/2f)))};if(v is ViewGroup)for(i in 0 until v.childCount)targets(v.getChildAt(i))}
                targets(e)
                val panel=e.findViewWithTag<View>("tool-panel")
                if(panel!=null){val at=IntArray(2);panel.getLocationInWindow(at);item.put("region",JSONArray(listOf(at[0]-origin[0],at[1]-origin[1],at[0]-origin[0]+panel.width,at[1]-origin[1]+panel.height)))}
                if(popup!=null){val v=popup.contentView;v.measure(View.MeasureSpec.makeMeasureSpec(popup.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(if(popup.height>0)popup.height else 700,View.MeasureSpec.AT_MOST));v.layout(0,0,v.measuredWidth,v.measuredHeight);val at=IntArray(2);v.getLocationInWindow(at);val x=(at[0]-origin[0]).coerceIn(8,1280-v.width-8);val y=(at[1]-origin[1]).coerceIn(44,(792-v.height).coerceAtLeast(44));c.save();c.translate(x.toFloat(),y.toFloat());v.draw(c);c.restore();item.put("region",JSONArray(listOf(x,y,x+v.width,y+v.height)));popup.dismiss()}
                val p=e.canvas.transformViewPoint(200f,150f);val offset=IntArray(2);e.canvas.getLocationInWindow(offset);item.put("Canvas center",JSONArray(listOf(p.x+offset[0]-origin[0],p.y+offset[1]-origin[1])))
                File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle();metadata.put(name,item)
            }
            try{
                e.setLightInterface(light);e.dismissPanel();layout(e);s.choose(brushPresets.first{it.name=="Studio Pen"});s.size=5f;s.color=0xff3b75b5.toInt();s.stabilization=0f;s.pressureSize=0f
                val points=FloatArray(97*4);for(i in 0..96){val t=i/96f*2*PI;points[i*4]=200+cos(t).toFloat()*92;points[i*4+1]=150+sin(t).toFloat()*74;points[i*4+2]=1f}
                s.stroke(0,points.copyOfRange(0,4));s.stroke(1,points.copyOfRange(4,points.size));s.stroke(2,floatArrayOf());command("info");s.color=0xffe3a252.toInt();e.refreshTools();scene("workspace")
                val fitted=camera()
                for(i in 0..12){val t=i/12f;e.canvas.setAtlasCamera(Matrix(fitted).apply{postScale(1+t*.5f,1+t*.5f,e.canvas.width/2f,e.canvas.height/2f);postRotate(t*12,e.canvas.width/2f,e.canvas.height/2f)});scene("zoom-$i")};e.canvas.fit()
                tap("Paint");scene("brushes");e.dismissPanel();layout(e)
                tap("Color");scene("colors");e.dismissPanel();layout(e)
                command("flood",JSONObject().put("x",200).put("y",150).put("color",0xffe3a252L).put("tolerance",.2));scene("filled");command("undo")
                tap("Paint Mixing");scene("mixing");e.dismissPanel();layout(e)
                s.choose(brushPresets.first{it.name=="Water Wash"});s.pressureSize=0f;s.pressureOpacity=0f;s.size=70f;s.color=Color.BLUE
                s.stroke(0,floatArrayOf(90f,150f,1f,0f));s.stroke(1,floatArrayOf(310f,150f,1f,0f));s.stroke(2,floatArrayOf());command("info")
                s.color=Color.YELLOW;s.paintMixingEnabled=true;s.paintBrush.wetMix=.8f;s.paintBrush.wetPickup=.6f;s.paintBrush.wetness=.65f
                s.stroke(0,floatArrayOf(200f,65f,1f,0f));s.stroke(1,floatArrayOf(200f,240f,1f,0f));s.stroke(2,floatArrayOf());command("info");scene("mixing-result");command("undo");command("undo");s.choose(brushPresets.first{it.name=="Studio Pen"});s.size=5f;s.color=0xffe3a252.toInt();e.refreshTools()
                tap("Layers");scene("layers");e.dismissPanel();command("add");command("group",JSONObject().put("action","create").put("name","Color study").put("empty",true));tap("Layers");scene("group-empty");e.dismissPanel();command("group",JSONObject().put("action","member").put("id",s.info.getJSONArray("groups").getJSONObject(0).getLong("id")));tap("Layers");scene("groups");tap("Options for group Color study")
                val options=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow;tap("Blend & opacity",options.contentView);scene("group-blend",shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow);options.dismiss();e.dismissPanel();layout(e)
                val fit=QuickShape.fit((0..96).map{i->PointF(points[i*4],points[i*4+1])})!!;e.canvas.quickShapeEdit.start(fit);e.canvas.quickShapeEdit.release();command("info");scene("shape");e.canvas.finishQuickShape(true);command("info")
                command("active",JSONObject().put("index",0));tap("Transform");command("info");scene("transform");e.canvas.rasterTransform.finish(true);s.tool="Paint";e.refreshTools()
                tap("Actions");tap("Share");scene("share");tap("Video");scene("video-off");tap("Time-lapse recording");until{a.timelapse.idle && a.timelapse.count(a.documentId)>0};scene("video");tap("Time-lapse length 1 min");scene("video-minute");tap("Time-lapse recording");e.dismissPanel();layout(e)
                val progress=TimelapseExportProgress(a);progress.preparing();progress.progress(42);scene("export",shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow)
                for((i,color) in listOf(0xffe8d2b2.toInt(),0xffb7d6ce.toInt(),0xffbfceea.toInt()).withIndex()){
                    val doc=VectorDocument(200,150,background=color);doc.layers[0].objects.add(VectorObject(doc.nextId(),"Ellipse",mutableListOf(PointF(40f,30f),PointF(160f,120f)),0xff3b75b5.toInt(),0xff3b75b5.toInt(),2f));val b=doc.bitmap(200);a.store.save(a.store.newId(),"Study ${i+1}",doc.encode(),b,200,150,"vector");b.recycle()
                }
                val pageCamera=camera();a.showAtlas(true);layout(e)
                for(i in 0..12){val t=i/12f;e.canvas.setAtlasCamera(Matrix(pageCamera).apply{postScale(1-t*.62f,1-t*.62f,e.canvas.width/2f,e.canvas.height/2f)});scene("atlas-zoom-$i")}
                repeat(3){scene("atlas");Thread.sleep(100)};assertTrue(a.atlas!!.previewCount>0);val boardCamera=camera()
                for(i in 0..12){e.canvas.setAtlasCamera(Matrix(boardCamera).apply{postTranslate(-i*22f,0f)});scene("atlas-pan-$i")}
                a.atlas!!.activateCurrent();scene("atlas-active");a.closeAtlas()
                File(dir,"targets.json").writeText(metadata.toString(2));assertTrue(metadata.has("zoom-12") && metadata.has("atlas-pan-12") && metadata.has("mixing-result"))
            }finally{val r=a.timelapse;controller.pause().stop().destroy();until{s.fullyClosed && r.fullyClosed && a.io.isTerminated}}
        }
    }
    @Test fun captureNativeBrushSwatches(){
        val dir=File(System.getProperty("inkstone.artifacts"),"brush-swatches").apply{mkdirs()}
        for(kind in listOf(4,11)+(32..42)){
            val handle=NativeBridge.create(280,72);assertTrue(handle!=0L)
            try{
                val points=FloatArray(121*4);for(i in 0..120){val t=i/120f;points[i*4]=26+t*228;points[i*4+1]=40-sin(t*PI).toFloat()*8;points[i*4+2]=.55f+sin(t*PI).toFloat()*.4f}
                NativeBridge.stroke(handle,0,points.copyOfRange(0,4),kind,Color.WHITE,40f,1f,.8f,0f,.3f,0f)
                NativeBridge.stroke(handle,1,points.copyOfRange(4,points.size),kind,Color.WHITE,40f,1f,.8f,0f,.3f,0f);NativeBridge.stroke(handle,2,floatArrayOf(),kind,Color.WHITE,40f,1f,.8f,0f,.3f,0f)
                val bytes=java.nio.ByteBuffer.allocateDirect(280*72*4);assertTrue(NativeBridge.render(handle,bytes,false));bytes.rewind();val image=Bitmap.createBitmap(280,72,Bitmap.Config.ARGB_8888);image.copyPixelsFromBuffer(bytes);File(dir,"tip-$kind.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            }finally{NativeBridge.destroy(handle)}
        }
    }
}
