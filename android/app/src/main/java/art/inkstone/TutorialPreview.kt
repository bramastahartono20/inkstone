package art.inkstone

import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import kotlin.math.*

/** Actual editor captures, with gesture overlays and a camera focused on the current action. */
internal class TutorialPreview(private val activity:MainActivity,private val topic:Int,private val step:Int):View(activity){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var phase=0f
    private var snapshot:Bitmap?=null
    private var point:PointF?=null
    private val theme=if(StudioTheme.of(activity).light)"light"else"dark"
    private val frames=object:LinkedHashMap<String,Bitmap>(4,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Bitmap>):Boolean{if(size<=4)return false;if(eldest.value!==snapshot)eldest.value.recycle();return true}
    }
    private val metadata by lazy{activity.assets.open("tutorials/$theme/targets.json").bufferedReader().use{JSONObject(it.readText())}}
    private val animation=ValueAnimator.ofFloat(0f,1f).apply{duration=6400;repeatCount=ValueAnimator.INFINITE;interpolator=android.view.animation.LinearInterpolator();addUpdateListener{phase=it.animatedValue as Float;invalidate()}}
    internal fun showPhase(value:Float){animation.cancel();phase=value.coerceIn(0f,1f);invalidate()}
    init{contentDescription="Animated workspace guide: ${Tutorials.lessons[topic].steps[step].title}";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(ValueAnimator.areAnimatorsEnabled())animation.start()else phase=.7f}
    override fun onDetachedFromWindow(){animation.cancel();frames.values.forEach{it.recycle()};frames.clear();snapshot=null;point=null;super.onDetachedFromWindow()}
    internal fun target():String=when(topic){
        0->when(step){0->"Paint";1->"Brush size";2->"";else->"Undo"}
        1->if(step<2)"Color" else "Paint Mixing"
        2->"Layers"
        3->if(step==2)"Transform" else "Actions"
        4->"Actions"
        else->if(step==0)"Save artwork" else "Actions"
    }
    internal fun findTarget():View?{
        fun find(v:View):View?{if(v.contentDescription?.toString()==target())return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
        return if(target().isEmpty())null else find(activity.findViewById(android.R.id.content))
    }
    internal fun spotlight(){
        val control=findTarget()?:return
        val host=activity.findViewById<ViewGroup>(android.R.id.content)
        val pulse=object:View(activity){
            val p=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c:Canvas){
                val a=IntArray(2);val b=IntArray(2);control.getLocationInWindow(a);getLocationInWindow(b)
                p.color=StudioColors.accent;p.style=Paint.Style.STROKE;p.strokeWidth=3*resources.displayMetrics.density
                c.drawRoundRect(RectF((a[0]-b[0]-6).toFloat(),(a[1]-b[1]-6).toFloat(),(a[0]-b[0]+control.width+6).toFloat(),(a[1]-b[1]+control.height+6).toFloat()),12f,12f,p)
            }
        }.apply{contentDescription="Tutorial highlighted ${target()}";isClickable=false}
        host.addView(pulse,ViewGroup.LayoutParams(-1,-1))
        pulse.animate().alpha(0f).setStartDelay(1300).setDuration(1000).withEndAction{host.removeView(pulse)}.start()
    }
    private fun image(name:String):Bitmap=frames.getOrPut(name){activity.assets.open("tutorials/$theme/$name.png").use{checkNotNull(BitmapFactory.decodeStream(it))}}
    private fun at(scene:String,key:String,fallback:PointF=PointF(650f,400f)):PointF {
        val a=metadata.optJSONObject(scene)?.optJSONArray(key)?:return fallback
        return PointF(a.getDouble(0).toFloat(),a.getDouble(1).toFloat())
    }
    private fun t(start:Float,end:Float)=((phase-start)/(end-start)).coerceIn(0f,1f).let{it*it*(3f-2f*it)}
    private fun between(a:PointF,b:PointF,v:Float)=PointF(a.x+(b.x-a.x)*v,a.y+(b.y-a.y)*v)
    private fun menu():String=when(topic){0->if(step==0)"brushes"else"workspace";1->when(step){0->"colors";1->"workspace";else->"mixing"};2->listOf("layers","groups","group-blend")[step];3->if(step==2)"transform"else"shape";4->if(step==0)"atlas"else"atlas-active";else->if(step==0)"share"else"video"}
    private fun gesture()=when{topic==0 && step==1->"slider";topic==0 && step==2->"pinch";topic==0 && step==3->"undo";topic==1 && step==1->"drop";topic==1 && step==3->"mix";topic==2 && step==1->"layer";topic==3 && step==0->"hold";topic==3 && step>0->"resize";topic==4->"atlas";topic==5 && step==2->"export";else->"menu"}
    override fun onDraw(c:Canvas){
        if(width<=0 || height<=0)return
        snapshot=image("workspace");point=at("workspace",target())
        val action=gesture();val menu=menu();val open=phase>.2f
        var scene=if(open)menu else "workspace"
        if(action=="drop" || action=="pinch" || action=="undo" || action=="slider" || action=="mix")scene="workspace"
        if(topic==5 && step==1 && phase<.4f && open)scene="video-off"
        if(action=="export" && phase>.45f)scene="video-minute"
        if(action=="export" && phase>.65f)scene="export"
        if(action=="pinch")scene="zoom-0"
        if(action=="atlas")scene=if(step==0)"atlas-zoom-12"else if(phase>.8f)"atlas-active"else"atlas-pan-${floor(t(.2f,.65f)*12).toInt()}"
        if(action=="layer" && phase<.7f)scene="group-empty"
        val image=image(scene);val center=at("workspace","Canvas center")
        var cropWidth=440f
        var focus=at("workspace",target())
        val region=metadata.optJSONObject(scene)?.optJSONArray("region")
        if(region!=null && open){
            val left=region.getDouble(0).toFloat();val top=region.getDouble(1).toFloat();val right=region.getDouble(2).toFloat();val bottom=region.getDouble(3).toFloat()
            cropWidth=max(380f,(right-left)*1.15f)
            val visible=cropWidth*height/width
            val pan=0f
            focus=PointF((left+right)/2,top+visible/2+(bottom-top-visible).coerceAtLeast(0f)*pan)
        }
        if(action=="drop"){cropWidth=850f;focus=between(at("workspace","Color"),center,.5f)}
        if(action=="slider"){cropWidth=650f;focus=if(phase>.78f)at("workspace","Erase",PointF(1170f,24f))else PointF(180f,240f+360*t(.4f,.55f))}
        if(action=="mix"){cropWidth=850f;focus=center}
        if(action=="pinch" || action=="hold" || action=="resize"){cropWidth=if(action=="pinch")900f else 720f;focus=center}
        if(action=="atlas"){cropWidth=1100f;focus=PointF(662f,422f)}
        if(action=="undo"){cropWidth=850f;focus=PointF(center.x,center.y-80f)}
        cropWidth=cropWidth.coerceAtMost(image.width.toFloat());val cropHeight=min(image.height.toFloat(),cropWidth*height/width)
        val left=(focus.x-cropWidth/2).coerceIn(0f,image.width-cropWidth);val top=(focus.y-cropHeight/2).coerceIn(0f,image.height-cropHeight)
        val scale=min(width/cropWidth,height/cropHeight)
        c.save();c.clipRect(0,0,width,height);c.scale(scale,scale);c.translate(-left,-top)
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE;paint.alpha=255
        if(action=="pinch" || (action=="atlas" && step==0) || action=="resize"){
            val amount=when(action){"pinch"->1f+.5f*t(.25f,.85f);"atlas"->(1f-.62f*t(.2f,.75f))/.38f;else->1f+.18f*t(.25f,.75f)}
            c.save();c.scale(amount,amount,center.x,center.y);if(action=="pinch")c.rotate(12*t(.25f,.85f),center.x,center.y);c.drawBitmap(image,0f,0f,paint);c.restore()
        }else if(action=="atlas" && step==1 && phase<=.8f){
            val value=t(.2f,.65f)*12;val index=floor(value).toInt();val fraction=value-index
            val next="atlas-pan-${(index+1).coerceAtMost(12)}";val before=at(scene,"Canvas center");val after=at(next,"Canvas center");val dx=after.x-before.x
            c.drawColor(StudioTheme.of(activity).workspace);c.drawBitmap(image(next),-dx*(1-fraction),0f,paint);c.drawBitmap(image,dx*fraction,0f,paint)
        }else c.drawBitmap(image,0f,0f,paint)
        fun finger(p:PointF){paint.style=Paint.Style.FILL;paint.color=0x552a9ff0;c.drawCircle(p.x,p.y,17f/scale,paint);paint.style=Paint.Style.STROKE;paint.color=Color.WHITE;paint.strokeWidth=2f/scale;c.drawCircle(p.x,p.y,17f/scale,paint)}
        fun tap(p:PointF){finger(p);paint.color=StudioColors.accent;paint.strokeWidth=2f/scale;c.drawCircle(p.x,p.y,(19+8*sin(phase*PI).toFloat())/scale,paint)}
        when(action){
            "drop"->{
                val pos=between(at("workspace","Color"),center,t(.12f,.55f))
                if(phase>.55f){val reveal=t(.55f,.68f);c.save();c.clipPath(Path().apply{addCircle(center.x,center.y,reveal*650,Path.Direction.CW)});paint.style=Paint.Style.FILL;paint.color=Color.WHITE;c.drawBitmap(image("filled"),0f,0f,paint);c.restore()}
                paint.style=Paint.Style.FILL;paint.color=0xffe3a252.toInt();c.drawCircle(pos.x,pos.y,15f/scale,paint);finger(pos)
                if(phase>.7f){finger(PointF(center.x+sin((phase-.7f)*16)*60,center.y));paint.style=Paint.Style.FILL;paint.color=Color.WHITE;paint.textSize=17f/scale;paint.setShadowLayer(2f,0f,1f,Color.BLACK);c.drawText("Threshold ${(35+sin((phase-.7f)*16)*15).roundToInt()}%",center.x-55/scale,center.y+44/scale,paint);paint.clearShadowLayer()}
            }
            "pinch"->{val spread=105+130*t(.25f,.7f);val angle=t(.65f,.9f)*.3f;finger(PointF(center.x-cos(angle)*spread,center.y-sin(angle)*spread));finger(PointF(center.x+cos(angle)*spread,center.y+sin(angle)*spread))}
            "slider"->{if(phase<.42f)finger(PointF(22f,170f+130*t(.12f,.4f)))else if(phase<.78f)finger(PointF(22f,540f+110*t(.45f,.75f)))else tap(at("workspace","Erase",PointF(1170f,24f)))}
            "undo"->{paint.style=Paint.Style.FILL;c.drawBitmap(image(if(phase>.4f)"workspace"else"filled"),0f,0f,paint);tap(at("workspace",if(phase>.7f)"Redo"else"Undo"))}
            "layer"->{val start=at("group-empty","Layer row Layer 2",PointF(1090f,225f));val end=at("groups","Toggle group Color study",PointF(1070f,115f));finger(between(start,end,t(.3f,.75f)))}
            "hold"->{val theta=t(.15f,.6f)*2*PI;finger(PointF(center.x+cos(theta).toFloat()*170,center.y+sin(theta).toFloat()*137))}
            "resize"->{finger(PointF(center.x+160+65*t(.25f,.75f),center.y+130+40*t(.25f,.75f)))}
            "atlas"->{val v=t(.25f,.65f);if(step==0){finger(PointF(780f-180+105*v,430f));finger(PointF(780f+180-105*v,430f))}else if(phase<.68f)finger(PointF(850f-264*v,440f))else tap(at("atlas-active","Canvas center"))}
            "mix"->{
                val start=center.x-200;val end=center.x+220;val y=center.y
                paint.style=Paint.Style.FILL;paint.color=0xffe6c84f.toInt();c.drawRoundRect(start+90,y-65,start+160,y+65,12f,12f,paint)
                val distance=420*t(.15f,.85f)
                c.save();c.clipRect(start-22,y-85,start+distance,y+85)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=30f;paint.strokeCap=Paint.Cap.ROUND
                paint.shader=LinearGradient(start,y,end,y,intArrayOf(0xff3684d9.toInt(),0xff3684d9.toInt(),0xff649e68.toInt(),0xff488eb0.toInt(),0xff3684d9.toInt()),floatArrayOf(0f,.20f,.38f,.65f,1f),Shader.TileMode.CLAMP)
                c.drawLine(start,y,end,y,paint);paint.shader=null;paint.strokeCap=Paint.Cap.BUTT;c.restore()
                finger(PointF(start+distance,y))
                paint.style=Paint.Style.FILL;paint.color=Color.DKGRAY;paint.textSize=19f
                c.drawText(if(distance<90)"Selected color"else if(distance<240)"Pick up & mix"else "Refill gradually",start,y+100,paint)
            }
            "export"->{if(scene=="video" || scene=="video-minute")tap(at(scene,if(phase<.45f)"Time-lapse length 1 min"else"Export time-lapse"))}
            else->{if(!open)tap(at("workspace",target()))else{
                val key=when(menu){"layers"->"Add";"mixing"->if(phase<.5f)"Mixing enabled"else"Color pickup";"colors"->"Color picker. Outer ring selects hue; inner area selects saturation and brightness.";"video","video-off"->"Time-lapse recording";"group-blend"->"Opacity";"brushes"->"Studio Pen, Inking";else->""}
                if(key.isNotEmpty())tap(at(menu,key,focus))
            }}
        }
        c.restore()
    }
}
