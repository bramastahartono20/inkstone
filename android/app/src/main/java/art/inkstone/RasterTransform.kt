package art.inkstone

import android.graphics.*
import android.os.Build
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal data class TransformPlane(val image:Bitmap?,val rect:RectF)
internal class TransformSnapshot(val info:JSONObject,val active:Int,val bounds:RectF,val planes:List<TransformPlane>){
    fun recycle(){planes.forEach{it.image?.recycle()}}
    companion object{
        fun decode(info:JSONObject,data:IntArray):TransformSnapshot{
            require(data.size>=6);val count=data[0];require(count>=1 && count<=(data.size-6)/6 && data[1] in 0 until count)
            val planes=mutableListOf<TransformPlane>();var offset=6
            try{repeat(count+1){
                require(offset+6<=data.size);val rect=RectF(data[offset].toFloat(),data[offset+1].toFloat(),data[offset+2].toFloat(),data[offset+3].toFloat())
                val w=data[offset+4];val h=data[offset+5];offset+=6;require(w>=0 && h>=0 && w.toLong()*h<=6_000_000 && offset.toLong()+w.toLong()*h<=data.size)
                val image=if(w>0 && h>0)Bitmap.createBitmap(data,offset,w,w,h,Bitmap.Config.ARGB_8888)else null;offset+=w*h;planes.add(TransformPlane(image,rect))
            };require(offset==data.size)
                return TransformSnapshot(info,data[1],RectF(data[2].toFloat(),data[3].toFloat(),data[4].toFloat(),data[5].toFloat()),planes)
            }catch(e:Exception){planes.forEach{it.image?.recycle()};throw e}
        }
    }
}

