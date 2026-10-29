package art.inkstone

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WorkflowTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+25_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Workflow operation timed out")}
    private fun named(v:View,s:String):View?{if(v.contentDescription==s || v is TextView && v.text.toString()==s)return v;if(v is ViewGroup)for(i in 0 until v.childCount)named(v.getChildAt(i),s)?.let{return it};return null}
    private fun click(v:View,s:String){assertTrue(checkNotNull(named(v,s)){"Missing $s"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun render(v:View,name:String,w:Int=1280,h:Int=800){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));v.rootView.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.rootView.layout(0,0,w,h);v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(image));File(System.getProperty("inkstone.artifacts"),"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
    private fun touch(v:View,action:Int,x:Float,y:Float){val t=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(t,t,action,x,y,0);v.dispatchTouchEvent(e);e.recycle()}
    private fun done(c:org.robolectric.android.controller.ActivityController<MainActivity>){val s=c.get().session;c.pause().stop().destroy();until{s==null || s.fullyClosed};until{c.get().io.isTerminated}}
    @Test fun maskControlsHideRevealSaveAndRestoreOriginalPixels(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(64,64);val e=a.editor!!;val s=a.session!!;s.color=0xff2468ab.toInt();s.command("fill",JSONObject().put("color",0xffdd5020L));until{s.info.optInt("undo")==1};render(e,"mask-before")
        click(e,"Layers");until{named(e,"Layer 1")!=null};val title=checkNotNull(named(e,"Layer 1"));assertTrue((title.parent as View).performClick());until{named(e,"Add layer mask")!=null};click(e,"Add layer mask");until{s.info.optBoolean("maskEditing") && named(e,"White reveals")!=null}
        s.size=20f;s.pressureSize=0f;s.stabilization=0f;s.stroke(0,floatArrayOf(32f,32f,1f,0f));s.stroke(2,floatArrayOf());until{s.bitmap.getPixel(32,32)==Color.WHITE};render(e,"mask-hidden-center")
        click(e,"White reveals");s.stroke(0,floatArrayOf(32f,32f,1f,0f));s.stroke(2,floatArrayOf());until{s.bitmap.getPixel(32,32)==0xffdd5020.toInt()}
        click(e,"Layer pixels");until{!s.info.optBoolean("maskEditing") && s.color==0xff2468ab.toInt()};assertEquals(0xff2468ab.toInt(),s.color)
        var bytes:ByteArray?=null;s.documentSnapshot{bytes=it};until{bytes!=null};val saved=JSONObject(String(bytes!!));assertNotNull(saved.getJSONArray("layers").getJSONObject(0).getJSONArray("mask"))
        var savedOK=false;a.saveCurrent{savedOK=it};until{savedOK};val art=a.store.list().first{it.id==a.documentId};a.showGallery();a.openArtwork(art);until{a.editor!=null && a.session!==s};assertTrue(a.session!!.info.getJSONArray("layers").getJSONObject(0).getBoolean("hasMask"));done(c)
    }
    @Test fun symmetrySettingsPersistAndGenerateFourCopies(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(64,64);val e=a.editor!!;val s=a.session!!;render(e,"symmetry-before")
        click(e,"Actions");click(e,"Canvas");click(e,"Symmetry");click(e,"Quadrant");assertEquals("Quadrant",s.symmetryMode)
        s.color=0xff20aa66.toInt();s.size=6f;s.pressureSize=0f;s.stroke(0,floatArrayOf(12f,18f,1f,0f));s.stroke(2,floatArrayOf());until{s.info.optInt("undo")==1 && s.bitmap.getPixel(52,46)==s.color};render(e,"symmetry-quadrant")
        a.newCanvas(64,64);assertEquals("Quadrant",a.session!!.symmetryMode);done(c)
    }
    @Test fun editableQuickShapeResizesConvertsAndKeepsOneUndo(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240);val e=a.editor!!;val s=a.session!!;render(e,"shape-edit-before");s.size=3f;s.pressureSize=0f;s.stabilization=0f
        val fit=checkNotNull(QuickShape.fit((0..100).map{val t=it*2*Math.PI/100;PointF(150+70*kotlin.math.cos(t).toFloat(),110+35*kotlin.math.sin(t).toFloat())}))
        e.canvas.quickShapeEdit.start(fit);e.canvas.quickShapeEdit.release();assertTrue(e.canvas.quickShapeEdit.released);assertEquals(0,s.info.optInt("undo"));click(e,"Circle");assertEquals("Circle",e.canvas.quickShapeEdit.name)
        val start=e.canvas.transformViewPoint(220f,180f);val end=e.canvas.transformViewPoint(245f,205f)
        touch(e.canvas,MotionEvent.ACTION_DOWN,start.x,start.y);touch(e.canvas,MotionEvent.ACTION_MOVE,end.x,end.y);touch(e.canvas,MotionEvent.ACTION_UP,end.x,end.y)
        val bounds=e.canvas.quickShapeEdit.bounds();assertTrue(bounds.right>225f)
        until{val x=bounds.right.toInt().coerceIn(1,318);val y=bounds.centerY().toInt().coerceIn(1,238);s.bitmap.getPixel(x,y)!=Color.WHITE || s.bitmap.getPixel(x-1,y)!=Color.WHITE}
        render(e,"quickshape-edit-handles");click(e,"Done");until{s.info.optInt("undo")==1};assertFalse(e.canvas.quickShapeEdit.active)
        var saved:ByteArray?=null;s.documentSnapshot{saved=it};until{saved!=null};s.command("undo");until{s.info.optInt("undo")==0};s.command("redo");until{s.info.optInt("undo")==1};var again:ByteArray?=null;s.documentSnapshot{again=it};until{again!=null};assertArrayEquals(saved,again)
        e.canvas.quickShapeEdit.start(fit);e.canvas.quickShapeEdit.release();click(e,"Cancel shape");until{!s.isStrokeActive};assertEquals(1,s.info.optInt("undo"));done(c)
    }
    @Test fun referenceImageMovesResizesPicksAndReopensInBothEditors(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240);val e=a.editor!!;render(e,"reference-before");e.showReference();val panel=checkNotNull(named(e,"Reference image companion")) as ReferencePanel
        panel.setImage(Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff5577bb.toInt())});render(e,"reference-companion")
        val image=checkNotNull(named(panel,"Reference image. Hold to pick a color. Drag to pan and pinch to zoom."));click(panel,"Pick reference color");touch(image,MotionEvent.ACTION_DOWN,image.width/2f,image.height/2f);touch(image,MotionEvent.ACTION_UP,image.width/2f,image.height/2f);assertEquals(0xff5577bb.toInt(),a.session!!.color)
        val header=checkNotNull(named(panel,"Move reference window"));val before=(panel.layoutParams as android.widget.FrameLayout.LayoutParams).leftMargin;touch(header,MotionEvent.ACTION_DOWN,20f,20f);touch(header,MotionEvent.ACTION_MOVE,120f,40f);touch(header,MotionEvent.ACTION_UP,120f,40f);assertTrue((panel.layoutParams as android.widget.FrameLayout.LayoutParams).leftMargin>before)
        val handle=checkNotNull(named(panel,"Resize reference window"));val width=panel.layoutParams.width;touch(handle,MotionEvent.ACTION_DOWN,5f,5f);touch(handle,MotionEvent.ACTION_MOVE,65f,65f);touch(handle,MotionEvent.ACTION_UP,65f,65f);assertTrue(panel.layoutParams.width>width)
        val path=File(a.filesDir,"references/${a.documentId}.png");until{path.exists()};click(panel,"Close reference");assertNull(named(e,"Reference image companion"));e.showReference();render(e,"reference-restored")
        a.newCanvas(320,240,mode="vector");a.vectorEditor!!.showReference();render(a.vectorEditor!!,"vector-reference-companion");assertNotNull(named(a.vectorEditor!!,"Reference image companion"));done(c)
    }
    @Test fun layeredPsdPickerImportAndInvalidFileLeaveExistingArtworkSafe(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(80,80);a.importDocument();var launched:org.robolectric.shadows.ShadowActivity.IntentForResult?=null;until{launched=shadowOf(a).nextStartedActivityForResult;launched!=null}
        val root=File(System.getProperty("inkstone.artifacts")).parentFile;val fixture=File(root,"crates/ink-engine/tests/fixtures/layered-1.psd")
        shadowOf(a).receiveResult(launched!!.intent,Activity.RESULT_OK,Intent().setData(Uri.fromFile(fixture)));until{a.session?.canvasWidth==48 && a.editor!=null};val s=a.session!!;assertEquals(2,s.info.getJSONArray("layers").length());assertTrue(s.info.getJSONArray("layers").getJSONObject(0).getBoolean("hasMask"));render(a.editor!!,"psd-imported-layers")
        val original=a.documentId;val invalid=File(a.cacheDir,"invalid.psd").apply{writeText("8BPSinvalid")};a.importDocument();shadowOf(Looper.getMainLooper()).idle();until{launched=shadowOf(a).nextStartedActivityForResult;launched!=null};shadowOf(a).receiveResult(launched!!.intent,Activity.RESULT_OK,Intent().setData(Uri.fromFile(invalid)));Thread.sleep(150);shadowOf(Looper.getMainLooper()).idle();assertSame(s,a.session);assertEquals(original,a.documentId);done(c)
    }
}
