package art.inkstone

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Looper
import android.view.*
import android.widget.TextView
import org.jcodec.common.io.NIOUtils
import org.jcodec.containers.mp4.demuxer.MP4Demuxer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File
import kotlin.math.*

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TutorialExportExperienceTest {
    private fun until(check:()->Boolean){val end=System.nanoTime()+45_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};error("Experience did not settle")}
    private fun find(v:View,name:String):View?{if(v.contentDescription==name)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),name)?.let{return it};return null}
    private fun texts(v:View):List<String> = (if(v is TextView)listOf(v.text.toString())else emptyList())+(if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))}else emptyList())
    private fun bitmap(v:View,w:Int=460,h:Int=600):Bitmap{v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}}
    private fun close(c:org.robolectric.android.controller.ActivityController<MainActivity>){val a=c.get();val s=a.session;val r=a.timelapse;c.pause().stop().destroy();until{r.fullyClosed && (s==null || s.fullyClosed) && a.io.isTerminated}}
    @Test fun everyLessonHasAnActionSpecificAnimatedDemonstration(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.showGallery()
        val all=mutableListOf<Bitmap>()
        try{
            Tutorials.lessons.forEachIndexed{topic,lesson->
                Tutorials.show(a,topic)
                repeat(lesson.steps.size){step->
                    val popup=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
                    fun preview(v:View):TutorialPreview?{if(v is TutorialPreview)return v;if(v is ViewGroup)for(i in 0 until v.childCount)preview(v.getChildAt(i))?.let{return it};return null}
                    val p=checkNotNull(preview(popup.contentView));p.showPhase(.12f);val before=bitmap(popup.contentView);p.showPhase(.79f);val after=bitmap(popup.contentView)
                    assertFalse("No visual demonstration: $topic/$step",before.sameAs(after));before.recycle()
                    val file=File(System.getProperty("inkstone.artifacts"),"tutorial-step-$topic-$step.png");file.outputStream().use{after.compress(Bitmap.CompressFormat.PNG,100,it)}
                    all.add(after);assertTrue(find(popup.contentView,"Tutorial next")!!.performClick())
                }
                Tutorials.dismiss(a)
            }
            assertEquals(19,all.size);assertNull(a.editor)
        }finally{all.forEach{it.recycle()};close(c)}
    }
    @Test fun allVideoLengthsFitTheEntireRecordingAndProduceTheRequestedDuration(){
        val dir=File(RuntimeEnvironment.getApplication().cacheDir,"durations").apply{mkdirs()}
        val files=listOf(Color.RED,Color.GREEN,Color.BLUE).mapIndexed{i,color->File(dir,"$i.jpg").also{file->val b=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);b.eraseColor(color);file.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle()}}
        for(seconds in listOf(30,60,120)){
            val movie=File(dir,"$seconds.mp4");val progress=mutableListOf<Int>()
            TimelapseVideo.encode(files,movie,true,seconds){progress.add(it)}
            val demux=MP4Demuxer.createMP4Demuxer(NIOUtils.readableChannel(movie))
            try{assertEquals(seconds.toDouble(),demux.videoTrack.meta.totalDuration,.02);assertEquals(seconds*TimelapseVideo.FPS,demux.videoTrack.meta.totalFrames)}finally{demux.close()}
            assertEquals(100,progress.last());assertTrue(progress.zipWithNext().all{it.first<=it.second})
            for(count in listOf(1,3,10_000)){val indices=(0 until seconds*TimelapseVideo.FPS).map{TimelapseVideo.sourceIndex(it,count,seconds)};assertEquals(0,indices.first());assertEquals(count-1,indices.last());assertTrue(indices.zipWithNext().all{it.first<=it.second})}
        }
        dir.deleteRecursively()
    }
    @Test fun exportShowsProgressAndOnlyReportsSavedAfterTheChosenFileIsWritten(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(64,48)
        try{
            until{a.timelapse.idle && a.timelapse.count(a.documentId)>0}
            val e=a.editor!!;find(e,"Actions")!!.performClick();shadowOf(Looper.getMainLooper()).idle();find(e,"Video")!!.performClick();shadowOf(Looper.getMainLooper()).idle()
            val panel=e.findViewWithTag<View>("tool-panel");assertNotNull(panel);assertTrue(texts(panel).none{it.contains("frame",ignoreCase=true)})
            find(panel,"Time-lapse length 1 min")!!.performClick();assertEquals(60,a.timelapse.duration(a.documentId));a.timelapse.setDuration(a.documentId,30)
            a.exportTimelapse()
            fun status():String{val popup=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow;return (find(popup.contentView,"Time-lapse export progress") as? TextView)?.text?.toString()?:""}
            assertTrue(status().contains("Preparing"));until{status().contains("Choose where")};assertFalse(status().startsWith("Saved"))
            val intent=shadowOf(a).nextStartedActivityForResult;assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.intent.action);assertEquals("video/mp4",intent.intent.type)
            val target=File(a.cacheDir,"saved-timelapse.mp4")
            @Suppress("DEPRECATION")
            MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply{isAccessible=true}.invoke(a,100,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target)))
            assertEquals("Saving video…",status());until{status().startsWith("Saved")};assertTrue(target.length()>1000)
            val popup=shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow;val b=bitmap(popup.contentView,390,220);File(System.getProperty("inkstone.artifacts"),"timelapse-saved-status.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle();target.delete()
        }finally{close(c)}
    }
    @Test fun newBrushFamiliesAreDistinctAndCustomCopiesKeepTheirNativeTip(){
        val context=RuntimeEnvironment.getApplication();val library=BrushLibrary(context)
        for(group in listOf("Surfaces","Retro","Botanical")){
            val presets=brushPresets.filter{it.group==group};assertTrue(presets.size>=3);assertTrue(presets.map{it.kind}.distinct().size>=3);assertTrue(presets.all{it.kind in 32..42})
            presets.forEach{p->val custom=library.copy(p);assertEquals(p.kind,BrushLibrary(context).custom().first{it.customId==custom.customId}.kind)}
        }
    }
}
