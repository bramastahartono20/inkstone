package art.inkstone

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
// The engine library loads into one Robolectric sandbox per JVM, so every test shares NATIVE graphics.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LayerQueueTest {
 private fun find(v:View,name:String):View?{if(v.contentDescription==name)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),name)?.let{return it};return null}
 private fun until(check:()->Boolean){val end=System.nanoTime()+20_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};error("Layer queue did not settle")}
 @Test fun repeatedAddTapsDuringPendingWorkCreateOnlyOneLayer(){
  val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(64,64);val s=a.session!!;val hold=CountDownLatch(1);val entered=CountDownLatch(1)
  try{
   find(a.editor!!,"Layers")!!.performClick();shadowOf(Looper.getMainLooper()).idle()
   val worker=DrawingSession::class.java.getDeclaredField("worker").apply{isAccessible=true}.get(s) as ExecutorService
   worker.execute{entered.countDown();hold.await(10,TimeUnit.SECONDS)};assertTrue(entered.await(5,TimeUnit.SECONDS))
   val add=find(a.editor!!.findViewWithTag("tool-panel"),"Add")!!
   repeat(25){add.performClick()};hold.countDown();until{s.info.getJSONArray("layers").length()==2};repeat(10){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
   assertEquals(2,s.info.getJSONArray("layers").length())
  }finally{hold.countDown();c.pause().stop().destroy();until{s.fullyClosed && a.io.isTerminated}}
 }
}
