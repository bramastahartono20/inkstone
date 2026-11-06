package art.inkstone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.Gravity
import android.widget.TextView
import kotlin.math.ceil

internal class GalleryButton(context:Context,action:()->Unit):TextView(context){
    private val d=resources.displayMetrics.density
    private var glow:ValueAnimator?=null
    internal var penHovered=false;private set
    val requiredWidth get()=ceil(paint.measureText(text.toString())+paddingLeft+paddingRight).toInt().coerceAtLeast((84*d).toInt())
    init{text="Home";contentDescription="Home";tooltipText="Save artwork and return Home";textSize=17f;typeface=Typeface.create("sans-serif",Typeface.NORMAL);gravity=Gravity.CENTER;setSingleLine(true);setPadding((12*d).toInt(),0,(12*d).toInt(),0);minimumHeight=(44*d).toInt();isClickable=true;isFocusable=true;setTextColor(StudioTheme.of(context).text);setOnClickListener{action()}}
    override fun onHoverEvent(event:MotionEvent):Boolean{
        val target=event.actionMasked!=MotionEvent.ACTION_HOVER_EXIT && event.getToolType(0) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)
        if(target!=penHovered){penHovered=target;glow?.cancel();val from=shadowRadius/d;val to=if(target)8f else 0f
            if(!ValueAnimator.areAnimatorsEnabled())setShadowLayer(to*d,0f,0f,StudioColors.accent)
            else glow=ValueAnimator.ofFloat(from,to).apply{duration=160;addUpdateListener{setShadowLayer((it.animatedValue as Float)*d,0f,0f,StudioColors.accent);invalidate()};start()}}
        return super.onHoverEvent(event)
    }
    override fun onDetachedFromWindow(){glow?.cancel();penHovered=false;setShadowLayer(0f,0f,0f,Color.TRANSPARENT);super.onDetachedFromWindow()}
}
