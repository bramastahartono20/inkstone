package art.inkstone

import android.app.AlertDialog
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class Preview019Test{
    private fun until(test:()->Boolean){val end=System.nanoTime()+25_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Preview check timed out")}
    private fun named(v:View,s:String):View?{if(v.contentDescription==s || v is TextView && v.text.toString()==s)return v;if(v is ViewGroup)for(i in 0 until v.childCount)named(v.getChildAt(i),s)?.let{return it};return null}
    private inline fun <reified T:View> find(v:View):T?{return descend(v){it is T} as? T}
    private fun descend(v:View,test:(View)->Boolean):View?{if(test(v))return v;if(v is ViewGroup)for(i in 0 until v.childCount)descend(v.getChildAt(i),test)?.let{return it};return null}
    private fun click(v:View,s:String){assertTrue(checkNotNull(named(v,s)){"Missing $s"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun layout(v:View,name:String){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));v.rootView.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.rootView.layout(0,0,1280,800);v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,800);val b=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getProperty("inkstone.artifacts"),"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun input(v:View,action:Int,p:PointF,button:Int=0,tool:Int=MotionEvent.TOOL_TYPE_STYLUS){
        val props=MotionEvent.PointerProperties().apply{id=0;toolType=tool};val point=MotionEvent.PointerCoords().apply{x=p.x;y=p.y;pressure=1f;size=.05f}
        val time=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(time,time,action,1,arrayOf(props),arrayOf(point),0,button,1f,1f,0,0,if(tool==MotionEvent.TOOL_TYPE_STYLUS)InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN,0);v.dispatchTouchEvent(e);e.recycle()
    }
    private fun pick(s:DrawingSession,x:Int,y:Int):Int{var result:JSONObject?=null;s.command("pick",JSONObject().put("x",x).put("y",y)){result=it};until{result!=null};return result!!.getLong("color").toInt()}
    private fun done(c:org.robolectric.android.controller.ActivityController<MainActivity>){val s=c.get().session;c.pause().stop().destroy();until{s==null || s.fullyClosed};until{c.get().io.isTerminated}}
    @Test fun vectorWheelAndHexUpdateLiveInkThenColorDropUsesThatColor(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,mode="vector");val e=a.vectorEditor!!;val canvas=e.canvas;val doc=a.vectorDocument!!
        canvas.edit{doc.layers[0].objects.add(VectorObject(doc.nextId(),"Rectangle",mutableListOf(PointF(60f,50f),PointF(260f,190f)),Color.BLACK,null,4f))}
        layout(e,"preview019-vector-before");click(e,"Colors");layout(e,"preview019-vector-picker")
        val picker=checkNotNull(find<ColorDisc>(e));val old=canvas.ink
        input(picker,MotionEvent.ACTION_DOWN,PointF(picker.width*.62f,picker.height*.37f));input(picker,MotionEvent.ACTION_UP,PointF(picker.width*.62f,picker.height*.37f))
        assertNotEquals(old,canvas.ink);val hex=checkNotNull(named(e,"Vector color hex")) as EditText;assertEquals(String.format("#%08X",canvas.ink),hex.text.toString())
        hex.setText("#FF2277CC");assertEquals(0xff2277cc.toInt(),canvas.ink);assertEquals(canvas.ink,(named(e,"Vector active color preview")!!.background as android.graphics.drawable.ColorDrawable).color)
        assertEquals(canvas.ink,(named(e,"Colors") as ToolIcon).swatch)
        e.dismissPanel();layout(e,"preview019-vector-live-ink")
        val control=named(e,"Colors")!!;val at=IntArray(2);val own=IntArray(2);control.getLocationInWindow(at);e.getLocationInWindow(own)
        val start=PointF(at[0]-own[0]+control.width/2f,at[1]-own[1]+control.height/2f)
        val end=canvas.viewPoint(160f,120f).apply{offset(canvas.left.toFloat(),canvas.top.toFloat())}
        input(e,MotionEvent.ACTION_DOWN,start);input(e,MotionEvent.ACTION_MOVE,end);input(e,MotionEvent.ACTION_UP,end)
        assertEquals(0xff2277cc.toInt(),doc.layers[0].objects[0].fill);layout(e,"preview019-vector-filled")
        canvas.undo();assertNull(doc.layers[0].objects[0].fill);canvas.redo();assertEquals(0xff2277cc.toInt(),doc.layers[0].objects[0].fill);done(c)
    }
    @Test fun pressingBarrelButtonMidStrokeUsesWideEraserAndKeepsOneUndo(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val s=e.session
        s.command("fill",JSONObject().put("color",0xffff0000L));until{s.info.optInt("undo")==1}
        s.tool="Erase";s.choose(brushPresets.first{it.name=="Ink Pen"});s.size=40f;s.pressureSize=0f;s.flow=1f;s.eraserHardness=1f;s.opacity=1f
        s.tool="Paint";s.size=4f;s.pressureSize=0f;s.color=Color.BLUE;s.stabilization=0f;s.penOnly=true
        layout(e,"preview019-stylus-before")
        fun point(x:Float,y:Float)=e.canvas.transformViewPoint(x,y).apply{offset(e.canvas.left.toFloat(),e.canvas.top.toFloat())}
        input(e,MotionEvent.ACTION_DOWN,point(60f,120f));input(e,MotionEvent.ACTION_MOVE,point(100f,120f),MotionEvent.BUTTON_STYLUS_PRIMARY);input(e,MotionEvent.ACTION_MOVE,point(180f,120f),MotionEvent.BUTTON_STYLUS_PRIMARY);input(e,MotionEvent.ACTION_UP,point(180f,120f))
        assertEquals(0,Color.alpha(pick(s,140,132)));assertEquals(Color.RED,pick(s,140,149));assertEquals(2,s.info.optInt("undo"));assertEquals("Paint",s.tool);assertEquals(4f,s.size,0f)
        layout(e,"preview019-stylus-erased");s.command("undo");assertEquals(Color.RED,pick(s,140,132));assertEquals(1,s.info.optInt("undo"));done(c)
    }
    @Test fun keyBindingsTriggerOnceAndPersistAndMouseButtonsAreIgnored(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val s=a.session!!
        s.command("fill",JSONObject().put("color",0xff112233L));s.command("fill",JSONObject().put("color",0xff334455L));until{s.info.optInt("undo")==2};s.stylusButtonAction=StylusAction.UNDO
        fun key(action:Int,repeat:Int=0){val now=android.os.SystemClock.uptimeMillis();assertTrue(a.dispatchKeyEvent(KeyEvent(now,now,action,KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,repeat,0,0,0,0,InputDevice.SOURCE_STYLUS)))}
        key(KeyEvent.ACTION_DOWN);key(KeyEvent.ACTION_DOWN,1);assertEquals(0xff112233.toInt(),pick(s,20,20));assertEquals(1,s.info.optInt("undo"));key(KeyEvent.ACTION_UP)
        s.stylusButtonAction=StylusAction.REDO;key(KeyEvent.ACTION_DOWN);key(KeyEvent.ACTION_UP);assertEquals(0xff334455.toInt(),pick(s,20,20))
        a.savePreferences();a.newCanvas(64,64);assertEquals(StylusAction.REDO,a.session!!.stylusButtonAction)
        var count=0;val buttons=StylusButtons{count++};val props=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_MOUSE};val coords=MotionEvent.PointerCoords().apply{x=1f;y=1f}
        val mouse=MotionEvent.obtain(1,1,MotionEvent.ACTION_MOVE,1,arrayOf(props),arrayOf(coords),0,MotionEvent.BUTTON_SECONDARY,1f,1f,0,0,InputDevice.SOURCE_MOUSE,0);buttons.observe(mouse);mouse.recycle();assertFalse(buttons.down);assertEquals(0,count)
        done(c)
    }
    @Test fun stylusFunctionChooserAndExplicitEyedropperAreAvailable(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val e=c.get().editor!!;click(e,"Actions");click(e,"Prefs");click(e,"Gesture controls");click(e,"Stylus button")
        val dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.listView.performItemClick(null,StylusAction.EYEDROPPER.ordinal,StylusAction.EYEDROPPER.ordinal.toLong());assertEquals(StylusAction.EYEDROPPER,e.session.stylusButtonAction)
        layout(e,"preview019-stylus-settings");e.dismissPanel();click(e,"Color");click(e,"Eyedropper");assertEquals("Eyedropper",e.session.tool);done(c)
    }
    @Test fun stylusMotionAliasesAndKeyReleaseDoNotLeaveTheButtonStuck(){
        var presses=0;val buttons=StylusButtons{presses++}
        fun motion(mask:Int){val props=MotionEvent.PointerProperties().apply{id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS};val coords=MotionEvent.PointerCoords();val e=MotionEvent.obtain(1,1,MotionEvent.ACTION_HOVER_MOVE,1,arrayOf(props),arrayOf(coords),0,mask,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0);buttons.observe(e);e.recycle()}
        motion(MotionEvent.BUTTON_SECONDARY);assertTrue(buttons.down);motion(MotionEvent.BUTTON_SECONDARY);assertEquals(1,presses);motion(0);assertFalse(buttons.down)
        motion(MotionEvent.BUTTON_STYLUS_SECONDARY);assertTrue(buttons.down);assertEquals(2,presses)
        val down=KeyEvent(1,1,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,0);buttons.key(down);assertEquals(2,presses)
        buttons.key(KeyEvent(1,1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,0));assertFalse(buttons.down)
        motion(MotionEvent.BUTTON_STYLUS_PRIMARY);assertEquals(3,presses);buttons.reset();assertFalse(buttons.down)
    }
    @Test fun penOnlyIsTheOnlyPaintingGateAndLegacyFingerSettingCannotBlockDrawing(){
        val app=org.robolectric.RuntimeEnvironment.getApplication();app.getSharedPreferences("studio",0).edit().putBoolean("finger",false).putBoolean("penOnly",false).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val s=e.session
        assertFalse(a.getSharedPreferences("studio",0).contains("finger"));s.color=Color.RED;s.size=20f;s.pressureSize=0f;s.stabilization=0f
        layout(e,"preview019-pen-only-before")
        fun finger(x:Float,y:Float){val p=e.canvas.transformViewPoint(x,y).apply{offset(e.canvas.left.toFloat(),e.canvas.top.toFloat())};input(e,MotionEvent.ACTION_DOWN,p,tool=MotionEvent.TOOL_TYPE_FINGER);input(e,MotionEvent.ACTION_UP,p,tool=MotionEvent.TOOL_TYPE_FINGER)}
        finger(80f,100f);assertEquals(Color.RED,pick(s,80,100));s.penOnly=true;finger(160f,100f);assertEquals(0,Color.alpha(pick(s,160,100)))
        s.penOnly=false;finger(240f,100f);assertEquals(Color.RED,pick(s,240,100))
        click(e,"Actions");click(e,"Prefs");click(e,"Gesture controls");assertNotNull(named(e,"Pen-only input"));assertNull(named(e,"Paint with finger"));done(c)
    }
    @Test fun fadePresetReachesNativeEngineAndResetsOnNextStroke(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(640,160,visible=false);val s=a.session!!
        s.choose(brushPresets.first{it.name=="Fading Pen"});s.fadeLength=300f;s.stabilization=0f;s.color=Color.BLUE
        s.stroke(0,floatArrayOf(20f,60f,1f,0f));s.stroke(2,floatArrayOf(620f,60f,1f,0f));assertTrue(Color.alpha(pick(s,35,60))>0);assertTrue(Color.alpha(pick(s,400,60))>0);assertTrue(Color.alpha(pick(s,600,60))<Color.alpha(pick(s,35,60)))
        s.stroke(0,floatArrayOf(20f,120f,1f,0f));s.stroke(2,floatArrayOf(620f,120f,1f,0f));assertTrue(Color.alpha(pick(s,35,120))>0)
        layout(a.editor!!,"preview019-fade-strokes");done(c)
    }
}
