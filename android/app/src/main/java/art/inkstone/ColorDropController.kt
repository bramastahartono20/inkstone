package art.inkstone

import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import kotlin.math.*

internal class ColorDropController(
    private val root:FrameLayout,private val canvas:PaintCanvas,private val session:DrawingSession,
    private val start:()->Unit,private val done:()->Unit,private val notice:(String)->Unit
){
    private val d=root.resources.displayMetrics.density
    private var downX=0f;private var downY=0f;private var x=0f;private var y=0f
    private var anchorX=0f;private var anchorY=0f;private var threshold=.12f
    private var dragging=false;private var filling=false;private var color=0
    private var seed=PointF()
    private var source:View?=null
    private fun restoreSource(){source?.alpha=1f;source?.isPressed=false;source=null}
    private val hold=Runnable{beginFill()}
    private val overlay=object:View(root.context){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){
            if(!dragging)return
            val cursorX=if(filling)anchorX else this@ColorDropController.x;val cursorY=if(filling)anchorY else this@ColorDropController.y
            if(filling){
                val left=width*.24f;val right=width*.76f;val top=canvas.top+12*d
                p.style=Paint.Style.FILL;p.color=0xee171717.toInt();c.drawRoundRect(left-14*d,top,right+14*d,top+57*d,12*d,12*d,p)
                p.color=Color.WHITE;p.textSize=14*d;p.textAlign=Paint.Align.CENTER;c.drawText("ColorDrop Threshold ${(threshold*100).roundToInt()}%",width/2f,top+23*d,p)
                p.color=0xff555555.toInt();c.drawRoundRect(left,top+36*d,right,top+40*d,2*d,2*d,p)
                p.color=StudioColors.accent;c.drawRoundRect(left,top+36*d,left+(right-left)*threshold,top+40*d,2*d,2*d,p)
            }
            p.style=Paint.Style.FILL;p.color=0x44000000
            c.drawCircle(cursorX,cursorY+2*d,18*d,p)
            p.color=color;c.drawCircle(cursorX,cursorY,16*d,p)
            p.style=Paint.Style.STROKE;p.color=Color.WHITE;p.strokeWidth=2*d;c.drawCircle(cursorX,cursorY,16*d,p)
        }
    }.apply{tag="colordrop-drag-preview";isClickable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    init{root.addView(overlay,FrameLayout.LayoutParams(-1,-1))}
    fun bind(control:View,tap:()->Unit){control.setOnTouchListener{_,event->
        val at=IntArray(2);val own=IntArray(2);control.getLocationInWindow(at);root.getLocationInWindow(own)
        x=at[0]-own[0]+event.x;y=at[1]-own[1]+event.y
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{cancel();source=control;downX=x;downY=y;anchorX=x;anchorY=y;color=session.color;control.isPressed=true;control.animate().cancel();control.scaleX=1f;control.scaleY=1f;true}
            MotionEvent.ACTION_MOVE->{
                if(!dragging && hypot(x-downX,y-downY)>9*d){dragging=true;control.alpha=0f;root.parent?.requestDisallowInterceptTouchEvent(true);canvas.onDismissPanel();overlay.bringToFront()}
                if(dragging){
                    if(filling){val next=(.12f+(x-anchorX)/(root.width*.65f)).coerceIn(0f,1f)
                        if(abs(next-threshold)>.008f){threshold=next;preview()}
                    }else if(inside()){
                        if(hypot(x-anchorX,y-anchorY)>5*d){root.removeCallbacks(hold);anchorX=x;anchorY=y;root.postDelayed(hold,220)}
                    }else root.removeCallbacks(hold)
                    overlay.invalidate()
                };true
            }
            MotionEvent.ACTION_UP->{
                control.isPressed=false;root.removeCallbacks(hold)
                if(dragging){if(!filling && inside())beginFill();if(filling){session.finishColorDrop(false){done()};filling=false}else canvas.cancelFillAnimation()}
                else tap()
                dragging=false;restoreSource();overlay.invalidate();true
            }
            MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{control.isPressed=false;cancel();true}
            else->true
        }
    }}
    private fun inside():Boolean {if((root.context as? MainActivity)?.atlas?.editing==false)return false;val p=canvas.colorDropPoint(x-canvas.left,y-canvas.top);return y>=canvas.top && p.x>=0&&p.y>=0&&p.x<session.canvasWidth&&p.y<session.canvasHeight}
    private fun beginFill(){
        if(!dragging || filling || !inside())return
        if(session.info.getJSONArray("layers").getJSONObject(session.info.getInt("active")).optBoolean("locked")){notice("Layer is locked");return}
        filling=true;threshold=.12f;anchorX=x;anchorY=y;seed=canvas.colorDropPoint(x-canvas.left,y-canvas.top)
        start();canvas.prepareFillAnimation(seed);preview();overlay.invalidate()
    }
    private fun preview(){session.previewColorDrop(JSONObject().put("x",seed.x).put("y",seed.y).put("color",color.toLong() and 0xffffffffL).put("threshold",threshold))}
    fun cancel(){root.removeCallbacks(hold);if(filling)session.finishColorDrop(true){done()};filling=false;dragging=false;restoreSource();canvas.cancelFillAnimation();overlay.invalidate()}
}
