package art.inkstone

import android.animation.ValueAnimator
import android.content.ClipData
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ScrollView

internal object LayerDrag {
    data class Token(val owner:Any,val id:Long,val height:Int=0,val width:Int=0)
    private var release:Triple<Any,Long,PointF>?=null
    fun source(view:View,owner:Any,id:Long,name:String){
        view.tooltipText=if(id<0)"Hold and drag to move this group"else"Hold and drag to move this layer"
        view.setOnLongClickListener{
            var row=it
            val key=if(id<0)"group-${-id}"else"layer-$id"
            while(row.parent is View && row.tag!=key)row=row.parent as View
            if(row.tag!=key)row=it
            row.startDragAndDrop(ClipData.newPlainText("Inkstone layer",name),View.DragShadowBuilder(row),Token(owner,id,row.height,row.width),0)
        }
    }
    internal class DropGlow(private val density:Float):Drawable(){
        var join=false;var above=true;var pulse=.5f
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c:Canvas){
            val r=RectF(bounds).apply{inset(4*density,2*density)};val color=StudioColors.accent;p.color=color
            if(join){p.style=Paint.Style.FILL;p.alpha=(22+18*pulse).toInt();c.drawRoundRect(r,StudioShape.CORNER_DP*density,StudioShape.CORNER_DP*density,p)}
            for((width,alpha)in listOf(10f to 18,5f to 60,2f to 230)){
                p.style=Paint.Style.STROKE;p.strokeWidth=width*density;p.alpha=(alpha*(.55f+.45f*pulse)).toInt()
                if(join)c.drawRoundRect(r,StudioShape.CORNER_DP*density,StudioShape.CORNER_DP*density,p)
                else{val y=if(above)r.top else r.bottom;p.strokeCap=Paint.Cap.ROUND;c.drawLine(r.left,y,r.right,y,p)}
            }
        }
        override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:ColorFilter?){};@Deprecated("Drawable opacity")override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    fun target(view:View,owner:Any,folder:Boolean=false,open:Boolean=false,canJoin:(Long)->Boolean={true},drop:(Long,Boolean,Boolean)->Unit){
        var scroll:ScrollView?=null;var speed=0;var pulse:ValueAnimator?=null
        val glow=DropGlow(view.resources.displayMetrics.density)
        val move=object:Runnable{override fun run(){if(speed!=0){scroll?.smoothScrollBy(0,speed);view.postDelayed(this,30)}}}
        fun stop(){speed=0;view.removeCallbacks(move);pulse?.cancel();pulse=null;view.foreground=null}
        fun update(y:Float,id:Long){
            val fraction=y/view.height.coerceAtLeast(1);glow.above=fraction<.5f;glow.join=folder && canJoin(id) && fraction>=.2f && (open || fraction<=.8f);if(folder && !canJoin(id) && fraction>=.2f)glow.above=false
            if(pulse==null){view.foreground=glow;pulse=ValueAnimator.ofFloat(.25f,1f).apply{duration=850;repeatMode=ValueAnimator.REVERSE;repeatCount=ValueAnimator.INFINITE;addUpdateListener{glow.pulse=it.animatedValue as Float;glow.invalidateSelf()};start()}}
            glow.invalidateSelf()
        }
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){};override fun onViewDetachedFromWindow(v:View){stop()}})
        view.setOnDragListener{_,event->
            val token=event.localState as? Token
            if(token==null || token.owner!==owner)false else{
                when(event.action){
                    DragEvent.ACTION_DRAG_ENTERED->update(event.y,token.id)
                    DragEvent.ACTION_DRAG_LOCATION->{
                        update(event.y,token.id);var parent=view.parent;while(parent!=null && parent !is ScrollView)parent=parent.parent;scroll=parent as? ScrollView
                        scroll?.let{s->val a=IntArray(2);val b=IntArray(2);view.getLocationOnScreen(a);s.getLocationOnScreen(b);val y=a[1]+event.y-b[1];val edge=48*view.resources.displayMetrics.density;val next=when{y<edge->-18;y>s.height-edge->18;else->0};if(next!=speed){view.removeCallbacks(move);speed=next;if(speed!=0)view.post(move)}}
                    }
                    DragEvent.ACTION_DROP->{update(event.y,token.id);val at=IntArray(2);view.getLocationOnScreen(at);release=Triple(owner,token.id,PointF(at[0]+event.x-token.width/2f,at[1]+event.y-token.height/2f));val join=if(folder)glow.join else event.x>=64*view.resources.displayMetrics.density;val above=glow.above;stop();drop(token.id,above,join)}
                    DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED->stop()
                };true
            }
        }
    }
    class Motion(private val old:Map<String,PointF>,private val scrollY:Int,private val drop:Pair<Long,PointF>?){
        fun settle(root:View){
            val body=find(root,"layer-list")?:return;val scroll=body.parent as? ScrollView;scroll?.scrollTo(0,scrollY)
            body.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{
                override fun onPreDraw():Boolean{
                if(body.viewTreeObserver.isAlive)body.viewTreeObserver.removeOnPreDrawListener(this)
                scroll?.scrollTo(0,scrollY)
                each(body){v->val key=v.tag as? String?:return@each;val at=IntArray(2);v.getLocationOnScreen(at);val source=key==(drop?.first?.let{if(it<0)"group-${-it}"else"layer-$it"});val from=if(source)drop!!.second.y else old[key]?.y?:return@each
                    v.animate().cancel();v.translationY=from-at[1];v.translationX=(if(source)drop!!.second.x else old[key]?.x?:at[0].toFloat())-at[0];if(source){v.scaleX=1f;v.scaleY=1f;v.alpha=1f}
                    v.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(if(source)380 else 300).setInterpolator(if(source)DecelerateInterpolator(1.5f)else DecelerateInterpolator()).start()
                };return true
                }
            })
        }
    }
    fun capture(root:View,owner:Any):Motion{
        val old=mutableMapOf<String,PointF>();val body=find(root,"layer-list")
        body?.let{each(it){view->val key=view.tag as? String;if(key!=null && (key.startsWith("layer-")||key.startsWith("group-"))){val p=IntArray(2);view.getLocationOnScreen(p);old[key]=PointF(p[0].toFloat(),p[1].toFloat())}}}
        val drop=release?.takeIf{it.first===owner}?.let{it.second to it.third};release=null
        return Motion(old,(body?.parent as? ScrollView)?.scrollY?:0,drop)
    }
    private fun find(v:View,tag:String):View?{if(v.tag==tag)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),tag)?.let{return it};return null}
    private fun each(v:View,run:(View)->Unit){run(v);if(v is ViewGroup)for(i in 0 until v.childCount)each(v.getChildAt(i),run)}
}
