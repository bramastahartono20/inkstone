package art.inkstone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject

class PaintCanvas(context: Context, val session: DrawingSession) : View(context) {
    internal val guides=DrawingGuide(context)
    internal val liquify=RasterLiquify(this,session)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val transform = Matrix()
    private val inverse = Matrix()
    private var gestureStartZoom=1f
    private var fitScale=1f
    internal var onAtlas:()->Unit={}
    private var zoom = 1f
    private var rotation = 0f
    private var panX = 0f; private var panY = 0f
    private var fitted = false
    private var activePointer = -1
    private var drawing = false
    private var pen = false
    internal val stylusButtons=StylusButtons(::stylusPressed)
    private fun stylusPressed(){
        shapeEligible=false;removeCallbacks(shapeHold);removeCallbacks(longPress)
        when(session.stylusButtonAction){
            StylusAction.UNDO,StylusAction.REDO->{
                if(drawing){session.stroke(3,floatArrayOf());drawing=false}
                finishQuickShape(cancel=true)
                session.command(if(session.stylusButtonAction==StylusAction.UNDO)"undo" else "redo");onChanged()
            }
            StylusAction.TOGGLE->{
                if(drawing){session.stroke(2,floatArrayOf());drawing=false}
                session.tool=if(session.tool=="Erase")"Paint" else "Erase";onChanged()
            }
            else->{}
        }
        invalidate()
    }
    private fun erasing(event:MotionEvent,index:Int)=event.getToolType(index)==MotionEvent.TOOL_TYPE_ERASER || (stylusButtons.down && session.stylusButtonAction==StylusAction.ERASE)
    override fun onGenericMotionEvent(event:MotionEvent):Boolean{
        if(event.actionMasked in listOf(MotionEvent.ACTION_BUTTON_PRESS,MotionEvent.ACTION_BUTTON_RELEASE)){stylusButtons.observe(event);onInputDevice(event);return true}
        return super.onGenericMotionEvent(event)
    }
    private var multi = false
    private val historyTap=MultiFingerTap(12*resources.displayMetrics.density)
    private var maxPointers = 0
    private var downTime = 0L
    private var downX = 0f; private var downY = 0f
    private var moved = false
    private var gestureX = 0f; private var gestureY = 0f
    private var gestureDistance = 0f; private var gestureAngle = 0f
    private var gestureA = -1; private var gestureB = -1
    private var gestureStartTime = 0L
    private var gestureStartDistance = 0f
    private var gestureLastDistance = 0f
    private var gestureLastTime = 0L
    private var gesturePinchVelocity = 0f
    private var gestureMinDistance = 0f
    private var gestureMaxDistance = 0f
    private var gesturePinching = false
    private var fitAnimator: ValueAnimator? = null
    private var hover: PointF? = null
    private var hoverEraser=false
    private val selectionPoints = mutableListOf<PointF>()
    private var selectionPath: Path? = null
    internal val quickShapeEdit=QuickShapeEdit(this,session)
    internal fun finishQuickShape(cancel:Boolean=false){quickShapeEdit.finish(cancel)}
    internal val rasterTransform=RasterTransform(this,session)
    internal val transformZoom get()=zoom
    internal fun transformDocumentPoint(x:Float,y:Float)=documentPoint(x,y)
    internal fun transformViewPoint(x:Float,y:Float):PointF{updateMatrix();val p=floatArrayOf(x,y);transform.mapPoints(p);return PointF(p[0],p[1])}
    internal fun syncTransformTool(){if(session.tool=="Transform")rasterTransform.ensure()else rasterTransform.finish()}
    private var picking = false
    private var pickPoint: PointF? = null
    var selectionShape = "Freehand"
    var selectionMode="Replace"
    var selectionThreshold=.12f
    private var selectionBitmap:Bitmap?=null
    private var selectionVersion=-1L
    internal fun syncSelection(){
        val version=session.info.optLong("selectionVersion")
        if(version==selectionVersion)return
        selectionVersion=version
        if(!session.info.optBoolean("selection")){deselect();return}
        session.selectionPreview{data->
            if(version!=selectionVersion || !session.info.optBoolean("selection"))return@selectionPreview
            val w=data.optInt("width");val h=data.optInt("height");if(w<=0 || h<=0)return@selectionPreview
            val runs=data.optJSONArray("runs")?:return@selectionPreview;val pixels=IntArray(w*h);var offset=0
            val region=Region()
            for(n in 0 until runs.length()){val pair=runs.getJSONArray(n);val count=pair.getInt(0);val alpha=pair.getInt(1);if(count<0 || offset+count>pixels.size)return@selectionPreview
                java.util.Arrays.fill(pixels,offset,offset+count,(alpha shl 24) or 0x305770);offset+=count}
            for(y in 0 until h){var start=-1;for(x in 0..w){val selected=x<w && Color.alpha(pixels[y*w+x])>=128;if(selected && start<0)start=x;if(!selected && start>=0){region.op(start,y,x,y+1,Region.Op.UNION);start=-1}}}
            selectionBitmap?.recycle();selectionBitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
            selectionPath=region.boundaryPath.apply{transform(Matrix().apply{setScale(session.canvasWidth.toFloat()/w,session.canvasHeight.toFloat()/h)})};invalidate()
        }
    }
    var onDismissPanel: () -> Boolean = { false }
    var onChanged: () -> Unit = {}
    var onStrokeStart: () -> Unit = {}
    var onColor: (Int) -> Unit = {}
    var onToggleInterface: () -> Unit = {}
    var onGesture: (String) -> Unit = {}
    var onInputDevice: (MotionEvent) -> Unit = {}
    private val shapePoints=mutableListOf<PointF>()
    private var snapped=false
    private var shapeEligible=true
    private var holdX=0f;private var holdY=0f
    private val shapeHold=Runnable {
        if(drawing && shapeEligible && !multi && !snapped && session.quickShape && session.tool=="Paint"){
            QuickShape.fit(shapePoints.filterIndexed{i,_->i%max(1,shapePoints.size/256)==0})?.let{fit->
                quickShapeEdit.start(fit);snapped=true;onGesture(fit.name+" created · drag handles to edit")
            }
        }
    }
    private fun recordShape(samples:FloatArray,x:Float,y:Float){
        if(!shapeEligible||!session.quickShape||session.tool!="Paint"||snapped)return
        for(i in samples.indices step 4){val p=PointF(samples[i],samples[i+1]);if(shapePoints.isEmpty()||hypot(p.x-shapePoints.last().x,p.y-shapePoints.last().y)>1f)shapePoints.add(p)}
        if(shapePoints.size>4096){val reduced=shapePoints.filterIndexed{i,_->i%2==0};shapePoints.clear();shapePoints.addAll(reduced)}
        if(hypot(x-holdX,y-holdY)>3*dp){holdX=x;holdY=y;removeCallbacks(shapeHold);postDelayed(shapeHold,650)}
    }
    private var holdCanPick=false
    private val longPress = Runnable {
        if (session.holdEyedropper && holdCanPick && !multi && !moved && activePointer >= 0 && session.tool == "Paint") {
            if(quickShapeEdit.active)return@Runnable
            removeCallbacks(shapeHold);if(drawing)session.stroke(3, floatArrayOf()); drawing = false; picking = true
            pick(downX, downY)
        }
    }
    private val dp get() = resources.displayMetrics.density