internal class RasterTransform(private val canvas:PaintCanvas,private val session:DrawingSession){
    var groupId:Long?=null;private set
    fun beginGroup(id:Long){dispose();groupId=id;ensure()}
    private fun member(info:JSONObject,index:Int):Boolean{
        val id=groupId?:return false;val groups=info.optJSONArray("groups")?:JSONArray();val layer=info.getJSONArray("layers").getJSONObject(index)
        var next=if(layer.isNull("groupId"))null else layer.getLong("groupId");val seen=mutableSetOf<Long>()
        while(next!=null && seen.add(next)){if(next==id)return true;val g=(0 until groups.length()).map{groups.getJSONObject(it)}.firstOrNull{it.getLong("id")==next}?:break;next=if(g.isNull("parent_id"))null else g.getLong("parent_id")};return false
    }
    private var snapshot:TransformSnapshot?=null
    private var generation=0
    private var loading=false
    private var committing=false
    private var awaitingFrame=false
    private val matrix=Matrix()
    private var initial=Matrix()
    private var gestureBefore=Matrix()
    private var gesture=""
    private var pointerA=-1;private var pointerB=-1
    private var pen=false
    private var origin=PointF();private var localOrigin=PointF();private var anchor=PointF();private var corner=PointF()
    var mode="Uniform"
    var snapping=false
    var interpolation="Bilinear"
        set(value){field=value;canvas.invalidate()}
    private val mesh=mutableListOf<PointF>()
    private var meshBefore=listOf<PointF>()
    private var snapX:Float?=null;private var snapY:Float?=null
    private var handle=-1
    private var pairCenter=PointF();private var pairDistance=0f;private var pairAngle=0f;private var accumulatedAngle=0f
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val ready get()=snapshot!=null && !committing
    val displaying get()=snapshot!=null
    val bounds get()=snapshot?.let{val q=if(meshChanged())SmoothWarp.grid(mappedMesh())else mappedMesh();RectF(q.minOf{it.x},q.minOf{it.y},q.maxOf{it.x},q.maxOf{it.y})}
    private fun regularMesh():List<PointF>{val r=snapshot?.bounds?:return emptyList();return (0..3).flatMap{y->(0..3).map{x->PointF(r.left+r.width()*x/3,r.top+r.height()*y/3)}}}
    private fun resetMesh(){mesh.clear();mesh.addAll(regularMesh())}
    private fun meshChanged()=mesh.zip(regularMesh()).any{(a,b)->hypot(a.x-b.x,a.y-b.y)>.001f}
    private fun mappedMesh():List<PointF>{if(mesh.isEmpty())return emptyList();val values=FloatArray(mesh.size*2);mesh.forEachIndexed{i,p->values[i*2]=p.x;values[i*2+1]=p.y};matrix.mapPoints(values);return mesh.indices.map{PointF(values[it*2],values[it*2+1])}}
    internal fun currentMatrix()=Matrix(matrix)
    fun ensure(){
        if(session.tool!="Transform" || loading || snapshot!=null || committing)return
        if(groupId==null && session.info.getJSONArray("layers").getJSONObject(session.info.getInt("active")).optBoolean("locked")){canvas.onGesture("This layer is locked");return}
        loading=true;session.transformEditing=true;val token=++generation
        session.transformSnapshot{value->
            if(token!=generation || session.tool!="Transform"){value?.recycle();return@transformSnapshot}
            loading=false
            if(value!=null && groupId!=null){value.bounds.setEmpty();for(i in 0 until value.info.getJSONArray("layers").length())if(member(value.info,i)){
                if(value.planes[i].image!=null)value.bounds.union(value.planes[i].rect)
                if(i==value.active && value.planes.last().image!=null)value.bounds.union(value.planes.last().rect)
            }}
            if(value==null || value.bounds.isEmpty){session.transformEditing=false;value?.recycle();canvas.onGesture("The active layer has no selected pixels to move");return@transformSnapshot}
            snapshot=value;matrix.reset();resetMesh();canvas.invalidate()
        }
    }
    fun finish(cancel:Boolean=false){
        if(committing)return
        val s=snapshot
        if(loading){generation++;loading=false}
        session.transformInteracting=false;session.transformEditing=false;gesture="";pointerA=-1;pointerB=-1
        if(s==null)return
        if(cancel || (matrix.isIdentity && !meshChanged())){dispose();canvas.invalidate();return}
        val values=FloatArray(9);matrix.getValues(values)
        committing=true
        val operation:String;val payload=JSONObject().put("filter",interpolation)
        if(groupId!=null){operation="group";payload.put("action","translate").put("id",groupId).put("x",values[Matrix.MTRANS_X].roundToInt()).put("y",values[Matrix.MTRANS_Y].roundToInt())}
        else if(meshChanged()){
            operation="transformMesh";payload.put("columns",SmoothWarp.STEPS).put("rows",SmoothWarp.STEPS).put("points",JSONArray().also{array->SmoothWarp.grid(mappedMesh()).forEach{array.put(JSONArray().put(it.x).put(it.y))}})
        }else{operation="transformProjective";payload.put("matrix",JSONArray(values.map{it.toDouble()}))}
        session.command(operation,payload){result->if(result.has("error")){dispose();canvas.invalidate()}else awaitingFrame=true}
    }

