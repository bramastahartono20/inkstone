package art.inkstone

import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PerformanceRegressionTest {
    private fun until(test:()->Boolean){val end=System.nanoTime()+25_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Performance regression check timed out")}

    @Test fun nativeShapeResizeKeepsDamageLocalAndCancelExact(){
        val h=NativeBridge.create(512,512)
        try{
            NativeBridge.renderPatch(h,true);val before=NativeBridge.save(h)
            fun shape(x:Float){NativeBridge.stroke(h,4,floatArrayOf(x,30f,1f,0f,x+40f,60f,1f,0f),0,0xff3070bb.toInt(),6f,1f,1f,0f,0f,0f)}
            shape(20f);NativeBridge.renderPatch(h,false)
            repeat(60){shape(20f+it);val p=checkNotNull(NativeBridge.renderPatch(h,false));assertTrue(p[2]*p[3]<512*512/8)}
            assertEquals(0,JSONObject(NativeBridge.command(h,"{\"op\":\"info\"}")).getInt("undo"))
            NativeBridge.stroke(h,3,floatArrayOf(),0,0,6f,1f,1f,0f,0f,0f)
            assertArrayEquals(before,NativeBridge.save(h))
        }finally{NativeBridge.destroy(h)}
    }

    @Test fun replacedLayerThumbnailsReleaseTheirBitmapMemory(){
        val s=DrawingSession(64,64)
        try{
            var ready=false;s.thumbnails{ready=true};until{ready}
            val previous=s.layerThumbnails.toList();assertTrue(previous.isNotEmpty())
            ready=false;s.thumbnails{ready=true};until{ready}
            assertTrue(previous.all{it.isRecycled});assertTrue(s.layerThumbnails.all{!it.isRecycled})
        }finally{s.close();until{s.fullyClosed}}
    }

    @Test fun burstOfShapeResizesCommitsLatestPositionInOneUndo(){
        val s=DrawingSession(256,128)
        try{
            s.size=4f;s.pressureSize=0f;s.stabilization=0f;s.color=0xffff0000.toInt()
            repeat(80){val x=20f+it*2;s.previewQuickShape(floatArrayOf(x,40f,1f,0f,x,90f,1f,0f))}
            s.stroke(2,floatArrayOf());until{s.info.optInt("undo")==1}
            var picked:JSONObject?=null;s.command("pick",JSONObject().put("x",178).put("y",60)){picked=it};until{picked!=null}
            assertEquals(0xffff0000.toInt(),picked!!.getLong("color").toInt());assertEquals("Shape",s.info.getString("undoAction"))
        }finally{s.close();until{s.fullyClosed}}
    }
}