    init {
        isFocusable = true; contentDescription = "Drawing canvas. Two fingers pan, pinch and rotate. Two-finger tap undo; three-finger tap redo."
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun fit() {
        if (width == 0 || height == 0) return
        zoom = min((width - 100 * dp) / session.canvasWidth, (height - 70 * dp) / session.canvasHeight).coerceAtLeast(.02f)
        fitScale=zoom
        rotation = 0f; panX = 0f; panY = 0f; fitted = true; invalidate()
    }
    private var fitFactor=1f
    internal fun atlasOverview(){fitFactor=.38f;animateFit();fitFactor=1f}
    internal fun captureAtlas(){updateMatrix();(context as? MainActivity)?.atlas?.capture(transform,width,height)}
    internal fun setAtlasCamera(matrix:Matrix){
        fitScale=min((width-100*dp)/session.canvasWidth,(height-70*dp)/session.canvasHeight).coerceAtLeast(.02f)
        val values=FloatArray(9);matrix.getValues(values);zoom=hypot(values[0],values[3]);rotation=atan2(values[3],values[0])*180f/Math.PI.toFloat()
        val center=floatArrayOf(session.canvasWidth/2f,session.canvasHeight/2f);matrix.mapPoints(center);panX=center[0]-width/2f;panY=center[1]-height/2f;fitted=true;updateMatrix();invalidate()
    }
    fun animateFit(onEnd: () -> Unit = {}) {
        if (width == 0 || height == 0) return
        fitAnimator?.cancel()
        val fittedZoom = min((width - 100 * dp) / session.canvasWidth, (height - 70 * dp) / session.canvasHeight).coerceAtLeast(.02f)
        val targetZoom=fittedZoom*fitFactor
        val startZoom = zoom
        val startPanX = panX
        val startPanY = panY
        var currentRot = rotation % 360f
        if (currentRot > 180f) currentRot -= 360f
        if (currentRot < -180f) currentRot += 360f
        val startRot = currentRot

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                zoom = startZoom + (targetZoom - startZoom) * f
                panX = startPanX + (0f - startPanX) * f
                panY = startPanY + (0f - startPanY) * f
                rotation = startRot + (0f - startRot) * f
                fitted = true
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled=false
                override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if(cancelled)return
                    zoom=targetZoom;fitScale=fittedZoom;rotation=0f;panX=0f;panY=0f;fitted=true;updateMatrix();invalidate()
                    fitAnimator = null
                    onEnd()
                }
            })
        }
        fitAnimator = animator
        animator.start()
    }
    fun resetRotation() {
        if (rotation == 0f) return
        fitAnimator?.cancel()
        var currentRot = rotation % 360f
        if (currentRot > 180f) currentRot -= 360f
        if (currentRot < -180f) currentRot += 360f
        val startRot = currentRot
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedFraction
                rotation = startRot * (1f - f)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled=false
                override fun onAnimationCancel(animation:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if(cancelled)return
                    fitAnimator=null
                    rotation = 0f
                    invalidate()
                }
            })
        }
        fitAnimator=animator
        animator.start()
    }
    fun deselect() { selectionBitmap?.recycle();selectionBitmap=null;selectionPath = null; selectionPoints.clear(); invalidate() }
    fun zoomBy(factor: Float) { zoom = (zoom * factor).coerceIn(.02f, 30f); invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { val camera=(context as? MainActivity)?.atlas?.pageCamera(w,h);if(camera!=null)setAtlasCamera(camera)else if (!fitted || oldw != w || oldh != h) fit() }
    private fun updateMatrix() {
        session.viewZoom=zoom
        transform.reset(); transform.postTranslate(-session.canvasWidth / 2f, -session.canvasHeight / 2f)
        transform.postScale(zoom, zoom); transform.postRotate(rotation)
        transform.postTranslate(width / 2f + panX, height / 2f + panY)
        transform.invert(inverse)
    }
    private fun documentPoint(x: Float, y: Float): PointF {
        updateMatrix(); val p = floatArrayOf(x, y); inverse.mapPoints(p); return PointF(p[0], p[1])
    }

    internal fun colorDropPoint(x:Float,y:Float)=documentPoint(x,y)
    private var fillBefore:Bitmap?=null
    private var fillCenter=PointF()
    private var fillAmount=1f
    private var fillWaiting=false
    private var fillAnimator:android.animation.ValueAnimator?=null
    fun prepareFillAnimation(point:PointF){
        cancelFillAnimation()
        if(!android.animation.ValueAnimator.areAnimatorsEnabled())return
        val scale=min(1f,2048f/max(session.bitmap.width,session.bitmap.height))
        fillBefore=runCatching{Bitmap.createScaledBitmap(session.bitmap,max(1,(session.bitmap.width*scale).toInt()),max(1,(session.bitmap.height*scale).toInt()),true).let{if(it===session.bitmap)it.copy(Bitmap.Config.ARGB_8888,false) else it}}.getOrNull()
        fillCenter=point;fillAmount=0f;fillWaiting=fillBefore!=null
    }
    fun onRenderedFrame(){
        rasterTransform.onFrame()
        if(fillWaiting){fillWaiting=false;fillAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply{
            duration=420;interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener{fillAmount=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){fillBefore?.recycle();fillBefore=null;invalidate()}});start()
        }}
        invalidate()
    }
    fun cancelFillAnimation(){fillAnimator?.cancel();fillAnimator=null;fillBefore?.recycle();fillBefore=null;fillWaiting=false;fillAmount=1f;invalidate()}
    override fun onDraw(canvas: Canvas) {
        updateMatrix()
        val atlas=(context as? MainActivity)?.atlas
        if(atlas!=null)atlas.draw(canvas,transform,width,height)else{
        canvas.drawColor(StudioTheme.of(context).workspace)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=dp*.65f
        paint.color=if(session.lightInterface)0x14000000 else 0x10ffffff
        val spacing=16*dp
        var gx=0f;while(gx<width){canvas.drawLine(gx,0f,gx,height.toFloat(),paint);gx+=spacing}
        var gy=0f;while(gy<height){canvas.drawLine(0f,gy,width.toFloat(),gy,paint);gy+=spacing}
        }
        updateMatrix(); canvas.save(); canvas.concat(transform)
        paint.color = 0xffe0e0e0.toInt(); paint.style = Paint.Style.FILL; paint.shader = null
        val w = session.canvasWidth.toFloat(); val h = session.canvasHeight.toFloat()
        val outsideSave=canvas.save()
        canvas.clipOutRect(0f,0f,w,h)
        paint.alpha=110
        if(!rasterTransform.draw(canvas,true))for(tile in session.exteriorTiles)canvas.drawBitmap(tile.bitmap,null,RectF(tile.x,tile.y,tile.x+128,tile.y+128),paint)
        paint.alpha=255;canvas.restoreToCount(outsideSave)
        canvas.drawRect(0f, 0f, w, h, paint)
        if (!session.info.optBoolean("backgroundVisible", true)) {
            val tile = 24f
            paint.color = 0xffb5b5b5.toInt()
            var y = 0f
            while (y < h) { var x = 0f; while (x < w) {
                if (((x / tile).toInt() + (y / tile).toInt()) % 2 == 0) canvas.drawRect(x, y, min(x + tile, w), min(y + tile, h), paint)
                x += tile
            }; y += tile }
        }
        fillBefore?.let{canvas.drawBitmap(it,null,RectF(0f,0f,w,h),paint)}
        val fillSave=canvas.save()
        if(fillBefore!=null){val radius=hypot(max(fillCenter.x,w-fillCenter.x),max(fillCenter.y,h-fillCenter.y))*fillAmount
            canvas.clipPath(Path().apply{addCircle(fillCenter.x,fillCenter.y,radius,Path.Direction.CW)})}
        if(!rasterTransform.draw(canvas))canvas.drawBitmap(session.bitmap, 0f, 0f, paint)
        canvas.restoreToCount(fillSave)
        guides.draw(canvas,w,h,zoom)
        liquify.draw(canvas,zoom)
        if(session.symmetryMode!="Off"){
            paint.color=0x997fc7ef.toInt();paint.strokeWidth=1.2f*dp/zoom;paint.style=Paint.Style.STROKE
            val mode=session.symmetryMode
            if(mode in listOf("Vertical","Quadrant","Radial 4"))canvas.drawLine(w/2,0f,w/2,h,paint)
            if(mode in listOf("Horizontal","Quadrant","Radial 4"))canvas.drawLine(0f,h/2,w,h/2,paint)
            if(mode=="Radial 4"){paint.color=0x557fc7ef;canvas.drawLine(0f,h/2-w/2,w,h/2+w/2,paint);canvas.drawLine(0f,h/2+w/2,w,h/2-w/2,paint)}
            paint.style=Paint.Style.FILL
        }
        if (selectionPath != null && !rasterTransform.displaying) {
            paint.color=Color.argb((session.selectionVisibility*255).toInt(),70,90,100)
            paint.style=Paint.Style.FILL
            if(selectionBitmap!=null){paint.alpha=(session.selectionVisibility*255).toInt();canvas.drawBitmap(selectionBitmap!!,null,RectF(0f,0f,w,h),paint);paint.alpha=255}else canvas.drawPath(selectionPath!!,paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f * dp / zoom
            paint.pathEffect = DashPathEffect(floatArrayOf(5 * dp / zoom, 5 * dp / zoom), 0f)
            paint.color = Color.WHITE; canvas.drawPath(selectionPath!!, paint)
            paint.color = Color.BLACK; paint.pathEffect = DashPathEffect(floatArrayOf(5 * dp / zoom, 5 * dp / zoom), 5 * dp / zoom)
            canvas.drawPath(selectionPath!!, paint); paint.pathEffect = null; paint.style = Paint.Style.FILL
        }
        if(session.tool=="Transform")rasterTransform.drawHandles(canvas)
        quickShapeEdit.draw(canvas)
        canvas.restore()
        if (hover != null && session.cursor) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp
            paint.color = if(session.cursorStyle=="Dark")0xff333333.toInt() else 0xffeeeeee.toInt();paint.alpha=(session.cursorOpacity*255).toInt(); canvas.drawCircle(hover!!.x, hover!!.y, session.cursorSize(hoverEraser || (stylusButtons.down && session.stylusButtonAction==StylusAction.ERASE)) * (if(session.dynamicBrushScaling)zoom else 1f) * .5f + dp, paint)
            paint.color = if(session.cursorStyle=="Light")0xffeeeeee.toInt() else 0xff333333.toInt();paint.alpha=(session.cursorOpacity*255).toInt(); canvas.drawCircle(hover!!.x, hover!!.y, session.cursorSize(hoverEraser || (stylusButtons.down && session.stylusButtonAction==StylusAction.ERASE)) * (if(session.dynamicBrushScaling)zoom else 1f) * .5f, paint)
            paint.alpha=255;paint.style = Paint.Style.FILL
        }
        pickPoint?.let {
            paint.color = session.color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 10 * dp
            canvas.drawCircle(it.x, it.y - 36 * dp, 28 * dp, paint)
            paint.color = Color.WHITE; paint.strokeWidth = 2 * dp
            canvas.drawCircle(it.x, it.y - 36 * dp, 33 * dp, paint); paint.style = Paint.Style.FILL
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        stylusButtons.observe(event)
        hoverEraser=event.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER
        hover = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) null else PointF(event.x, event.y)
        onInputDevice(event); invalidate(); return true
    }
    private fun pick(x: Float, y: Float) {
        val p = documentPoint(x, y); pickPoint = PointF(x, y)
        session.command("pick", JSONObject().put("x", p.x).put("y", p.y)) {
            if (it.has("color")) { session.color = it.getLong("color").toInt(); onColor(session.color); invalidate() }
        }
    }
    private fun strokeTimes(event:MotionEvent,includeHistory:Boolean):FloatArray {
        val history=if(includeHistory)event.historySize else 0
        return FloatArray(history+1){i->((if(i<history)event.getHistoricalEventTime(i)else event.eventTime)-event.downTime).toFloat()}
    }
    private fun strokeSamples(event: MotionEvent, index: Int, includeHistory: Boolean): FloatArray {
        val samples = mutableListOf<Float>()
        val maxPressure = event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE, event.source)?.max?.coerceAtLeast(1f) ?: 1f
        fun add(x: Float, y: Float, pressure: Float, tilt: Float) {
            val p = documentPoint(x, y)
            samples.add(p.x); samples.add(p.y); samples.add(if (pen && session.pressureEnabled) (pressure / maxPressure).coerceIn(0f, 1f) else 1f); samples.add(tilt)
        }
        if (includeHistory) for (i in 0 until event.historySize) {
            add(event.getHistoricalX(index, i), event.getHistoricalY(index, i), event.getHistoricalPressure(index, i), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, i))
        }
        add(event.getX(index), event.getY(index), event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index))
        return samples.toFloatArray()
    }
    private fun startGesture(event: MotionEvent, exclude: Int = -1) {
        val indexes = (0 until event.pointerCount).filter { it != exclude }
        if (indexes.size < 2) { gestureA = -1; gestureB = -1; gesturePinching = false; return }
        val a = indexes[0]; val b = indexes[1]; gestureA = event.getPointerId(a); gestureB = event.getPointerId(b)
        gestureX = (event.getX(a) + event.getX(b)) * .5f; gestureY = (event.getY(a) + event.getY(b)) * .5f
        val dx = event.getX(b) - event.getX(a); val dy = event.getY(b) - event.getY(a)
        gestureDistance = hypot(dx, dy); gestureAngle = atan2(dy, dx)
        val now = SystemClock.uptimeMillis()
        gestureStartZoom=zoom
        gestureStartTime = now
        gestureStartDistance = gestureDistance
        gestureLastDistance = gestureDistance
        gestureLastTime = now
        gesturePinchVelocity = 0f
        gestureMinDistance = gestureDistance
        gestureMaxDistance = gestureDistance
        gesturePinching = true
    }
    private fun moveGesture(event: MotionEvent) {
        if(session.canvasLocked){moved=true;return}
        val a = event.findPointerIndex(gestureA); val b = event.findPointerIndex(gestureB)
        if (a < 0 || b < 0) { startGesture(event); return }
        val x = (event.getX(a) + event.getX(b)) * .5f; val y = (event.getY(a) + event.getY(b)) * .5f
        val dx = event.getX(b) - event.getX(a); val dy = event.getY(b) - event.getY(a)
        val distance = hypot(dx, dy); val angle = atan2(dy, dx)
        val now = SystemClock.uptimeMillis()
        val dt = (now - gestureLastTime).coerceAtLeast(1)
        val contraction = (gestureLastDistance - distance) / dt.toFloat()
        gesturePinchVelocity = gesturePinchVelocity * 0.35f + contraction * 0.65f
        gestureLastDistance = distance
        gestureLastTime = now
        if (distance < gestureMinDistance) gestureMinDistance = distance
        if (distance > gestureMaxDistance) gestureMaxDistance = distance
        if (hypot(x - gestureX, y - gestureY) > 2 * dp || abs(distance - gestureDistance) > 2 * dp || abs(angle - gestureAngle) > .015f) moved = true
        val anchor = documentPoint(gestureX, gestureY)
        if (gestureDistance > 10 * dp) zoom = (zoom * distance / gestureDistance).coerceIn(.02f, 30f)
        var delta = angle - gestureAngle
        if (delta > Math.PI) delta -= (2 * Math.PI).toFloat(); if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
        rotation += delta * 180f / Math.PI.toFloat()
        updateMatrix(); val mapped = floatArrayOf(anchor.x, anchor.y); transform.mapPoints(mapped)
        panX += x - mapped[0]; panY += y - mapped[1]
        if(zoom<fitScale*.55f && zoom<gestureStartZoom*.85f)onAtlas()
        gestureX = x; gestureY = y; gestureDistance = distance; gestureAngle = angle; invalidate()
    }
    private fun checkQuickPinch(): Boolean {
        if((context as? MainActivity)?.atlas!=null){gesturePinching=false;return false}
        if(gesturePinching && !session.canvasLocked && zoom<fitScale*.48f){gesturePinching=false;onAtlas();return true}
        if (!gesturePinching || !session.pinchToFit || session.canvasLocked) {
            gesturePinching = false
            return false
        }
        val now = SystemClock.uptimeMillis()
        val duration = now - gestureStartTime
        val dist = gestureLastDistance
        val ratio = if (gestureStartDistance > 0f) dist / gestureStartDistance else 1f
        val fastPinch = gesturePinchVelocity > 0.30f * dp && ratio < 0.90f
        val quickContraction = duration in 60..450 && ratio < 0.78f && gesturePinchVelocity > 0.08f * dp
        gesturePinching = false
        if ((fastPinch || quickContraction) && (zoom>=fitScale*.8f || gestureStartZoom>fitScale*1.1f)) {
            animateFit()
            onGesture("Fit canvas")
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){fitAnimator?.cancel();fitAnimator=null}
        updateMatrix();if((context as? MainActivity)?.atlas?.touch(event,this,transform,::setAtlasCamera)==true)return true
        if(guides.touch(event,documentPoint(event.x,event.y),session.canvasWidth.toFloat(),session.canvasHeight.toFloat(),32*resources.displayMetrics.density/zoom)){invalidate();return true}

        stylusButtons.observe(event)
        val tappedFingers=historyTap.observe(event)
        if(liquify.touch(event))return true
        if(quickShapeEdit.touch(event))return true
        parent.requestDisallowInterceptTouchEvent(true)
        if(session.tool=="Transform"){
            if(event.actionMasked==MotionEvent.ACTION_DOWN){requestFocus();if(onDismissPanel())return true;onInputDevice(event);if(session.info.getJSONArray("layers").getJSONObject(session.info.getInt("active")).optBoolean("locked")){onGesture("Layer is locked");return true}}
            return rasterTransform.touch(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fitAnimator?.cancel(); fitAnimator = null
                removeCallbacks(shapeHold);shapePoints.clear();snapped=false;shapeEligible=event.getToolType(0)!=MotionEvent.TOOL_TYPE_ERASER && !(stylusButtons.down && session.stylusButtonAction==StylusAction.ERASE);holdX=event.x;holdY=event.y
                requestFocus()
                val dismissed=onDismissPanel()
                downTime = SystemClock.uptimeMillis(); downX = event.x; downY = event.y; moved = false; multi = false; maxPointers = 1
                activePointer = event.getPointerId(0); pen = event.getToolType(0) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
                onInputDevice(event)
                removeCallbacks(longPress);holdCanPick=event.getToolType(0)!=MotionEvent.TOOL_TYPE_ERASER && !stylusButtons.down
                if(session.holdEyedropper && holdCanPick && session.tool=="Paint")postDelayed(longPress,450)
                if(dismissed)return true
                if (!pen && session.penOnly && session.tool in listOf("Paint", "Erase", "Smudge","Liquify")) return true
                if(session.info.getJSONArray("layers").getJSONObject(session.info.getInt("active")).optBoolean("locked") && session.tool in listOf("Paint","Smudge","Erase","Fill","Transform")){onGesture("Layer is locked");return true}
                if(pen && stylusButtons.down){
                    if(session.stylusButtonAction in listOf(StylusAction.UNDO,StylusAction.REDO))return true
                    if(session.stylusButtonAction==StylusAction.EYEDROPPER){picking=true;pick(event.x,event.y);return true}
                }
                when (session.tool) {
                    "Select" -> {
                        selectionPoints.clear()
                        if(selectionShape=="Automatic"){val at=documentPoint(event.x,event.y);session.command("selectionAutomatic",JSONObject().put("x",at.x.toInt()).put("y",at.y.toInt()).put("threshold",selectionThreshold).put("mode",selectionMode)){syncSelection()}}
                        else{selectionPoints.add(documentPoint(event.x,event.y));updateSelectionPath()}
                    }
                    "Eyedropper" -> { picking = true; pick(event.x, event.y) }
                    "Fill" -> { val p = documentPoint(event.x, event.y); session.command("flood", JSONObject().put("x", p.x).put("y", p.y).put("color", session.color.toLong() and 0xffffffffL)); onChanged() }
                    else -> {
                        drawing = true
                        onStrokeStart()
                        session.stroke(0, strokeSamples(event, 0, false), erasing(event,0),strokeTimes(event,false))
                        recordShape(strokeSamples(event,0,false),event.x,event.y)

                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(shapeHold)
                if (pen && drawing) return true
                removeCallbacks(longPress); if (drawing) { session.stroke(3, floatArrayOf()); drawing = false }
                multi = true; picking = false; pickPoint = null; maxPointers = max(maxPointers, event.pointerCount)
                startGesture(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) { moveGesture(event); return true }
                val index = event.findPointerIndex(activePointer); if (index < 0) return true
                val x = event.getX(index); val y = event.getY(index)
                if (hypot(x - downX, y - downY) > (if(pen)6 else 12) * dp) { moved = true; removeCallbacks(longPress) }
                if(pen && stylusButtons.down && session.stylusButtonAction==StylusAction.EYEDROPPER && !picking){
                    if(drawing){session.stroke(3,floatArrayOf());drawing=false};picking=true
                }
                when {
                    picking -> pick(x, y)
                    session.tool == "Select" && selectionShape!="Automatic" -> { selectionPoints.add(documentPoint(x, y)); updateSelectionPath() }
                    drawing && !snapped -> {val samples=strokeSamples(event,index,true);session.stroke(1,samples,erasing(event,index),strokeTimes(event,true));recordShape(samples,x,y)}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(shapeHold)
                if (pen && drawing && event.getPointerId(event.actionIndex) == activePointer) {
                    if(snapped)quickShapeEdit.release()else session.stroke(2,strokeSamples(event,event.actionIndex,false),erasing(event,event.actionIndex),strokeTimes(event,false))
                    drawing = false; onChanged()
                } else if (multi) {
                    if (!checkQuickPinch()) startGesture(event, event.actionIndex)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(shapeHold)
                removeCallbacks(longPress)
                if (multi) {
                    val pinched = checkQuickPinch()
                    if (!pinched && tappedFingers!=0) {
                        when (tappedFingers) { 2 -> { if(session.twoFingerUndo){session.command("undo")} }; 3 -> { if(session.threeFingerRedo){session.command("redo")} }; 4 -> if(session.fourFingerFullScreen)onToggleInterface() }
                        onChanged()
                    }
                } else if (event.flags and MotionEvent.FLAG_CANCELED != 0) {
                    if (drawing) session.stroke(3, floatArrayOf()); drawing = false
                } else when {
                    picking -> { picking = false; pickPoint = null; if (session.tool == "Eyedropper") session.tool = "Paint"; onChanged() }
                    session.tool == "Select" && selectionPoints.size > 1 -> {
                        val points = JSONArray(); selectionPoints.forEach { points.put(JSONArray().put(it.x).put(it.y)) }
                        session.command("select", JSONObject().put("shape", selectionShape).put("mode",selectionMode).put("points", points)){syncSelection()}; onChanged()
                    }
                    drawing -> {if(snapped)quickShapeEdit.release()else session.stroke(2,strokeSamples(event,0,false),erasing(event,0),strokeTimes(event,false));onChanged()}
                }
                drawing = false; activePointer = -1; multi = false; pickPoint = null; invalidate(); performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                gesturePinching=false;fitAnimator?.cancel();fitAnimator=null
                finishQuickShape(cancel=true)
                removeCallbacks(shapeHold)
                removeCallbacks(longPress); if (drawing) { session.stroke(3, floatArrayOf()); onChanged() }
                drawing = false; activePointer = -1; multi = false; picking = false; pickPoint = null; invalidate()
            }
        }
        return true
    }
    private fun updateSelectionPath() {
        if (selectionPoints.isEmpty()) return
        val first = selectionPoints.first(); val last = selectionPoints.last()
        selectionPath = Path().apply {
            when (selectionShape) {
                "Rectangle" -> addRect(min(first.x, last.x), min(first.y, last.y), max(first.x, last.x), max(first.y, last.y), Path.Direction.CW)
                "Ellipse" -> addOval(RectF(min(first.x, last.x), min(first.y, last.y), max(first.x, last.x), max(first.y, last.y)), Path.Direction.CW)
                else -> { moveTo(first.x, first.y); selectionPoints.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            }
        }; invalidate()
    }
    override fun onDetachedFromWindow(){removeCallbacks(longPress);removeCallbacks(shapeHold);fitAnimator?.cancel();fitAnimator=null;selectionBitmap?.recycle();selectionBitmap=null;rasterTransform.dispose();super.onDetachedFromWindow()}
    override fun performClick(): Boolean { super.performClick(); return true }
}
