package art.inkstone

import android.graphics.*
import android.view.MotionEvent
import kotlin.math.*

internal class QuickShapeEdit(private val canvas:PaintCanvas,private val session:DrawingSession){
    var active=false;private set
    var released=false;private set
    var name="";private set
    private var mappedPoints:FloatArray?=null
    private var samples=FloatArray(0)
    private var beforeSamples=FloatArray(0)
    private val matrix=Matrix();private var original=Matrix();private var start=PointF();private var pivot=PointF();private var mode="";private var handle=-1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var changed:()->Unit={}
    private var brush:BrushState?=null
    private data class BrushState(val preset:BrushPreset,val size:Float,val opacity:Float,val flow:Float,val pressureSize:Float,val pressureOpacity:Float,val color:Int)
    private fun settings()=BrushState(session.preset,session.size,session.opacity,session.flow,session.pressureSize,session.pressureOpacity,session.color)
    private fun apply(s:BrushState){session.preset=s.preset;session.size=s.size;session.opacity=s.opacity;session.flow=s.flow;session.pressureSize=s.pressureSize;session.pressureOpacity=s.pressureOpacity;session.color=s.color}
    private val d get()=canvas.resources.displayMetrics.density/canvas.transformZoom
    fun start(fit:QuickShape.Fit){
        active=true;released=false;name=fit.name;samples=fit.samples.clone();matrix.reset();brush=settings();preview();changed()
    }
    private fun points():FloatArray{mappedPoints?.let{return it};val p=FloatArray(samples.size/2);for(i in samples.indices step 4){p[i/2]=samples[i];p[i/2+1]=samples[i+1]};matrix.mapPoints(p);mappedPoints=p;return p}
    private fun box():RectF{val p=points();return RectF((p.indices step 2).minOf{p[it]},(p.indices step 2).minOf{p[it+1]},(p.indices step 2).maxOf{p[it]},(p.indices step 2).maxOf{p[it+1]})}
    internal fun bounds()=box()
    private fun corners(r:RectF)=listOf(PointF(r.left,r.top),PointF(r.right,r.top),PointF(r.right,r.bottom),PointF(r.left,r.bottom))
    private fun preview(){
        if(!active)return
        mappedPoints=null
        val prior=settings();brush?.let(::apply);val smooth=session.stabilization;session.stabilization=0f
        val xy=points();val out=samples.clone();for(i in samples.indices step 4){out[i]=xy[i/2];out[i+1]=xy[i/2+1]}
        session.previewQuickShape(out);session.stabilization=smooth;apply(prior);canvas.invalidate()
    }
    fun release(){if(active){released=true;changed();canvas.invalidate()}}
    fun finish(cancel:Boolean=false){if(!active)return;active=false;released=false;mode="";session.stroke(if(cancel)3 else 2,floatArrayOf());changed();canvas.invalidate()}
    fun constrain(){
        if(!active)return
        val r=box();val size=max(r.width(),r.height());val output=mutableListOf<PointF>()
        if(name in listOf("Circle","Ellipse")){name="Circle";for(i in 0..160){val t=i*2*Math.PI/160;output.add(PointF(r.centerX()+size/2*cos(t).toFloat(),r.centerY()+size/2*sin(t).toFloat()))}}
        else if(name in listOf("Square","Rectangle")){name="Square";val q=RectF(r.centerX()-size/2,r.centerY()-size/2,r.centerX()+size/2,r.centerY()+size/2);output.addAll(corners(q));output.add(output[0])}else return
        samples=FloatArray(output.size*4);output.forEachIndexed{i,p->samples[i*4]=p.x;samples[i*4+1]=p.y;samples[i*4+2]=1f};matrix.reset();preview();changed()
    }
    fun draw(c:Canvas){if(!active || !released)return
        val r=box();paint.color=StudioColors.accent;paint.strokeWidth=1.3f*d;paint.style=Paint.Style.STROKE
        if(name!="Line")c.drawRect(r,paint)
        val p=points();val handles=if(name=="Line")listOf(PointF(p[0],p[1]),PointF(p[p.size-2],p.last()))else corners(r)
        paint.style=Paint.Style.FILL;for(h in handles)c.drawCircle(h.x,h.y,5*d,paint)
        if(name!="Line"){paint.strokeWidth=d;c.drawLine(r.centerX(),r.top,r.centerX(),r.top-24*d,paint);c.drawCircle(r.centerX(),r.top-24*d,5*d,paint)}
    }
    fun touch(e:MotionEvent):Boolean{
        if(!active || !released)return false
        if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN){finish();return false}
        val p=canvas.transformDocumentPoint(e.x,e.y)
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                val r=box();val xy=points();val handles=if(name=="Line")listOf(PointF(xy[0],xy[1]),PointF(xy[xy.size-2],xy.last()))else corners(r)
                handle=handles.indexOfFirst{hypot(it.x-p.x,it.y-p.y)<20*d}
                mode=when{handle>=0->if(name=="Line")"endpoint"else"scale";name!="Line" && hypot(p.x-r.centerX(),p.y-(r.top-24*d))<20*d->"rotate";RectF(r).apply{inset(-12*d,-12*d)}.contains(p.x,p.y)->"move";else->""}
                if(mode.isEmpty()){finish();return false}
                start=p;original=Matrix(matrix);beforeSamples=samples.clone();pivot=if(mode=="scale")handles[(handle+2)%4]else PointF(r.centerX(),r.centerY());return true
            }
            MotionEvent.ACTION_MOVE->{if(mode.isEmpty())return false
                when(mode){
                    "endpoint"->{val inverse=Matrix();matrix.invert(inverse);val xy=floatArrayOf(p.x,p.y);inverse.mapPoints(xy);val index=if(handle==0)0 else samples.size-4;samples[index]=xy[0];samples[index+1]=xy[1]}
                    "move"->matrix.set(Matrix(original).apply{postTranslate(p.x-start.x,p.y-start.y)})
                    "scale"->{val sx=if(abs(start.x-pivot.x)>1e-4)((p.x-pivot.x)/(start.x-pivot.x)).coerceIn(.05f,20f)else 1f;var sy=if(abs(start.y-pivot.y)>1e-4)((p.y-pivot.y)/(start.y-pivot.y)).coerceIn(.05f,20f)else 1f
                        val x=if(name in listOf("Circle","Square"))min(sx,sy)else sx;if(name in listOf("Circle","Square"))sy=x;matrix.set(Matrix(original).apply{postScale(x,sy,pivot.x,pivot.y)})}
                    "rotate"->{val angle=(atan2(p.y-pivot.y,p.x-pivot.x)-atan2(start.y-pivot.y,start.x-pivot.x))*180/Math.PI.toFloat();matrix.set(Matrix(original).apply{postRotate(angle,pivot.x,pivot.y)})}
                };preview();return true
            }
            MotionEvent.ACTION_UP->{if(mode.isEmpty())return false;mode="";return true}
            MotionEvent.ACTION_CANCEL->{if(mode.isNotEmpty()){samples=beforeSamples;matrix.set(original);preview();mode=""};return true}
        };return mode.isNotEmpty()
    }
}