    fun onFrame(){if(awaitingFrame){dispose();canvas.deselect();canvas.invalidate();if(session.tool=="Transform")ensure()}}
    fun dispose(){session.transformEditing=false;session.transformInteracting=false;generation++;groupId=null;snapshot?.recycle();snapshot=null;loading=false;committing=false;awaitingFrame=false;matrix.reset();mesh.clear();snapX=null;snapY=null}
    private fun layerSource(c:Canvas,index:Int,s:TransformSnapshot){
        if(groupId!=null){val save=c.save();if(member(s.info,index))c.concat(matrix)
            val plane=s.planes[index];plane.image?.let{c.drawBitmap(it,null,plane.rect,p)}
            if(index==s.active){val moving=s.planes.last();moving.image?.let{c.drawBitmap(it,null,moving.rect,p)}}
            c.restoreToCount(save);return
        }
        val plane=s.planes[index];plane.image?.let{c.drawBitmap(it,null,plane.rect,p)}
        if(index==s.active){val moving=s.planes.last();moving.image?.let{
            p.isFilterBitmap=interpolation!="Nearest"
            if(meshChanged()){
                val steps=SmoothWarp.STEPS
                val vertices=SmoothWarp.grid(mappedMesh()).flatMap{point->listOf(point.x,point.y)}.toFloatArray()
                c.drawBitmapMesh(it,steps,steps,vertices,0,null,0,p)
            }
            else{val save=c.save();c.concat(matrix);c.drawBitmap(it,null,moving.rect,p);c.restoreToCount(save)}
        }}
    }
    private fun blend(name:String,opacity:Float):Paint=Paint().apply{
        alpha=(opacity*255).roundToInt().coerceIn(0,255)
        if(Build.VERSION.SDK_INT>=29)blendMode=when(name){
            "Multiply"->BlendMode.MULTIPLY;"Screen"->BlendMode.SCREEN;"Overlay"->BlendMode.OVERLAY;"Hard Light"->BlendMode.HARD_LIGHT
            "Darken"->BlendMode.DARKEN;"Lighten"->BlendMode.LIGHTEN;"Difference"->BlendMode.DIFFERENCE;"Exclusion"->BlendMode.EXCLUSION
            "Colour Burn"->BlendMode.COLOR_BURN;"Colour Dodge"->BlendMode.COLOR_DODGE;"Add"->BlendMode.PLUS;else->BlendMode.SRC_OVER
        }else xfermode=PorterDuffXfermode(when(name){"Multiply"->PorterDuff.Mode.MULTIPLY;"Screen"->PorterDuff.Mode.SCREEN;"Overlay"->PorterDuff.Mode.OVERLAY;"Darken"->PorterDuff.Mode.DARKEN;"Lighten"->PorterDuff.Mode.LIGHTEN;"Add"->PorterDuff.Mode.ADD;else->PorterDuff.Mode.SRC_OVER})
    }
    fun draw(c:Canvas,outside:Boolean=false):Boolean{
        val s=snapshot?:return false;val page=RectF(0f,0f,session.canvasWidth.toFloat(),session.canvasHeight.toFloat())
        if(!outside && s.info.optBoolean("backgroundVisible",true)){p.color=s.info.getLong("background").toInt();c.drawRect(page,p)}
        val clipped=c.save();if(outside)c.clipOutRect(page)else c.clipRect(page)
        val exteriorAlpha=if(outside)c.saveLayer(null,Paint().apply{alpha=110})else -1
        val layers=s.info.getJSONArray("layers");val groups=s.info.optJSONArray("groups")?:JSONArray();var base=-1
        val stack=mutableListOf<Pair<Long,Int>>()
        fun isolatedPath(layer:JSONObject):List<JSONObject>{
            var id=if(layer.isNull("groupId"))null else layer.getLong("groupId");val path=mutableListOf<JSONObject>();val seen=mutableSetOf<Long>()
            while(id!=null && seen.add(id)){
                val g=(0 until groups.length()).map{groups.getJSONObject(it)}.firstOrNull{it.getLong("id")==id}?:break
                if(g.optString("blend","Pass Through")!="Pass Through" || g.optDouble("opacity",1.0)<1.0)path.add(0,g)
                id=if(g.isNull("parent_id"))null else g.getLong("parent_id")
            };return path
        }
        for(i in 0 until layers.length()){
            val layer=layers.getJSONObject(i);val path=isolatedPath(layer)
            val common=stack.zip(path).takeWhile{it.first.first==it.second.getLong("id")}.size
            while(stack.size>common)c.restoreToCount(stack.removeAt(stack.lastIndex).second)
            for(g in path.drop(common))stack.add(g.getLong("id") to c.saveLayer(if(outside)null else page,GroupAppearance.paint(g.optString("blend","Pass Through"),g.optDouble("opacity",1.0).toFloat())))
            if(!layer.optBoolean("clipping"))base=i
            if(!layer.getBoolean("visible"))continue
            val save=c.saveLayer(if(outside)null else page,blend(layer.getString("blend"),layer.getDouble("opacity").toFloat()))
            layerSource(c,i,s)
            if(layer.optBoolean("clipping")){
                val mask=c.saveLayer(if(outside)null else page,Paint().apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)})
                if(base>=0 && layers.getJSONObject(base).getBoolean("visible"))layerSource(c,base,s)
                c.restoreToCount(mask)
            }
            c.restoreToCount(save)
        }
        while(stack.isNotEmpty())c.restoreToCount(stack.removeAt(stack.lastIndex).second)
        if(exteriorAlpha>=0)c.restoreToCount(exteriorAlpha)
        c.restoreToCount(clipped);return true
    }
    private fun points():List<PointF>{val r=snapshot?.bounds?:return emptyList();return listOf(PointF(r.left,r.top),PointF(r.right,r.top),PointF(r.right,r.bottom),PointF(r.left,r.bottom),PointF(r.centerX(),r.top),PointF(r.right,r.centerY()),PointF(r.centerX(),r.bottom),PointF(r.left,r.centerY()))}
    private fun mapped():List<PointF> = points().map{v->val a=floatArrayOf(v.x,v.y);matrix.mapPoints(a);PointF(a[0],a[1])}
    private fun rotationHandle():PointF{
        val r=snapshot!!.bounds;val q=floatArrayOf(r.centerX(),r.centerY(),r.centerX(),r.top);matrix.mapPoints(q)
        val length=hypot(q[2]-q[0],q[3]-q[1]).coerceAtLeast(.001f);val extra=30*canvas.resources.displayMetrics.density/canvas.transformZoom
        return PointF(q[2]+(q[2]-q[0])/length*extra,q[3]+(q[3]-q[1])/length*extra)
    }
    fun drawHandles(c:Canvas){
        if(groupId!=null){bounds?.let{r->p.color=StudioColors.accent;p.style=Paint.Style.STROKE;p.strokeWidth=1.5f*canvas.resources.displayMetrics.density/canvas.transformZoom;c.drawRect(r,p);p.style=Paint.Style.FILL};return}
        if(snapshot==null)return;val q=if(mode=="Warp")mappedMesh() else mapped();val d=canvas.resources.displayMetrics.density/canvas.transformZoom
        p.color=StudioColors.accent;p.style=Paint.Style.STROKE;p.strokeWidth=1.3f*d;p.pathEffect=DashPathEffect(floatArrayOf(6*d,4*d),0f)
        if(mode=="Warp"){
            val grid=SmoothWarp.grid(q);val steps=SmoothWarp.STEPS;val stride=steps+1
            for(row in 0..3)c.drawPath(Path().apply{for(col in 0..steps){val point=grid[row*(steps/3)*stride+col];if(col==0)moveTo(point.x,point.y)else lineTo(point.x,point.y)}},p)
            for(col in 0..3)c.drawPath(Path().apply{for(row in 0..steps){val point=grid[row*stride+col*(steps/3)];if(row==0)moveTo(point.x,point.y)else lineTo(point.x,point.y)}},p)
        }else c.drawPath(Path().apply{moveTo(q[0].x,q[0].y);for(i in 1..3)lineTo(q[i].x,q[i].y);close()},p)
        p.pathEffect=null
        snapX?.let{c.drawLine(it,0f,it,session.canvasHeight.toFloat(),p)};snapY?.let{c.drawLine(0f,it,session.canvasWidth.toFloat(),it,p)}
        val rotate=rotationHandle();c.drawLine(mapped()[4].x,mapped()[4].y,rotate.x,rotate.y,p)
        for(v in q+rotate){p.style=Paint.Style.FILL;p.color=Color.WHITE;c.drawCircle(v.x,v.y,5*d,p);p.style=Paint.Style.STROKE;p.color=StudioColors.accent;c.drawCircle(v.x,v.y,5*d,p)}
        p.style=Paint.Style.FILL
    }
    private fun valid(candidate:Matrix):Boolean{val m=FloatArray(9);candidate.getValues(m);return m.all{it.isFinite() && abs(it)<=1_000_000} && abs(m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]))>1e-6}
    private fun replace(candidate:Matrix){if(valid(candidate)){matrix.set(candidate);canvas.invalidate()}}
    fun reset(){ensure();if(snapshot!=null){matrix.reset();resetMesh();snapX=null;snapY=null;canvas.invalidate();canvas.onChanged()}}
    fun fitToCanvas(){ensure();val r=bounds?:return;val scale=min(session.canvasWidth/r.width(),session.canvasHeight/r.height());replace(Matrix(matrix).apply{postScale(scale,scale,r.centerX(),r.centerY());postTranslate(session.canvasWidth/2f-r.centerX(),session.canvasHeight/2f-r.centerY())});canvas.onChanged()}
    private fun snapMove(dx:Float,dy:Float):PointF{
        snapX=null;snapY=null;if(!snapping)return PointF(dx,dy)
        val r=snapshot!!.bounds;val before=RectF();initial.mapRect(before,r)
        val xs=mutableListOf(0f,session.canvasWidth/2f,session.canvasWidth.toFloat());val ys=mutableListOf(0f,session.canvasHeight/2f,session.canvasHeight.toFloat())
        snapshot!!.planes.dropLast(1).forEachIndexed{i,p->if(i!=snapshot!!.active && !p.rect.isEmpty){xs.addAll(listOf(p.rect.left,p.rect.centerX(),p.rect.right));ys.addAll(listOf(p.rect.top,p.rect.centerY(),p.rect.bottom))}}
        val threshold=8*canvas.resources.displayMetrics.density/canvas.transformZoom
        val x=xs.flatMap{target->listOf(before.left,before.centerX(),before.right).map{edge->Pair(target,target-edge-dx)}}.minByOrNull{abs(it.second)}
        val y=ys.flatMap{target->listOf(before.top,before.centerY(),before.bottom).map{edge->Pair(target,target-edge-dy)}}.minByOrNull{abs(it.second)}
        if(x!=null && abs(x.second)<threshold)snapX=x.first;if(y!=null && abs(y.second)<threshold)snapY=y.first
        return PointF(dx+if(snapX!=null)x!!.second else 0f,dy+if(snapY!=null)y!!.second else 0f)
    }
    private fun convex(points:List<PointF>):Boolean{
        var sign=0f
        for(i in points.indices){val a=points[i];val b=points[(i+1)%points.size];val c=points[(i+2)%points.size];val cross=(b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x);if(abs(cross)<.0001f)return false;if(sign!=0f && sign*cross<0)return false;sign=cross};return true
    }
    private fun validMesh(points:List<PointF>):Boolean {
        val grid=SmoothWarp.grid(points);val n=SmoothWarp.STEPS
        var orientation=0f
        for(row in 0 until n)for(col in 0 until n){val i=row*(n+1)+col;val q=listOf(grid[i],grid[i+1],grid[i+n+2],grid[i+n+1])
            if(!convex(q))return false
            val cross=(q[1].x-q[0].x)*(q[2].y-q[1].y)-(q[1].y-q[0].y)*(q[2].x-q[1].x)
            if(orientation*cross<0)return false;orientation=cross
        };return true
    }

    fun scale(factor:Float){ensure();val s=snapshot?:return;val r=RectF();matrix.mapRect(r,s.bounds);replace(Matrix(matrix).apply{postScale(factor,factor,r.centerX(),r.centerY())});canvas.onChanged()}
    fun operation(key:String,value:Float){ensure();val s=snapshot?:return;val r=RectF();matrix.mapRect(r,s.bounds)
        replace(Matrix(matrix).apply{when(key){"sx"->postScale(value,1f,r.centerX(),r.centerY());"sy"->postScale(1f,value,r.centerX(),r.centerY());"angle"->postRotate(value*180f/Math.PI.toFloat(),r.centerX(),r.centerY())}});canvas.onChanged()
    }
    private fun startPair(e:MotionEvent,exclude:Int=-1){
        val ids=(0 until e.pointerCount).filter{it!=exclude};if(ids.size<2)return
        pointerA=e.getPointerId(ids[0]);pointerB=e.getPointerId(ids[1]);val a=canvas.transformDocumentPoint(e.getX(ids[0]),e.getY(ids[0]));val b=canvas.transformDocumentPoint(e.getX(ids[1]),e.getY(ids[1]))
        pairCenter=PointF((a.x+b.x)/2,(a.y+b.y)/2);pairDistance=hypot(b.x-a.x,b.y-a.y);pairAngle=atan2(b.y-a.y,b.x-a.x);accumulatedAngle=0f;initial=Matrix(matrix);gesture="pair"
    }
    fun touch(e:MotionEvent):Boolean{
        if(!ready){ensure();if(e.actionMasked==MotionEvent.ACTION_DOWN)canvas.onGesture(if(committing)"Applying transform…"else"Preparing transform…");return true}
        if(groupId!=null){
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{canvas.onStrokeStart();session.transformInteracting=true;origin=canvas.transformDocumentPoint(e.x,e.y);gestureBefore=Matrix(matrix);pointerA=e.getPointerId(0)}
                MotionEvent.ACTION_MOVE->{val index=e.findPointerIndex(pointerA);if(index>=0){val at=canvas.transformDocumentPoint(e.getX(index),e.getY(index));matrix.set(gestureBefore);matrix.postTranslate(at.x-origin.x,at.y-origin.y)}}
                MotionEvent.ACTION_CANCEL->{matrix.set(gestureBefore);session.transformInteracting=false;pointerA=-1}
                MotionEvent.ACTION_UP->{session.transformInteracting=false;pointerA=-1}
            };canvas.invalidate();return true
        }
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{canvas.onStrokeStart();session.transformInteracting=true;pen=e.getToolType(0) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER);pointerA=e.getPointerId(0);pointerB=-1;origin=canvas.transformDocumentPoint(e.x,e.y);initial=Matrix(matrix);gestureBefore=Matrix(matrix);meshBefore=mesh.map{PointF(it.x,it.y)}
                val radius=22*canvas.resources.displayMetrics.density/canvas.transformZoom;val q=if(mode=="Warp")mappedMesh()else mapped();handle=q.indices.minByOrNull{hypot(q[it].x-origin.x,q[it].y-origin.y)}?.takeIf{hypot(q[it].x-origin.x,q[it].y-origin.y)<=radius}?:-1
                val rotate=rotationHandle();gesture=if(handle>=0 && mode=="Warp")"warp"else if(handle>=0)"resize"else if(hypot(rotate.x-origin.x,rotate.y-origin.y)<=radius)"rotate"else"move"
                val r=snapshot!!.bounds;anchor=PointF(r.centerX(),r.centerY())
                if(handle>=0 && gesture!="warp"){val ps=points();corner=ps[handle];anchor=ps[if(handle<4)(handle+2)%4 else 4+(handle-4+2)%4];val inverse=Matrix();initial.invert(inverse);val xy=floatArrayOf(origin.x,origin.y);inverse.mapPoints(xy);localOrigin=PointF(xy[0],xy[1])}
                else if(gesture=="rotate"){val xy=floatArrayOf(anchor.x,anchor.y);initial.mapPoints(xy);anchor=PointF(xy[0],xy[1])}
            }
            MotionEvent.ACTION_POINTER_DOWN->if(!pen && pointerB<0)startPair(e)
            MotionEvent.ACTION_MOVE->{
                val a=e.findPointerIndex(pointerA);if(a<0)return true;val at=canvas.transformDocumentPoint(e.getX(a),e.getY(a))
                when(gesture){
                    "pair"->{val b=e.findPointerIndex(pointerB);if(b<0)return true;val bt=canvas.transformDocumentPoint(e.getX(b),e.getY(b));val center=PointF((at.x+bt.x)/2,(at.y+bt.y)/2);val distance=hypot(bt.x-at.x,bt.y-at.y);val angle=atan2(bt.y-at.y,bt.x-at.x)
                        var delta=angle-pairAngle;while(delta>Math.PI)delta-=(2*Math.PI).toFloat();while(delta< -Math.PI)delta+=(2*Math.PI).toFloat();accumulatedAngle+=delta;pairAngle=angle
                        val scale=if(pairDistance>.001f)(distance/pairDistance).coerceIn(.02f,50f)else 1f
                        val change=Matrix().apply{setScale(scale,scale,pairCenter.x,pairCenter.y);postRotate(accumulatedAngle*180f/Math.PI.toFloat(),pairCenter.x,pairCenter.y);postTranslate(center.x-pairCenter.x,center.y-pairCenter.y)}
                        replace(Matrix(initial).apply{postConcat(change)})
                    }
                    "move"->{val offset=snapMove(at.x-origin.x,at.y-origin.y);replace(Matrix(initial).apply{postTranslate(offset.x,offset.y)})}
                    "rotate"->{var angle=(atan2(at.y-anchor.y,at.x-anchor.x)-atan2(origin.y-anchor.y,origin.x-anchor.x))*180f/Math.PI.toFloat();if(snapping && abs(angle/15f-round(angle/15f))<.2f)angle=round(angle/15f)*15f;replace(Matrix(initial).apply{postRotate(angle,anchor.x,anchor.y)})}
                    "warp"->{val inverse=Matrix();initial.invert(inverse);val xy=floatArrayOf(at.x,at.y,origin.x,origin.y);inverse.mapPoints(xy);val candidate=meshBefore.map{PointF(it.x,it.y)}.toMutableList();candidate[handle].offset(xy[0]-xy[2],xy[1]-xy[3]);if(validMesh(candidate)){mesh.clear();mesh.addAll(candidate);canvas.invalidate()}}
                    "resize"->{
                        if(mode=="Distort"){
                            val source=points().take(4);val target=source.map{v->val p=floatArrayOf(v.x,v.y);initial.mapPoints(p);PointF(p[0],p[1])}.toMutableList()
                            val affected=if(handle<4)listOf(handle)else when(handle){4->listOf(0,1);5->listOf(1,2);6->listOf(2,3);else->listOf(3,0)}
                            affected.forEach{target[it].offset(at.x-origin.x,at.y-origin.y)}
                            if(convex(target)){val src=source.flatMap{listOf(it.x,it.y)}.toFloatArray();val dst=target.flatMap{listOf(it.x,it.y)}.toFloatArray();val candidate=Matrix();if(candidate.setPolyToPoly(src,0,dst,0,4))replace(candidate)}
                            return true
                        }
                        val inverse=Matrix();initial.invert(inverse);val xy=floatArrayOf(at.x,at.y);inverse.mapPoints(xy);val x=corner.x+xy[0]-localOrigin.x;val y=corner.y+xy[1]-localOrigin.y
                        var sx=1f;var sy=1f
                        if(handle<4){val dx=corner.x-anchor.x;val dy=corner.y-anchor.y
                            if(mode=="Freeform"){sx=((x-anchor.x)/dx).coerceIn(.02f,50f);sy=((y-anchor.y)/dy).coerceIn(.02f,50f)}else{val v=(((x-anchor.x)*dx+(y-anchor.y)*dy)/(dx*dx+dy*dy).coerceAtLeast(.001f)).coerceIn(.02f,50f);sx=v;sy=v}}
                        else if(handle==5 || handle==7)sx=((x-anchor.x)/(corner.x-anchor.x)).coerceIn(.02f,50f)
                        else sy=((y-anchor.y)/(corner.y-anchor.y)).coerceIn(.02f,50f)
                        if(mode=="Uniform"){if(handle==5 || handle==7)sy=sx else if(handle>=4)sx=sy}
                        replace(Matrix(initial).apply{preScale(sx,sy,anchor.x,anchor.y)})
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP->{if(pen && e.getPointerId(e.actionIndex)!=pointerA)return true
                val last=MotionEvent.obtain(e);last.action=MotionEvent.ACTION_MOVE;touch(last);last.recycle()
                val remaining=(0 until e.pointerCount).filter{it!=e.actionIndex}
                if(remaining.size>=2)startPair(e,e.actionIndex)else if(remaining.size==1){val i=remaining[0];pointerA=e.getPointerId(i);pointerB=-1;origin=canvas.transformDocumentPoint(e.getX(i),e.getY(i));initial=Matrix(matrix);gesture="move"}
            }
            MotionEvent.ACTION_UP->{val last=MotionEvent.obtain(e);last.action=MotionEvent.ACTION_MOVE;touch(last);last.recycle();session.transformInteracting=false;gesture="";pointerA=-1;pointerB=-1;snapX=null;snapY=null;canvas.onChanged();canvas.performClick()}
            MotionEvent.ACTION_CANCEL->{mesh.clear();mesh.addAll(meshBefore);replace(gestureBefore);session.transformInteracting=false;gesture="";pointerA=-1;pointerB=-1;canvas.onChanged()}
        };return true
    }
}
