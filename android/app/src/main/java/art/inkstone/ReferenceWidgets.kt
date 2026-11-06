package art.inkstone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch
import kotlin.math.*

internal class PopoverBackground(private val density:Float, var anchor:Float,private val light:Boolean=false):Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline=Path()
    override fun onBoundsChange(bounds:Rect){
        val d=density;val w=bounds.width().toFloat();val h=bounds.height().toFloat();val top=10*d;val r=StudioShape.CORNER_DP*d
        val a=(if(light)w-42*d else anchor).coerceIn(20*d,w-20*d)
        outline.reset();outline.moveTo(r,top);outline.lineTo(a-10*d,top);outline.lineTo(a,1*d);outline.lineTo(a+10*d,top)
        outline.lineTo(w-r,top);outline.quadTo(w,top,w,top+r);outline.lineTo(w,h-r);outline.quadTo(w,h,w-r,h)
        outline.lineTo(r,h);outline.quadTo(0f,h,0f,h-r);outline.lineTo(0f,top+r);outline.quadTo(0f,top,r,top);outline.close()
        p.shader=LinearGradient(0f,top,0f,h,if(light)intArrayOf(0xffe9e9e9.toInt(),0xffe5e5e5.toInt()) else intArrayOf(0xff363d40.toInt(),StudioColors.track),null,Shader.TileMode.CLAMP)
    }
    override fun draw(canvas:Canvas){p.style=Paint.Style.FILL;canvas.drawPath(outline,p);val shader=p.shader;p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=density;p.color=if(light)0xffdedede.toInt() else 0xff596164.toInt();canvas.drawPath(outline,p);p.shader=shader}
    override fun setAlpha(alpha:Int){p.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){p.colorFilter=filter}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

internal class StudioSwitch(context:Context,label:String,initial:Boolean,private val changed:(Boolean)->Unit):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var checked=initial
    private var amount=if(initial)1f else 0f
    private var animator:ValueAnimator?=null
    init{isClickable=true;isFocusable=true;contentDescription=label;setOnClickListener{
        checked=!checked;changed(checked);animator?.cancel()
        val end=if(checked)1f else 0f
        if(!ValueAnimator.areAnimatorsEnabled()){amount=end;invalidate()}else{
            animator=ValueAnimator.ofFloat(amount,end).apply{duration=170;addUpdateListener{amount=it.animatedValue as Float;invalidate()};start()}
        }
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }}
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density;val w=48*d;val h=28*d;val x=(width-w)/2;val y=(height-h)/2
        val on=StudioColors.accent;p.color=Color.rgb((86+(Color.red(on)-86)*amount).toInt(),(93+(Color.green(on)-93)*amount).toInt(),(97+(Color.blue(on)-97)*amount).toInt());c.drawRoundRect(x,y,x+w,y+h,h/2,h/2,p)
        p.color=0xffd5dadd.toInt();c.drawCircle(x+14*d+20*d*amount,y+h/2,12*d,p)
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className=Switch::class.java.name;info.isCheckable=true;info.isChecked=checked}
    override fun onDetachedFromWindow(){animator?.cancel();super.onDetachedFromWindow()}
}

internal class BrushHud(context:Context):View(context){
    var value=.5f
    var opacityMode=false
    var labelOverride:String?=null
    var soft=false
    var mark:(()->Unit)?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{isClickable=true;contentDescription="Brush preview. Tap to remember this slider position.";setOnClickListener{mark?.invoke()}}
    override fun onDraw(c:Canvas){
        val d=resources.displayMetrics.density;val w=width.toFloat();val h=height.toFloat()
        p.shader=null;p.style=Paint.Style.FILL;p.color=StudioTheme.of(context).glass;c.drawRoundRect(0f,0f,w,h,StudioShape.CORNER_DP*d,StudioShape.CORNER_DP*d,p)
        p.style=Paint.Style.STROKE;p.color=StudioTheme.of(context).border;p.strokeWidth=d;c.drawRoundRect(d/2,d/2,w-d/2,h-d/2,StudioShape.CORNER_DP*d,StudioShape.CORNER_DP*d,p)
        p.style=Paint.Style.FILL;p.color=StudioTheme.of(context).text;p.textSize=14*d;p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        c.drawText("${labelOverride?:if(opacityMode)"Opacity" else "Size"} ${(value*100).roundToInt()}%",12*d,23*d,p)
        p.strokeWidth=1.6f*d;c.drawLine(w-25*d,17*d,w-11*d,17*d,p);c.drawLine(w-18*d,10*d,w-18*d,24*d,p)
        val radius=if(opacityMode)w*.3f else (8+value*54)*d
        val cx=w/2;val cy=h*.59f
        if(soft)p.shader=RadialGradient(cx,cy,radius.coerceAtLeast(1f),intArrayOf(0xffdce7e9.toInt(),0x00dce7e9),null,Shader.TileMode.CLAMP)
        p.color=if(StudioTheme.of(context).light)0xff536b80.toInt() else 0xffdce7e9.toInt();p.alpha=if(opacityMode)(value*255).toInt() else 255;c.drawCircle(cx,cy,radius,p);p.alpha=255;p.shader=null
    }
}

