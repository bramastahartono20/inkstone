package art.inkstone

import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

internal class VectorCanvas(private val activity:MainActivity,val document:VectorDocument):View(activity){
    val guides=DrawingGuide(activity)
    var movingGroup:Long?=null;private set
    private var groupBefore:ByteArray?=null
    private var groupOrigin=PointF()
    private var groupMatrices=emptyList<Pair<VectorObject,Matrix>>()
    fun moveGroup(id:Long){changeTool("Select");movingGroup=id;onSelection()}
    fun finishGroupMove(){cancelGesture();movingGroup=null;onSelection()}
    var tool="Pen"
    var ink=0xff202020.toInt()
    var strokeWidth=5f
    var fillColor:Int?=null
    var stabilization=activity.vectorStabilization
    var selectedNode:Int?=null;private set
    var selectedId:Long?=null
    var onChanged:()->Unit={}
    var onSelection:()->Unit={}
    var onDismissPanel:()->Boolean={false}
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var fitScale=1f
    internal var onAtlas:()->Unit={}
    private var zoom=1f;private var rotation=0f;private var pan=PointF();private var fitted=false
    private val historyTap=MultiFingerTap(12*resources.displayMetrics.density)
    private var pointer=-1;private var pen=false
    private var draft:VectorObject?=null
    private var before:ByteArray?=null
    var joiningPaths=false;private set
    var addingAnchor=false;private set
    private var mode=""
    private var nodeOriginal=emptyList<PointF>()
    private var nodeMoved=false
    private var filtered=PointF()
    private var sampleTime=0L
    private var origin=PointF();private var originalMatrix=Matrix();private var anchor=PointF();private var node=-1
    private var gestureDistance=0f;private var gestureAngle=0f;private var gestureCenter=PointF();private var multi=false
    private var pickTouch:PointF?=null
    private var holdOrigin=PointF()
    private var pickingColor=false
    private var pickBitmap:Bitmap?=null
    private val holdColor=Runnable{
        if(pointer>=0 && !multi && tool=="Pen"){
            cancelGesture();pickingColor=true;pickAt(holdOrigin.x,holdOrigin.y)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }
    private fun pickAt(x:Float,y:Float){
        val point=pagePoint(x,y);if(point.x<0 || point.y<0 || point.x>=document.width || point.y>=document.height)return
        val bitmap=pickBitmap?:Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).also{pickBitmap=it};bitmap.eraseColor(Color.TRANSPARENT)
        val sample=Canvas(bitmap);sample.translate(-floor(point.x),-floor(point.y));document.draw(sample)
        val color=bitmap.getPixel(0,0);if(Color.alpha(color)>0){ink=color;pickTouch=PointF(x,y);onSelection();invalidate()}
    }
    val busy get()=pointer>=0 || multi || pickingColor
    val selected get()=document.objectById(selectedId)
    init{isFocusable=true;contentDescription="Vector drawing canvas"}
    private fun screenMatrix()=Matrix().apply{postTranslate(-document.width/2f,-document.height/2f);postScale(zoom,zoom);postRotate(rotation);postTranslate(width/2f+pan.x,height/2f+pan.y)}
    internal fun pagePoint(x:Float,y:Float):PointF {val inverse=Matrix();screenMatrix().invert(inverse);val p=floatArrayOf(x,y);inverse.mapPoints(p);return PointF(p[0],p[1])}
    internal fun viewPoint(x:Float,y:Float):PointF {val p=floatArrayOf(x,y);screenMatrix().mapPoints(p);return PointF(p[0],p[1])}
    internal fun captureAtlas(){activity.atlas?.capture(screenMatrix(),width,height)}
    internal fun setAtlasCamera(matrix:Matrix){fitScale=min((width-64*density)/document.width,(height-64*density)/document.height).coerceAtLeast(.01f);val values=FloatArray(9);matrix.getValues(values);zoom=hypot(values[0],values[3]);rotation=atan2(values[3],values[0])*180f/Math.PI.toFloat();val center=floatArrayOf(document.width/2f,document.height/2f);matrix.mapPoints(center);pan=PointF(center[0]-width/2f,center[1]-height/2f);fitted=true;invalidate()}
    private var atlasAnimator:android.animation.ValueAnimator?=null
    private var atlasGestureStartZoom=1f
    internal fun atlasOverview(){atlasAnimator?.cancel();val start=zoom;val target=min((width-64*density)/document.width,(height-64*density)/document.height)*.38f;val previous=PointF(pan.x,pan.y)
        atlasAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=280;interpolator=android.view.animation.DecelerateInterpolator();addUpdateListener{val f=it.animatedValue as Float;zoom=start+(target-start)*f;pan=PointF(previous.x*(1-f),previous.y*(1-f));invalidate()};start()}}
    fun fit(){if(width<=0 || height<=0)return;zoom=min((width-64*density)/document.width,(height-64*density)/document.height).coerceAtLeast(.01f);fitScale=zoom;pan=PointF();rotation=0f;fitted=true;invalidate()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){val camera=activity.atlas?.pageCamera(w,h);if(camera!=null)setAtlasCamera(camera)else if(!fitted || oldw!=w || oldh!=h)fit()}
    fun snapshot():ByteArray=before?.clone()?:document.encode()
    fun cancelGesture(){groupBefore?.let{document.restore(it)};groupBefore=null;groupMatrices=emptyList();removeCallbacks(holdColor);pickingColor=false;pickTouch=null;before?.let{document.restore(it)};before=null;draft=null;pointer=-1;mode="";multi=false;invalidate();onSelection()}
    fun changeTool(value:String){movingGroup=null;joiningPaths=false;addingAnchor=false;cancelGesture();if(value!="Nodes")selectedNode=null;tool=value;invalidate();onSelection()}
    fun undo(){addingAnchor=false;cancelGesture();if(document.undo()){selectedId=null;selectedNode=null;onChanged()};activity.message(document.historyNotice);invalidate();onSelection()}
    fun redo(){addingAnchor=false;cancelGesture();if(document.redo()){selectedId=null;selectedNode=null;onChanged()};activity.message(document.historyNotice);invalidate();onSelection()}
    fun edit(change:()->Unit){cancelGesture();runCatching{document.edit(change)}.onFailure{activity.message(it.message?:"Could not change object")}.onSuccess{onChanged()};invalidate();onSelection()}
    override fun onDraw(c:Canvas){
        if(activity.atlas!=null)activity.atlas!!.draw(c,screenMatrix(),width,height)else{
        c.drawColor(StudioTheme.of(context).workspace);paint.color=if(StudioTheme.of(context).light)0x18000000 else 0x0bffffff;paint.strokeWidth=density
        var x=0f;while(x<width){c.drawLine(x,0f,x,height.toFloat(),paint);x+=24*density};var y=0f;while(y<height){c.drawLine(0f,y,width.toFloat(),y,paint);y+=24*density}
        }
        val page=c.save();c.concat(screenMatrix());val clipped=c.save();c.clipRect(0f,0f,document.width.toFloat(),document.height.toFloat())
        if(!document.backgroundVisible){c.drawColor(0xffe2e4e7.toInt());paint.color=0xffc5c9ce.toInt();for(yy in 0 until document.height step 32)for(xx in 0 until document.width step 32)if((xx/32+yy/32)%2==0)c.drawRect(xx.toFloat(),yy.toFloat(),xx+32f,yy+32f,paint)}
        document.draw(c);guides.draw(c,document.width.toFloat(),document.height.toFloat(),zoom);draft?.let{VectorDocument.drawObject(c,it)};c.restoreToCount(clipped)
        selected?.takeIf{tool in listOf("Select","Nodes")}?.let{obj->
            val r=obj.bounds();paint.color=StudioColors.accent;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.3f*density/zoom;c.drawRect(r,paint)
            paint.style=Paint.Style.FILL
            if(tool=="Nodes"){
                val selectedIndex=selectedNode
                if(obj.kind=="Bezier" && selectedIndex!=null && selectedIndex%3==0){
                    val at=obj.points.getOrNull(selectedIndex)
                    if(at!=null){val anchor=VectorGeometry.mapped(obj,at)
                        for(index in listOf(selectedIndex-1,selectedIndex+1))obj.points.getOrNull(index)?.let{p->val handle=VectorGeometry.mapped(obj,p)
                            paint.color=0xff91b7d2.toInt();paint.strokeWidth=density/zoom;c.drawLine(anchor.x,anchor.y,handle.x,handle.y,paint)
                            paint.color=Color.WHITE;c.drawCircle(handle.x,handle.y,5*density/zoom,paint);paint.style=Paint.Style.STROKE;paint.color=StudioColors.accent;paint.strokeWidth=1.5f*density/zoom;c.drawCircle(handle.x,handle.y,5*density/zoom,paint);paint.style=Paint.Style.FILL
                        }
                    }
                }
                for((index,p) in VectorGeometry.anchors(obj)){val xy=VectorGeometry.mapped(obj,p);val active=index==selectedNode
                    paint.color=if(active)StudioColors.accent else Color.WHITE;c.drawCircle(xy.x,xy.y,(if(active)7 else 4)*density/zoom,paint)
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*density/zoom;paint.color=if(active)Color.WHITE else StudioColors.accent;c.drawCircle(xy.x,xy.y,(if(active)7 else 4)*density/zoom,paint);paint.style=Paint.Style.FILL
                }
            }else{
                for(p in corners(r))c.drawCircle(p.x,p.y,5*density/zoom,paint)
                c.drawLine(r.centerX(),r.top,r.centerX(),r.top-26*density/zoom,paint)
                c.drawCircle(r.centerX(),r.top-24*density/zoom,5*density/zoom,paint)
            }
        };c.restoreToCount(page)
        pickTouch?.let{p->paint.style=Paint.Style.FILL;paint.color=ink;c.drawCircle(p.x,p.y-35*density,20*density,paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=Color.WHITE;c.drawCircle(p.x,p.y-35*density,20*density,paint);paint.style=Paint.Style.FILL}
    }
    private fun corners(r:RectF)=listOf(PointF(r.left,r.top),PointF(r.right,r.top),PointF(r.right,r.bottom),PointF(r.left,r.bottom))
    fun cycleNode(delta:Int){
        cancelGesture();val obj=selected?:return;val anchors=VectorGeometry.anchors(obj);if(anchors.isEmpty())return
        val current=anchors.indexOfFirst{it.first==selectedNode};selectedNode=anchors[(if(current<0)0 else (current+delta+anchors.size)%anchors.size)].first;onSelection();invalidate()
    }
    fun nodePosition():PointF?{val obj=selected?:return null;return VectorGeometry.anchors(obj).firstOrNull{it.first==selectedNode}?.second?.let{PointF(it.x,it.y)}}
    fun setNodePosition(x:Float,y:Float){
        val obj=selected?:return;val index=selectedNode?:return
        if(document.owner(obj.id)?.effectiveLocked==true){activity.message("This layer is locked");return}
        edit{if(obj.kind=="Pen")obj.toBezier();val old=obj.points[index];val dx=x-old.x;val dy=y-old.y
            for(i in if(obj.kind=="Bezier")listOf(index-1,index,index+1)else listOf(index))obj.points.getOrNull(i)?.offset(dx,dy)
            obj.geometryChanged()}
    }
    fun addAnchorMode(){
        cancelGesture();val obj=selected?:return
        if(document.owner(obj.id)?.effectiveLocked==true){activity.message("This layer is locked");return}
        if(obj.kind !in listOf("Line","Pen","Bezier")){activity.message("Choose a line or pen path");return}
        addingAnchor=!addingAnchor;onSelection();invalidate()
    }
    private fun addAnchor(point:PointF){
        val id=selectedId?:return
        runCatching{var index=0;document.edit{
            val obj=checkNotNull(document.objectById(id));require(document.owner(id)?.effectiveLocked!=true){"This layer is locked"}
            index=VectorGeometry.insertAnchor(obj,point,24*density/zoom)
        };index}.onSuccess{selectedNode=it;addingAnchor=false;onChanged()}
            .onFailure{activity.message(it.message?:"Could not add anchor")}
        pointer=-1;onSelection();invalidate()
    }
    private fun beginNodes(point:PointF){
        val radius=(if(pen)12 else 22)*density/zoom
        var hit:VectorGeometry.Hit?=null;var handle=false
        selected?.takeIf{it.kind=="Bezier"}?.let{obj->selectedNode?.let{index->
            val a=obj.points.getOrNull(index)?.let{VectorGeometry.mapped(obj,it)}
            if(a!=null){val anchorDistance=hypot(a.x-point.x,a.y-point.y)
                val candidate=listOf(index-1,index+1).mapNotNull{i->obj.points.getOrNull(i)?.let{p->i to VectorGeometry.mapped(obj,p)}}
                    .filter{(_,p)->hypot(p.x-point.x,p.y-point.y)<=radius && hypot(p.x-point.x,p.y-point.y)<anchorDistance}
                    .minByOrNull{(_,p)->hypot(p.x-point.x,p.y-point.y)}
                if(candidate!=null){hit=VectorGeometry.Hit(obj.id,candidate.first);handle=true}
            }
        }}
        if(hit==null)hit=VectorGeometry.hitAnchor(document,selectedId,point,radius)
        if(hit==null){val obj=document.hit(point,8*density/zoom);selectedId=obj?.id;selectedNode=null;onSelection();invalidate();return}
        val h=hit!!;selectedId=h.objectId;val obj=selected?:return;document.active=document.layers.indexOf(document.owner(obj.id))
        val picked=h.index
        if(!handle)selectedNode=picked
        if(document.owner(obj.id)?.effectiveLocked==true){activity.message("This layer is locked");onSelection();return}
        before=document.encode();if(obj.kind=="Pen")obj.toBezier();node=picked;mode=if(handle)"handle"else"node";origin=point;nodeMoved=false
        nodeOriginal=obj.points.map{PointF(it.x,it.y)};onSelection();invalidate()
    }
    fun closePath(){
        val obj=selected?:return
        if(document.owner(obj.id)?.effectiveLocked==true){activity.message("This layer is locked");return}
        if(obj.kind !in listOf("Pen","Line","Bezier"))return
        edit{obj.closed=!obj.closed;if(!obj.closed)obj.fill=null;obj.geometryChanged()};joiningPaths=false
    }
    fun joinPathMode(){
        cancelGesture();val obj=selected?:return
        if(obj.isClosed || document.owner(obj.id)?.effectiveLocked==true){activity.message("Choose an unlocked open path");return}
        addingAnchor=false;joiningPaths=!joiningPaths;onSelection()
        if(joiningPaths)activity.message("Tap an endpoint of another path to join it")
    }
    private fun joinAt(point:PointF){
        val first=selected?:return;val layer=document.owner(first.id)?:return
        val candidates=layer.objects.filter{it.id!=first.id && !it.isClosed && it.kind in listOf("Pen","Line","Bezier")}
        val hit=candidates.flatMap{o->listOf(0,o.points.lastIndex).map{i->Triple(o,i,VectorGeometry.mapped(o,o.points[i]))}}
            .filter{hypot(it.third.x-point.x,it.third.y-point.y)<=28*density/zoom}.minByOrNull{hypot(it.third.x-point.x,it.third.y-point.y)}
        if(hit==null){activity.message("Tap an endpoint on another open path in the same layer");return}
        val other=hit.first;val reverseFirst=selectedNode==0;val reverseOther=hit.second!=0
        edit{
            require(!layer.effectiveLocked){"This layer is locked"}
            first.toBezier();other.toBezier()
            if(reverseFirst)first.points.reverse()
            val inverse=Matrix();check(first.matrix.invert(inverse))
            val incoming=(if(reverseOther)other.points.asReversed()else other.points).map{p->VectorGeometry.mapped(other,p).let{mapped->val xy=floatArrayOf(mapped.x,mapped.y);inverse.mapPoints(xy);PointF(xy[0],xy[1])}}
            require(first.points.size+incoming.size+2<=12289){"Joined path exceeds point limit"}
            val a=first.points.last();val b=incoming.first()
            first.points.add(PointF(a.x+(b.x-a.x)/3,a.y+(b.y-a.y)/3));first.points.add(PointF(a.x+(b.x-a.x)*2/3,a.y+(b.y-a.y)*2/3));first.points.addAll(incoming)
            first.geometryChanged();layer.objects.remove(other)
        }
        joiningPaths=false;selectedNode=null;onSelection();invalidate()
    }
    fun fillAt(x:Float,y:Float,color:Int):Boolean{
        cancelGesture();val point=pagePoint(x,y)
        if(point.x !in 0f..document.width.toFloat() || point.y !in 0f..document.height.toFloat())return false
        val obj=document.layers.asReversed().filter{it.effectiveVisible && it.opacity>0}.flatMap{it.objects.asReversed()}.firstOrNull{it.containsInterior(point)}
        if(obj==null){activity.message("Drop inside a closed path. Use Nodes → Close path for freehand lines.");return false}
        if(document.owner(obj.id)?.effectiveLocked==true){activity.message("This layer is locked");return false}
        edit{obj.fill=color};RecentColors(activity).record(color);return true
    }
    private fun begin(point:PointF){
        if(tool=="Nodes"){if(joiningPaths)joinAt(point)else if(addingAnchor)addAnchor(point)else beginNodes(point);return}
        if(tool=="Select"){
            var obj=selected
            if(obj!=null){
                val r=obj.bounds();val tolerance=14*density/zoom
                    val handle=corners(r).indexOfFirst{hypot(it.x-point.x,it.y-point.y)<tolerance}
                    if(handle>=0){mode="scale";anchor=corners(r)[(handle+2)%4]}
                    else if(hypot(r.centerX()-point.x,r.top-24*density/zoom-point.y)<tolerance){mode="rotate";anchor=PointF(r.centerX(),r.centerY())}
                    else if(obj.hit(point,tolerance/2))mode="move"
            }
            if(mode.isEmpty()){
                obj=document.hit(point,8*density/zoom);selectedId=obj?.id
                if(obj!=null){document.active=document.layers.indexOf(document.owner(obj.id));mode="move"}
            }
            if(obj!=null && document.owner(obj.id)?.effectiveLocked==true){activity.message("This layer is locked");mode=""}
            if(obj!=null && mode.isNotEmpty()){before=document.encode();originalMatrix=Matrix(obj.matrix);origin=point}
            onSelection();invalidate();return
        }
        if(document.layers[document.active].effectiveLocked){activity.message("This layer is locked");return}
        before=document.encode();selectedId=null;selectedNode=null;filtered=PointF(point.x,point.y);sampleTime=android.os.SystemClock.uptimeMillis()
        val points=mutableListOf(PointF(point.x,point.y));if(tool!="Pen")points.add(PointF(point.x,point.y))
        draft=VectorObject(document.nextId(),tool,points,ink,if(tool in listOf("Rectangle","Ellipse"))fillColor else null,strokeWidth)
        onSelection()
    }
    private fun move(raw:PointF,time:Long=android.os.SystemClock.uptimeMillis(),release:Boolean=false){
        var point=raw
        draft?.let{obj->
            if(obj.kind=="Pen"){
                if(stabilization>0f && !release){val dt=((time-sampleTime).coerceIn(1,40))/1000f;val tau=.14f*stabilization*stabilization;val a=1f-exp(-dt/tau.coerceAtLeast(.00001f));filtered.offset((raw.x-filtered.x)*a,(raw.y-filtered.y)*a);point=PointF(filtered.x,filtered.y)}else filtered=PointF(raw.x,raw.y)
                sampleTime=time
                if(hypot(point.x-obj.points.last().x,point.y-obj.points.last().y)>1*density/zoom){obj.points.add(point);if(obj.points.size>2048){val reduced=obj.points.filterIndexed{i,_->i%2==0 || i==obj.points.lastIndex};obj.points.clear();obj.points.addAll(reduced)}}
            }else obj.points[1]=point
            obj.geometryChanged();invalidate();return
        }
        val obj=selected?:return
        when(mode){
            "node","handle"->{
                if(!nodeMoved && hypot(point.x-origin.x,point.y-origin.y)<3*density/zoom)return
                nodeMoved=true;val inverse=Matrix();if(obj.matrix.invert(inverse)){val delta=floatArrayOf(point.x-origin.x,point.y-origin.y);inverse.mapVectors(delta)
                    val affected=if(mode=="node" && obj.kind=="Bezier")listOf(node-1,node,node+1)else listOf(node)
                    for(i in affected)if(i in obj.points.indices){val p=nodeOriginal[i];obj.points[i]=PointF(p.x+delta[0],p.y+delta[1])};obj.geometryChanged()
                }
            }
            "move"->obj.matrix=Matrix(originalMatrix).apply{postTranslate(point.x-origin.x,point.y-origin.y)}
            "scale"->{
                val sx=if(abs(origin.x-anchor.x)>1e-4)((point.x-anchor.x)/(origin.x-anchor.x)).coerceIn(.05f,20f) else 1f
                val sy=if(abs(origin.y-anchor.y)>1e-4)((point.y-anchor.y)/(origin.y-anchor.y)).coerceIn(.05f,20f) else 1f
                obj.matrix=Matrix(originalMatrix).apply{postConcat(Matrix().apply{setScale(sx,sy,anchor.x,anchor.y)})}
            }
            "rotate"->{val angle=(atan2(point.y-anchor.y,point.x-anchor.x)-atan2(origin.y-anchor.y,origin.x-anchor.x))*180f/Math.PI.toFloat()
                obj.matrix=Matrix(originalMatrix).apply{postConcat(Matrix().apply{setRotate(angle,anchor.x,anchor.y)})}}
        };invalidate()
    }
    private fun finish(){
        val previous=before
        if(previous!=null){
            if(mode in listOf("node","handle") && !nodeMoved){document.restore(previous);before=null;draft=null;pointer=-1;mode="";invalidate();onSelection();return}
            draft?.let{if(it.kind=="Pen"){val reduced=VectorGeometry.simplify(it.points,1.2f*density/zoom);it.points.clear();it.points.addAll(reduced);it.toBezier()};document.layers[document.active].objects.add(it);selectedId=null}
            runCatching{document.commit(previous)}.onSuccess{draft?.let{obj->RecentColors(activity).record(obj.stroke);obj.fill?.let{RecentColors(activity).record(it)}}}.onFailure{document.restore(previous);selectedId=null;activity.message(it.message?:"Could not add object")}
            onChanged()
        }
        before=null;draft=null;pointer=-1;mode="";invalidate();onSelection()
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        movingGroup?.let{id->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{cancelGesture();val group=document.groups.firstOrNull{it.id==id};if(group==null || group.locked){activity.message("Unlock this group first");return true};groupBefore=document.encode();groupOrigin=pagePoint(e.x,e.y);groupMatrices=document.layers.filter{it.groupId==id && !it.locked}.flatMap{it.objects}.map{it to Matrix(it.matrix)}}
                MotionEvent.ACTION_MOVE->{if(e.pointerCount==1 && groupBefore!=null){val at=pagePoint(e.x,e.y);for((obj,matrix)in groupMatrices){obj.matrix.set(matrix);obj.matrix.postTranslate(at.x-groupOrigin.x,at.y-groupOrigin.y)};invalidate()}}
                MotionEvent.ACTION_UP->{groupBefore?.let{state->runCatching{document.commit(state)}.onFailure{document.restore(state);activity.message(it.message?:"Cannot move group")};onChanged()};groupBefore=null;groupMatrices=emptyList();invalidate()}
                MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL->cancelGesture()
            };return true
        }

        if(e.actionMasked==MotionEvent.ACTION_DOWN){atlasAnimator?.cancel();atlasAnimator=null}
        if(activity.atlas?.touch(e,this,screenMatrix(),::setAtlasCamera)==true)return true
        if(guides.touch(e,pagePoint(e.x,e.y),document.width.toFloat(),document.height.toFloat(),32*density/zoom)){invalidate();return true}
        val tappedFingers=historyTap.observe(e)
        parent.requestDisallowInterceptTouchEvent(true)
        val hasPen=(0 until e.pointerCount).any{e.getToolType(it) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)}
        if(e.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER){if(e.actionMasked==MotionEvent.ACTION_DOWN){cancelGesture();activity.message("Use Select → Object → Delete to remove a vector object")};return true}
        if(!e.x.isFinite() || !e.y.isFinite()){cancelGesture();return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{val dismissed=onDismissPanel();activity.deferAutosave();pointer=e.getPointerId(0);pen=hasPen;mode="";multi=false;holdOrigin=PointF(e.x,e.y)
                removeCallbacks(holdColor);if(tool=="Pen" && activity.getSharedPreferences("studio",0).getBoolean("holdEyedropper",true))postDelayed(holdColor,450)
                if(!dismissed && (hasPen || !activity.penOnlyInput || tool in listOf("Nodes","Select")))begin(pagePoint(e.x,e.y))}
            MotionEvent.ACTION_POINTER_DOWN->{removeCallbacks(holdColor);pickingColor=false;pickTouch=null;if(pen && pointer>=0)return true;cancelGesture();multi=true;atlasGestureStartZoom=zoom;gestureCenter=PointF((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2);gestureDistance=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));gestureAngle=atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0))}
            MotionEvent.ACTION_MOVE->{
                if(pickingColor){pickAt(e.x,e.y);return true}
                if(hypot(e.x-holdOrigin.x,e.y-holdOrigin.y)>(if(pen)6 else 12)*density)removeCallbacks(holdColor)
                if(multi && e.pointerCount>=2){val mid=PointF((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2);val distance=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));val page=pagePoint(gestureCenter.x,gestureCenter.y)
                    val angle=atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0));var delta=angle-gestureAngle
                    while(delta>Math.PI)delta-=(2*Math.PI).toFloat();while(delta< -Math.PI)delta+=(2*Math.PI).toFloat();rotation+=delta*180f/Math.PI.toFloat();gestureAngle=angle
                    if(gestureDistance>0)zoom=(zoom*distance/gestureDistance).coerceIn(.01f,64f);if(zoom<fitScale*.55f && zoom<atlasGestureStartZoom*.85f)onAtlas();val now=viewPoint(page.x,page.y);pan.offset(mid.x-now.x,mid.y-now.y);gestureCenter=mid;gestureDistance=distance;invalidate();return true}
                val index=e.findPointerIndex(pointer);if(index>=0 && !multi){if(draft?.kind=="Pen")for(h in 0 until e.historySize)move(pagePoint(e.getHistoricalX(index,h),e.getHistoricalY(index,h)),e.getHistoricalEventTime(h));move(pagePoint(e.getX(index),e.getY(index)),e.eventTime)}
            }
            MotionEvent.ACTION_POINTER_UP->{if(pen && e.getPointerId(e.actionIndex)==pointer){move(pagePoint(e.getX(e.actionIndex),e.getY(e.actionIndex)),e.eventTime,release=true);finish()}}
            MotionEvent.ACTION_UP->{removeCallbacks(holdColor);if(pickingColor){pickingColor=false;pickTouch=null;pointer=-1;invalidate();performClick();return true};if(multi && activity.atlas==null && zoom<fitScale*.48f){multi=false;pointer=-1;onAtlas();return true};if(multi){when(tappedFingers){2->if(activity.twoFingerUndo)undo();3->if(activity.threeFingerRedo)redo()}};if(!multi && tappedFingers==0 && pointer>=0){move(pagePoint(e.x,e.y),e.eventTime,release=true);finish()};multi=false;pointer=-1;performClick()}
            MotionEvent.ACTION_CANCEL->{cancelGesture();activity.scheduleSave()}
        };return true
    }
    override fun onDetachedFromWindow(){removeCallbacks(holdColor);pickBitmap?.recycle();pickBitmap=null;atlasAnimator?.cancel();atlasAnimator=null;super.onDetachedFromWindow()}
    override fun performClick():Boolean{super.performClick();return true}
}
