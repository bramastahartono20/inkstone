package art.inkstone

import android.graphics.*
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.jcodec.common.Codec
import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File
import java.util.UUID

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MixingTimelapseTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+30_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Mixing/time-lapse check timed out")}
    private fun find(v:View,name:String):View?{if(v.contentDescription==name)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),name)?.let{return it};return null}
    private fun click(v:View,name:String){assertTrue(checkNotNull(find(v,name)){"Missing $name"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun close(c:org.robolectric.android.controller.ActivityController<MainActivity>){val a=c.get();val s=a.session;val r=a.timelapse;c.pause().stop().destroy();until{r.fullyClosed && (s==null || s.fullyClosed) && a.io.isTerminated}}
    private fun screenshot(v:View,name:String,w:Int=1280,h:Int=800){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getProperty("inkstone.artifacts"),"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}

    @Test fun recentColorsStayOneRowScrollableAndTutorialPointsAtRealControl(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128)
        repeat(30){RecentColors(a).record(Color.rgb(it*7,40,120))}
        val recent=RecentColorRow(a){}
        recent.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.AT_MOST));recent.layout(0,0,300,recent.measuredHeight)
        val scroll=recent.getChildAt(1) as android.widget.HorizontalScrollView
        val swatches=scroll.getChildAt(0) as android.widget.LinearLayout
        assertEquals(30,swatches.childCount);assertEquals(android.widget.LinearLayout.HORIZONTAL,swatches.orientation)
        scroll.scrollTo(300,0);assertTrue(scroll.scrollX>0)
        val preview=TutorialPreview(a,1,0);assertEquals("Color",preview.target());assertNotNull(preview.findTarget())
        Tutorials.show(a,1);val popup=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        assertTrue(find(popup.contentView,"Show tutorial control")!!.performClick());assertFalse(popup.isShowing)
        val host=a.findViewById<ViewGroup>(android.R.id.content)
        assertNotNull(find(host,"Tutorial highlighted Color"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3000))
        assertNull(find(host,"Tutorial highlighted Color"));close(c)
    }

    @Test fun captureRealWorkspaceForGalleryTutorials(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,96);val e=a.editor!!
        screenshot(e,"tutorial-workspace-source")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600))
        screenshot(e,"tutorial-workspace-source")
        val bounds=JSONObject();val origin=IntArray(2);e.getLocationInWindow(origin)
        for(name in listOf("Paint","Brush size","Undo","Color","Paint Mixing","Layers","Transform","Actions","Save artwork")){
            val v=checkNotNull(find(e,name));val at=IntArray(2);v.getLocationInWindow(at)
            bounds.put(name,org.json.JSONArray(listOf(at[0]-origin[0]+v.width/2f,at[1]-origin[1]+v.height/2f)))
        }
        File(System.getProperty("inkstone.artifacts"),"tutorial-workspace-targets.json").writeText(bounds.toString())
        close(c)
    }

    @Test fun galleryLessonUsesRealEditorImageAndDoesNotOpenAnArtwork(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val previous=a.session;a.showGallery();until{previous==null || previous.fullyClosed};assertNull(a.editor)
        val before=a.store.list().size;Tutorials.show(a,1)
        val popup=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        screenshot(popup.contentView,"tutorial-gallery-fixed",460,600)
        fun preview(v:View):TutorialPreview?{if(v is TutorialPreview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)preview(v.getChildAt(i))?.let{return it};return null}
        val view=checkNotNull(preview(popup.contentView));val f=TutorialPreview::class.java.getDeclaredField("snapshot").apply{isAccessible=true};val image=f.get(view) as Bitmap
        val source=a.assets.open("tutorials/dark/workspace.png").use{BitmapFactory.decodeStream(it)}
        assertTrue(image.sameAs(source));source.recycle();assertNull(a.editor);assertEquals(before,a.store.list().size)
        assertNull(find(popup.contentView,"Show tutorial control"));close(c)
    }

    @Test fun mixingUsesSelectedBrushAndHasAnOnOffRing(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,128);val e=a.editor!!;val s=e.session
        val chosen=brushPresets.first{it.name=="Water Wash"};s.choose(chosen);click(e,"Paint Mixing")
        assertEquals(chosen,s.preset);assertEquals(chosen.kind,s.paintBrush.kind);assertNull(find(e,"Brush tip"))
        val icon=find(e,"Paint Mixing") as ToolIcon;assertTrue(icon.mixingEnabled)
        val sampleIcon=ToolIcon(a,"Paint Mixing"){}.apply{mixingEnabled=true;layout(0,0,48,48)};val b=Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888);sampleIcon.draw(Canvas(b));val green=b.getPixel(42,24);assertTrue(Color.green(green)>Color.red(green));b.recycle()
        screenshot(e,"mixing-refill-panel")
        click(e,"Mixing enabled");assertFalse(s.paintMixingEnabled);assertFalse(icon.mixingEnabled)
        screenshot(e,"mixing-disabled-indicator");close(c)
    }

    @Test fun firstUseLessonsCanBeDismissedCompletedAndReopened(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();shadowOf(Looper.getMainLooper()).idle()
        val host=a.findViewById<ViewGroup>(android.R.id.content)
        if(find(host,"Dismiss welcome tutorial")!=null)click(host,"Dismiss welcome tutorial")
        assertTrue(a.getSharedPreferences("tutorials",0).getBoolean("welcomeSeen",false))
        Tutorials.show(a,1)
        fun popup()=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        screenshot(popup().contentView,"tutorial-colors",460,600)
        repeat(Tutorials.lessons[1].steps.size){click(popup().contentView,"Tutorial next")}
        assertTrue(a.getSharedPreferences("tutorials",0).getStringSet("completed",emptySet())!!.contains("1"))
        assertNotNull(find(popup().contentView,"Tutorial Colors & Paint Mixing"));click(popup().contentView,"Close tutorials")
        Tutorials.show(a);assertNotNull(find(popup().contentView,"Tutorial Save & time-lapse"));close(c)
    }

    @Test fun recordingPersistsPausesDeduplicatesAndExportsPlayableMp4(){
        val context=RuntimeEnvironment.getApplication();val id=UUID.randomUUID().toString();var error:String?=null
        val record=TimelapseRecorder(context,error={error=it},encode={files,target,seconds,progress->TimelapseVideo.encode(files,target,softwareOnly=true,durationSeconds=seconds,progress=progress)})
        val image=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888)
        try{
            record.setEnabled(id,true);image.eraseColor(Color.RED);record.capture(id,image,true);until{record.idle && record.count(id)==1}
            record.capture(id,image,true);until{record.idle};assertEquals(1,record.count(id))
            image.eraseColor(Color.BLUE);record.capture(id,image,true);until{record.idle && record.count(id)==2}
            record.setEnabled(id,false);image.eraseColor(Color.GREEN);record.capture(id,image,true);assertTrue(record.idle);assertEquals(2,record.count(id))
            val reopened=TimelapseRecorder(context);assertEquals(2,reopened.count(id));assertFalse(reopened.enabled(id));reopened.close();until{reopened.fullyClosed}
            record.setEnabled(id,true);record.capture(id,image,true);until{record.idle && record.count(id)==3}
            var ready=false;var movie:File?=null;var failure:String?=null
            record.export(id,image){file,e->movie=file;failure=e;ready=true};until{ready}
            assertNull(failure);assertNull(error);val file=checkNotNull(movie);assertTrue(file.length()>1000)
            val demux=MP4Demuxer.createMP4Demuxer(NIOUtils.readableChannel(file))
            try{val meta=demux.videoTrack.meta;assertEquals(Codec.H264,meta.codec);assertEquals(30*TimelapseVideo.FPS,meta.totalFrames);assertEquals(64,meta.videoCodecMeta.size.width);assertEquals(48,meta.videoCodecMeta.size.height);assertEquals(30.0,meta.totalDuration,.02)}finally{demux.close()}
            file.copyTo(File(System.getProperty("inkstone.artifacts"),"timelapse-export-test.mp4"),true);file.delete()
            var cleared=false;record.clear(id){cleared=true};until{cleared};assertEquals(0,record.count(id))
        }finally{image.recycle();record.close();until{record.fullyClosed}}
    }

    @Test fun hardwareYuvConversionHasCorrectLumaAndChroma(){
        val black=TimelapseVideo.yuv(IntArray(16){Color.BLACK},4,4)
        assertTrue(black[0].all{it.toInt()and 255==16});assertTrue(black[1].all{it.toInt()and 255==128});assertTrue(black[2].all{it.toInt()and 255==128})
        val red=TimelapseVideo.yuv(IntArray(16){Color.RED},4,4)
        assertEquals(82,red[0][0].toInt()and 255);assertEquals(90,red[1][0].toInt()and 255);assertEquals(240,red[2][0].toInt()and 255)
    }

    @Test fun movingStrokesAlsoRequestRecordingFrames(){
        val s=DrawingSession(128,128);var captured=0;s.onCommittedFrame={captured++}
        try{
            s.captureNextFrame();until{captured==1}
            s.stroke(0,floatArrayOf(20f,30f,1f,0f));s.stroke(1,floatArrayOf(90f,30f,1f,0f))
            var ready=false;s.command("info"){ready=true};until{ready && captured>=2};val during=captured
            s.stroke(2,floatArrayOf());until{captured>during}
        }finally{s.close();until{s.fullyClosed}}
    }

    @Test fun videoControlsAreAvailableAndNewArtworkDefaultDoesNotChangeExistingRecording(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(128,96);val e=a.editor!!;val recorder=a.timelapse
        until{recorder.idle && recorder.count(a.documentId)>0}
        click(e,"Actions");click(e,"Video");assertNotNull(find(e,"Time-lapse recording"));assertNotNull(find(e,"Export time-lapse"))
        screenshot(e,"timelapse-video-controls")
        val id=a.documentId;assertTrue(recorder.enabled(id))
        a.getSharedPreferences("timelapse",0).edit().putBoolean("defaultEnabled",false).apply()
        assertTrue(recorder.enabled(id));val fresh=UUID.randomUUID().toString();recorder.prepare(fresh);assertFalse(recorder.enabled(fresh))
        click(e,"Time-lapse recording");assertFalse(recorder.enabled(id));close(c)
    }

    @Test fun veryNarrowCanvasKeepsAspectRatioInRecordedFrames(){
        val recorder=TimelapseRecorder(RuntimeEnvironment.getApplication());val id=UUID.randomUUID().toString()
        val source=Bitmap.createBitmap(16,8192,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        try{
            recorder.setEnabled(id,true);recorder.capture(id,source,true);until{recorder.idle && recorder.count(id)==1}
            val image=BitmapFactory.decodeFile(recorder.frames(id).single().path)
            try{assertEquals(16,image.width);assertEquals(960,image.height);val edge=image.getPixel(0,480);assertTrue(Color.green(edge)>200);val center=image.getPixel(8,480);assertTrue(Color.red(center)>Color.green(center)+80)}finally{image.recycle()}
            var cleared=false;recorder.clear(id){cleared=true};until{cleared}
        }finally{source.recycle();recorder.close();until{recorder.fullyClosed}}
    }
}