internal class CategoryIcon(context:Context,private val name:String,var chosen:Boolean):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    override fun onDraw(c:Canvas){
        c.save();c.translate(width/2f,height/2f);val scale=resources.displayMetrics.density;c.scale(scale,scale)
        p.color=if(chosen)Color.WHITE else StudioTheme.of(context).secondary;p.style=Paint.Style.STROKE;p.strokeWidth=1.7f;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        path.reset()
        when(name){
            "Recent"->{for(i in 0..9){val a=(i*PI/5-PI/2);val r=if(i%2==0)8f else 3.5f;val x=cos(a).toFloat()*r;val y=sin(a).toFloat()*r;if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};path.close();p.style=Paint.Style.FILL;c.drawPath(path,p)}
            "Pencils","Pens","Airbrush"->{path.moveTo(0f,-9f);path.lineTo(-5f,6f);path.quadTo(0f,11f,5f,6f);path.close();if(name=="Airbrush")p.style=Paint.Style.FILL;c.drawPath(path,p);c.drawLine(0f,-2f,0f,5f,p)}
            "Lines","Lettering"->{path.moveTo(-7f,1f);path.cubicTo(8f,-15f,12f,0f,-2f,4f);path.cubicTo(-9f,11f,3f,11f,7f,4f);c.drawPath(path,p)}
            "Paints"->{c.drawRoundRect(-4f,-8f,4f,2f,1f,1f,p);c.drawLine(0f,2f,0f,10f,p);c.drawLine(-3f,-4f,3f,-4f,p)}
            "Charcoal","Grit"->{for(i in -1..1){c.drawLine(i*4f,-7f,i*4f,7f,p)}}
            "Texture","Surfaces"->{c.drawRoundRect(-7f,-7f,7f,7f,2f,2f,p);for(i in -1..1)c.drawLine(-5f,i*4f+3,5f,i*4f-3,p)}
            "Shapes"->{path.moveTo(0f,-8f);path.lineTo(-8f,7f);path.lineTo(8f,7f);path.close();c.drawPath(path,p);c.drawCircle(0f,3f,2f,p)}
            "Expressive"->{path.moveTo(5f,6f);path.cubicTo(-5f,14f,-13f,-3f,-4f,-7f);path.cubicTo(4f,-11f,13f,0f,7f,1f);path.quadTo(1f,0f,5f,6f);c.drawPath(path,p);c.drawCircle(-4f,-3f,1f,p);c.drawCircle(1f,-5f,1f,p);c.drawCircle(-5f,2f,1f,p)}
            "Nature"->{c.drawCircle(0f,0f,8f,p);c.drawOval(-3f,-8f,3f,8f,p);c.drawLine(-7f,-3f,7f,-3f,p);c.drawLine(-7f,3f,7f,3f,p)}
            "Spray"->{c.drawRoundRect(-5f,-3f,5f,9f,2f,2f,p);c.drawRect(-2f,-6f,2f,-3f,p);c.drawLine(0f,-9f,4f,-9f,p);c.drawCircle(7f,-9f,.6f,p)}
            "Retro"->{c.drawLine(-6f,-8f,6f,-8f,p);c.drawLine(-6f,8f,6f,8f,p);path.moveTo(-5f,-8f);path.cubicTo(-5f,0f,5f,0f,5f,8f);path.moveTo(5f,-8f);path.cubicTo(5f,0f,-5f,0f,-5f,8f);c.drawPath(path,p)}
            "Glow"->{path.moveTo(0f,-10f);path.quadTo(1f,-1f,9f,0f);path.quadTo(1f,1f,0f,10f);path.quadTo(-1f,1f,-9f,0f);path.quadTo(-1f,-1f,0f,-10f);c.drawPath(path,p)}
            "Botanical"->{path.moveTo(-6f,7f);path.cubicTo(-10f,-5f,2f,-8f,8f,-8f);path.cubicTo(9f,1f,4f,10f,-6f,7f);c.drawPath(path,p);c.drawLine(-7f,9f,4f,-3f,p)}
            "Watercolour"->{path.moveTo(0f,-9f);path.cubicTo(-4f,-1f,-8f,1f,-6f,6f);path.cubicTo(-3f,11f,6f,10f,6f,4f);path.quadTo(6f,1f,0f,-9f);c.drawPath(path,p)}
            "Imported"->{path.moveTo(-8f,7f);path.lineTo(-8f,-6f);path.lineTo(-1f,-6f);path.lineTo(2f,-3f);path.lineTo(8f,-3f);path.lineTo(8f,7f);path.close();c.drawPath(path,p)}
            else->{c.drawCircle(0f,0f,8f,p);c.drawCircle(-3f,-3f,1f,p);c.drawCircle(3f,2f,2f,p);c.drawLine(-4f,5f,3f,-5f,p)}
        };c.restore()
    }
}
