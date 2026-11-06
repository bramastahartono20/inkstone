package art.inkstone

import android.graphics.PointF
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContributionIntegrationTest {
    private fun idle(){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))}
    private fun find(v:View,label:String):View?{if(v.contentDescription==label || v is TextView && v.text.toString()==label)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),label)?.let{return it};return null}
    private fun click(v:View,label:String){assertTrue(checkNotNull(find(v,label)){"Missing $label"}.performClick());idle()}
    private fun layout(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,800);idle()}
    private fun key(a:MainActivity,code:Int,meta:Int=0,repeat:Int=0){a.dispatchKeyEvent(KeyEvent(1,2,KeyEvent.ACTION_DOWN,code,repeat,meta));a.dispatchKeyEvent(KeyEvent(1,3,KeyEvent.ACTION_UP,code,0,meta))}
    private fun close(c:org.robolectric.android.controller.ActivityController<MainActivity>){c.pause().stop().destroy();repeat(200){shadowOf(Looper.getMainLooper()).idle();if(c.get().io.isTerminated)return;Thread.sleep(5)}}
    @Test fun threeToolsRetainFullBrushProfilesIncludingFadeAfterReopen(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val s=a.session!!
        for((i,tool) in listOf("Paint","Smudge","Erase").withIndex()){
            s.tool=tool;s.choose(brushPresets.first{it.name==listOf("Cloud Soft","Blend Medium","Fading Pen")[i]})
            s.size=31f+i;s.opacity=.7f-i*.1f;s.flow=.21f+i*.1f;s.pressureSize=.13f+i*.1f;s.pressureOpacity=.33f+i*.1f;s.fadeLength=170f+i*10
        }
        a.savePreferences();a.newCanvas(80,80);val restored=a.session!!
        for((i,tool) in listOf("Paint","Smudge","Erase").withIndex()){
            restored.tool=tool
            assertEquals(listOf("Cloud Soft","Blend Medium","Fading Pen")[i],restored.preset.name)
            assertEquals(31f+i,restored.size,0f);assertEquals(.7f-i*.1f,restored.opacity,0f)
            assertEquals(.21f+i*.1f,restored.flow,0f);assertEquals(.13f+i*.1f,restored.pressureSize,0f)
            assertEquals(.33f+i*.1f,restored.pressureOpacity,0f);assertEquals(170f+i*10,restored.fadeLength,0f)
        };close(c)
    }
    @Test fun legacySavedPresetRestoresItsOwnFlowAndPressureDefaults(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        a.getSharedPreferences("studio",0).edit().putString("paint_brush_preset","Cloud Soft").apply()
        a.newCanvas(80,80);val s=a.session!!;val p=brushPresets.first{it.name=="Cloud Soft"}
        assertEquals(p.flow,s.flow,0f);assertEquals(p.pressureSize,s.pressureSize,0f);assertEquals(p.pressureOpacity,s.pressureOpacity,0f);close(c)
    }
    @Test fun shortcutToggleUsesIndependentRasterAndVectorToolsAndIgnoresRepeats(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val s=a.session!!
        s.tool="Smudge";key(a,KeyEvent.KEYCODE_E);assertEquals("Erase",s.tool)
        key(a,KeyEvent.KEYCODE_E,repeat=1);assertEquals("Erase",s.tool)
        key(a,KeyEvent.KEYCODE_E);assertEquals("Smudge",s.tool)
        a.newCanvas(80,80,mode="vector");val e=a.vectorEditor!!;e.canvas.changeTool("Nodes")
        key(a,KeyEvent.KEYCODE_E);assertEquals("Select",e.canvas.tool)
        key(a,KeyEvent.KEYCODE_E);assertEquals("Nodes",e.canvas.tool);close(c)
    }
    @Test fun vectorDialogRecordsModifierShortcutAndResetRestoresDefault(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(80,80,mode="vector");val e=a.vectorEditor!!
        click(e,"Actions");click(e,"Shortcut keys");val dialog=ShadowAlertDialog.getLatestAlertDialog()
        val decor=dialog.window!!.decorView;click(decor,"Set")
        dialog.dispatchKeyEvent(KeyEvent(1,2,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_Q,0,KeyEvent.META_CTRL_ON))
        assertNull(a.recordingShortcutAction);assertEquals(KeyShortcut(KeyEvent.KEYCODE_Q,true,false,false),a.getShortcut("undo"))
        click(decor,"Reset");assertEquals(KeyShortcut(KeyEvent.KEYCODE_Z,true,false,false),a.getShortcut("undo"))
        click(decor,"Set");dialog.dismiss();idle();assertNull(a.recordingShortcutAction);close(c)
    }
    @Test fun switchingRightPanelToActionsKeepsLeftAlignmentAndSettingsStayInPlace(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val e=c.get().editor!!;layout(e)
        click(e,"Paint");click(e,"Actions");layout(e)
        val panel=find(e,"Actions")!!.let {
            fun heading(v:View):View?{if(v is TextView && v.text.toString()=="Actions")return v;if(v is ViewGroup)for(i in 0 until v.childCount)heading(v.getChildAt(i))?.let{return it};return null}
            heading(e)!!.parent.parent as View
        }
        assertEquals(android.view.Gravity.LEFT,(panel.layoutParams as FrameLayout.LayoutParams).gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        click(e,"Prefs");click(e,"Gesture controls");assertNotNull(find(e,"Stylus button"));assertNull(find(e,"Paint with finger"));close(c)
    }
    @Test fun interruptingFitAnimationDoesNotSnapCanvasToFinalFit(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val e=c.get().editor!!;layout(e);val canvas=e.canvas
        canvas.zoomBy(2f);val before=canvas.transformViewPoint(100f,100f)
        canvas.animateFit();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        val during=canvas.transformViewPoint(100f,100f);val event=MotionEvent.obtain(1,2,MotionEvent.ACTION_DOWN,200f,200f,0);canvas.onTouchEvent(event);event.recycle()
        val stopped=canvas.transformViewPoint(100f,100f)
        assertEquals(during.x,stopped.x,.01f);assertEquals(during.y,stopped.y,.01f)
        assertTrue(before!=PointF());val cancel=MotionEvent.obtain(1,3,MotionEvent.ACTION_CANCEL,200f,200f,0);canvas.onTouchEvent(cancel);cancel.recycle();close(c)
    }
    @Test fun quickPinchFitsOnlyWhenEnabledAndUnlocked(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val e=a.editor!!;layout(e);val canvas=e.canvas;val session=e.session;session.penOnly=true
        canvas.fit();val fit=canvas.transformViewPoint(100f,100f)
        fun gesture(){
            fun send(action:Int,n:Int,distance:Float){
                val properties=Array(n){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                val coords=Array(n){i->MotionEvent.PointerCoords().apply{x=600f+(if(i==0)-1 else 1)*distance/2;y=350f;pressure=1f}}
                val now=android.os.SystemClock.uptimeMillis();val event=MotionEvent.obtain(now,now,action,n,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                canvas.onTouchEvent(event);event.recycle()
            }
            send(MotionEvent.ACTION_DOWN,1,500f);send(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),2,500f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(80));send(MotionEvent.ACTION_MOVE,2,150f)
            send(MotionEvent.ACTION_POINTER_UP or (1 shl 8),2,150f);send(MotionEvent.ACTION_UP,1,150f);idle()
        }
        canvas.zoomBy(2f);gesture();val actual=canvas.transformViewPoint(100f,100f);assertEquals(fit.x,actual.x,.01f);assertEquals(fit.y,actual.y,.01f)
        canvas.zoomBy(2f);session.pinchToFit=false;gesture();assertNotEquals(fit.x,canvas.transformViewPoint(100f,100f).x,.01f)
        canvas.fit();canvas.zoomBy(2f);val locked=canvas.transformViewPoint(100f,100f);session.pinchToFit=true;session.canvasLocked=true
        gesture();assertEquals(locked.x,canvas.transformViewPoint(100f,100f).x,.01f);close(c)
    }

    @Test fun reusedPanelsKeepTheirOwnWidthAcrossLibraryLayerAndSettingsSwitches(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val e=c.get().editor!!;layout(e)
        fun awaitPanel(key:String):View{val end=System.nanoTime()+20_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();val view=e.findViewWithTag<View>("tool-panel");if(e.panelKey==key && view!=null)return view;Thread.sleep(10)};error("Missing panel $key")}
        click(e,"Layers");val panel=awaitPanel("Layers");layout(e);assertEquals(350,panel.width)
        for((tool,width) in listOf("Paint" to 450,"Erase" to 450,"Layers" to 350,"Smudge" to 450,"Color" to 350,"Paint" to 450,"Layers" to 350)){
            click(e,tool);awaitPanel(if(tool=="Layers")"Layers"else if(tool=="Color")"Colors"else"Brushes");layout(e);assertSame(panel,e.findViewWithTag<View>("tool-panel"));assertEquals("Wrong width after $tool",width,panel.width);assertEquals(12,(panel.layoutParams as FrameLayout.LayoutParams).rightMargin)
        }
        click(e,"Actions");val left=awaitPanel("Actions");layout(e);assertEquals(390,left.width)
        click(e,"Prefs");click(e,"Pressure and Smoothing");layout(e);assertSame(left,e.findViewWithTag<View>("tool-panel"));assertEquals(android.view.Gravity.LEFT,(left.layoutParams as FrameLayout.LayoutParams).gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        close(c)
    }

}
