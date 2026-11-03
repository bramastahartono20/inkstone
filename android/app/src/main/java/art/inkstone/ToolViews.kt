package art.inkstone

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ToolIcon(context: Context, var symbol: String, private val action: () -> Unit) : View(context) {
    var activeTool = false
        set(value) {
            if(field == value) return
            field = value
            selection?.cancel()
            val target = if(value) 1f else 0f
            if(!isAttachedToWindow || !android.animation.ValueAnimator.areAnimatorsEnabled()) { selectedAmount=target;invalidate();return }
            selection=android.animation.ValueAnimator.ofFloat(selectedAmount,target).apply {
                duration=150;interpolator=android.view.animation.DecelerateInterpolator()
                addUpdateListener { selectedAmount=it.animatedValue as Float;invalidate() };start()
            }
        }
    var repeatDelay:(()->Long)?=null
    private var repeated=false
    private val repeatAction=object:Runnable{override fun run(){if(isPressed){repeated=true;action();postDelayed(this,80)}}}
    var swatch = 0xff202020.toInt()
    var light = false
    private var selectedAmount=0f
    private var selection:android.animation.ValueAnimator?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun shape(block:Path.()->Unit)=Path().apply(block)
    private val brush=shape {
        moveTo(-2f,3f);cubicTo(0f,-1f,9f,-11f,13f,-14f);cubicTo(15f,-16f,15.6f,-15.2f,14f,-13f)
        cubicTo(11f,-8f,4f,0f,1f,4f);close()
        moveTo(-3f,4f);lineTo(0f,6f);cubicTo(-1f,10f,-5f,12f,-11f,13f)
        cubicTo(-7f,10f,-9f,7f,-3f,4f);close()
    }
    private val hand=shape {
        moveTo(-10f,12f);lineTo(-6f,5f);quadTo(-8f,0f,-5f,-3f)
        lineTo(-1f,-9f);quadTo(1f,-12f,3f,-10f);quadTo(4f,-9f,2f,-6f)
        lineTo(1f,-4f);lineTo(6f,-10f);quadTo(8f,-12f,10f,-10f)
        quadTo(11f,-8f,9f,-6f);lineTo(6f,-2f)
        quadTo(12f,-5f,12f,-1f);quadTo(12f,1f,9f,4f)
        lineTo(5f,8f);quadTo(3f,10f,0f,10f);lineTo(-3f,15f);close()
    }
    private val wrench=shape {
        moveTo(-10f,10f);lineTo(1f,-1f);cubicTo(-1f,-6f,3f,-11f,8f,-10f)
        lineTo(4f,-6f);lineTo(5f,-3f);lineTo(8f,-2f);lineTo(12f,-6f)
        cubicTo(13f,0f,8f,3f,4f,1f);lineTo(-7f,12f);quadTo(-11f,14f,-12f,11f);close()
    }
    private val eraser=shape {
        moveTo(-2f,-1f);lineTo(7f,-10f);quadTo(8f,-11f,9f,-10f);lineTo(15f,-4f)
        quadTo(16f,-3f,15f,-2f);lineTo(6f,7f);close()
        moveTo(-3.5f,.5f);lineTo(4.5f,8.5f);lineTo(1f,12f);quadTo(0f,13f,-1f,12f)
        lineTo(-7f,6f);quadTo(-8f,5f,-7f,4f);close()
    }
    private val arrow=shape {moveTo(-9f,-2f);lineTo(11f,-11f);lineTo(3f,10f);lineTo(0f,1f);close()}
    private val ribbon=shape {
        moveTo(7f,-10f);cubicTo(-5f,-15f,-12f,-4f,-2f,0f)
        cubicTo(10f,4f,4f,14f,-7f,10f)
    }
    private val undo=shape {moveTo(-8f,-2f);cubicTo(8f,-7f,13f,6f,3f,8f);lineTo(-1f,8f);moveTo(-4f,-7f);lineTo(-9f,-2f);lineTo(-4f,2f)}
    private val dashes=DashPathEffect(floatArrayOf(2.2f,2.2f),0f)
    var mixingEnabled=false
    init {isClickable=true;isFocusable=true;contentDescription=symbol;tooltipText=when(symbol){"Paint Mixing"->"Paint Mixing · color pickup and wetness";"Paint"->"Brush / Pen";"Erase"->"Eraser · tap again for hardness";"Select"->"Selection";"Transform"->"Move / resize";"Color"->"Colors · drag to fill";"Nodes"->"Edit vector points";else->symbol};setOnClickListener{action()}}
    override fun onDraw(c:Canvas) {
        val s=min(width,height)/48f
        c.save();c.translate(width/2f,height/2f);c.scale(s,s)
        p.style=Paint.Style.FILL
        if(symbol in editingTools) {p.color=if(selectedAmount>.01f)Color.argb((255*selectedAmount).toInt(),44,166,235) else if(light)0x10000000 else 0x30000000;c.drawCircle(0f,0f,14f,p)}
        if(symbol=="Paint Mixing"){
            p.style=Paint.Style.FILL;p.color=if(mixingEnabled)0x224fba87 else if(light)0x18778089 else 0x287e8790;c.drawCircle(0f,0f,19f,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=if(mixingEnabled)0x996bd49d.toInt() else if(light)0x66747f89 else 0x668b949e;c.drawCircle(0f,0f,18f,p);p.style=Paint.Style.FILL
        }
        if(isFocused) {p.color=0x33888888;c.drawRoundRect(-21f,-21f,21f,21f,7f,7f,p)}
        val base=if(light)0xff454545.toInt() else 0xffc7c7c7.toInt()
        fun channel(shift:Int)=(((base ushr shift) and 255)*(1-selectedAmount)+((StudioColors.accent ushr shift) and 255)*selectedAmount).toInt()
        p.color=if(symbol in editingTools && selectedAmount>.5f)Color.WHITE else Color.rgb(channel(16),channel(8),channel(0));p.strokeWidth=1.7f;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        when(symbol) {
            "Line"->{p.style=Paint.Style.STROKE;p.strokeWidth=2f;c.drawLine(-9f,9f,9f,-9f,p)}
            "Rectangle"->{p.style=Paint.Style.STROKE;p.strokeWidth=2f;c.drawRect(-10f,-8f,10f,8f,p)}
            "Ellipse"->{p.style=Paint.Style.STROKE;p.strokeWidth=2f;c.drawOval(-10f,-8f,10f,8f,p)}
            "Nodes"->{p.style=Paint.Style.STROKE;c.drawPath(Path().apply{moveTo(-10f,8f);quadTo(-4f,-13f,10f,-6f)},p);p.style=Paint.Style.FILL;for(point in listOf(PointF(-10f,8f),PointF(-4f,-4f),PointF(10f,-6f)))c.drawRect(point.x-2,point.y-2,point.x+2,point.y+2,p)}
            "Paint Mixing"->{p.style=Paint.Style.STROKE;p.strokeWidth=2f;c.drawOval(-12f,-10f,12f,11f,p);c.drawArc(-6f,-5f,7f,8f,25f,285f,false,p);p.style=Paint.Style.FILL;c.drawCircle(-6f,-5f,2f,p);c.drawCircle(2f,-7f,2f,p);c.drawCircle(8f,-2f,2f,p)}
            "Paint"->c.drawPath(brush,p)
            "Smudge"->c.drawPath(hand,p)
            "Erase"->c.drawPath(eraser,p)
            "Actions"->{c.save();c.scale(.72f,.72f);c.drawPath(wrench,p);c.restore()}
            "Transform"->{c.save();c.scale(.75f,.75f);c.drawPath(arrow,p);c.restore()}
            "Select"->{p.style=Paint.Style.STROKE;p.strokeWidth=2.1f;p.pathEffect=dashes;c.save();c.scale(.75f,.75f);c.drawPath(ribbon,p);c.restore();p.pathEffect=null}
            "Layers"->{p.style=Paint.Style.STROKE;c.drawRoundRect(-4f,-11f,13f,6f,2f,2f,p);p.style=Paint.Style.FILL;c.drawRoundRect(-10f,-5f,7f,12f,2f,2f,p)}
            "Adjustments"->{p.style=Paint.Style.STROKE;p.strokeWidth=2.1f;c.drawLine(-6f,7f,5f,-5f,p);p.strokeWidth=1.2f;c.drawLine(-6f,-8f,-6f,-3f,p);c.drawLine(-8.5f,-5.5f,-3.5f,-5.5f,p);c.drawLine(6f,3f,6f,7f,p);c.drawLine(4f,5f,8f,5f,p);c.drawLine(4f,-10f,6f,-10f,p)}
            "Color"->{p.color=swatch;c.drawCircle(0f,0f,14f,p);p.style=Paint.Style.STROKE;p.color=if(light)0x30000000 else 0x30ffffff;p.strokeWidth=1f;c.drawCircle(0f,0f,14f,p)}
            "Eyedropper"->{c.save();c.rotate(40f);p.style=Paint.Style.STROKE;p.strokeWidth=1.8f
                c.drawPath(Path().apply{moveTo(-3f,-7f);lineTo(3f,-7f);lineTo(3f,6f);lineTo(0f,11f);lineTo(-3f,6f);close()},p)
                p.style=Paint.Style.FILL;c.drawRoundRect(-5f,-13f,5f,-6f,3f,3f,p);c.restore()}
            "Undo","Redo"->{p.style=Paint.Style.STROKE;if(symbol=="Redo")c.scale(-1f,1f);c.drawPath(undo,p)}
            "Canvas"->{p.style=Paint.Style.STROKE;c.drawRoundRect(-11f,-8f,11f,8f,2f,2f,p);c.drawLine(-6f,4f,0f,-3f,p);c.drawLine(0f,-3f,7f,4f,p)}
            "NewCanvas"->{c.drawRoundRect(-12f,-6f,12f,10f,3f,3f,p);c.drawRoundRect(-9f,-10f,9f,-7f,1f,1f,p);p.color=if(light)0xffe9e9e9.toInt() else 0xff222222.toInt();p.style=Paint.Style.STROKE;c.drawLine(-9f,1f,-1f,1f,p);c.drawLine(-5f,-3f,-5f,5f,p)}
            "Share"->{p.style=Paint.Style.STROKE;c.drawRoundRect(-8f,-2f,8f,11f,1f,1f,p);c.drawLine(0f,4f,0f,-12f,p);c.drawLine(0f,-12f,-4f,-7f,p);c.drawLine(0f,-12f,4f,-7f,p)}
            "Video"->{val t=Path();t.moveTo(-7f,-10f);t.lineTo(12f,0f);t.lineTo(-7f,10f);t.close();c.drawPath(t,p)}
            "Prefs"->{c.drawRoundRect(-13f,-7f,13f,7f,7f,7f,p);p.color=0xff293238.toInt();c.drawCircle(5f,0f,5f,p)}
            "Help"->{c.drawCircle(0f,0f,11f,p);p.color=if(light)Color.WHITE else 0xff293238.toInt();p.textSize=18f;p.typeface=Typeface.DEFAULT_BOLD;p.textAlign=Paint.Align.CENTER;c.drawText("?",0f,6f,p)}
            "Add"->{p.style=Paint.Style.STROKE;c.drawLine(-10f,0f,10f,0f,p);c.drawLine(0f,-10f,0f,10f,p)}
            "Close"->{p.style=Paint.Style.STROKE;c.drawLine(-7f,-7f,7f,7f,p);c.drawLine(7f,-7f,-7f,7f,p)}
            else->{p.textSize=16f;p.textAlign=Paint.Align.CENTER;c.drawText(symbol,0f,6f,p)}
        }
        c.restore()
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){repeated=false;repeatDelay?.let{postDelayed(repeatAction,it())}}
        if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL){
            removeCallbacks(repeatAction)
            if(repeated){val cancelled=MotionEvent.obtain(event);cancelled.action=MotionEvent.ACTION_CANCEL;val handled=super.onTouchEvent(cancelled);cancelled.recycle();return handled}
        }
        return super.onTouchEvent(event)
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        animate().cancel()
        if(android.animation.ValueAnimator.areAnimatorsEnabled()) animate().scaleX(if(isPressed).86f else 1f).scaleY(if(isPressed).86f else 1f).setDuration(if(isPressed)80 else 180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        else {scaleX=1f;scaleY=1f}
        invalidate()
    }
    override fun onDetachedFromWindow(){removeCallbacks(repeatAction);selection?.cancel();animate().cancel();super.onDetachedFromWindow()}
    private companion object {val editingTools=setOf("Actions","Adjustments","Select","Transform")}
}

class VerticalSlider(context: Context, private val label: String, initial: Float, val changed: (Float) -> Unit) : View(context) {
    var value=initial;set(v){field=v.coerceIn(0f,1f);invalidate()}
    var tracking:((Boolean)->Unit)?=null
    var bookmark:Float?=null
    val thumbY get()=height-10*resources.displayMetrics.density-value*(height-20*resources.displayMetrics.density)
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{isFocusable=true;isClickable=true;contentDescription=label}
    override fun onDraw(c:Canvas){val d=resources.displayMetrics.density
        p.color=StudioTheme.of(context).track;c.drawRoundRect(3*d,0f,width-3*d,height.toFloat(),StudioShape.CORNER_DP*d,StudioShape.CORNER_DP*d,p)
        bookmark?.let{p.color=0xff4cb5ed.toInt();val y=height-10*d-it*(height-20*d);c.drawRect(2*d,y-d,7*d,y+d,p)}
        val y=thumbY;p.color=if(StudioTheme.of(context).light)0xff647689.toInt() else 0xffc8d2d6.toInt();c.drawRoundRect(2*d,y-6*d,width-2*d,y+6*d,StudioShape.CORNER_DP*d,StudioShape.CORNER_DP*d,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=d;p.color=0xff879599.toInt();c.drawRoundRect(2*d,y-6*d,width-2*d,y+6*d,StudioShape.CORNER_DP*d,StudioShape.CORNER_DP*d,p);p.style=Paint.Style.FILL
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        parent.requestDisallowInterceptTouchEvent(true)
        if(e.actionMasked==MotionEvent.ACTION_DOWN)tracking?.invoke(true)
        if(e.actionMasked==MotionEvent.ACTION_DOWN||e.actionMasked==MotionEvent.ACTION_MOVE){val d=resources.displayMetrics.density;value=1f-(e.y-10*d)/(height-20*d);changed(value);contentDescription="$label ${(value*100).toInt()} percent"}
        if(e.actionMasked==MotionEvent.ACTION_UP){performClick();tracking?.invoke(false)}
        if(e.actionMasked==MotionEvent.ACTION_CANCEL)tracking?.invoke(false)
        return true
    }
    override fun onKeyDown(code:Int,event:android.view.KeyEvent):Boolean{
        if(code==android.view.KeyEvent.KEYCODE_DPAD_UP||code==android.view.KeyEvent.KEYCODE_DPAD_DOWN){value+=if(code==android.view.KeyEvent.KEYCODE_DPAD_UP).02f else -.02f;changed(value);return true}
        return super.onKeyDown(code,event)
    }
    override fun performClick():Boolean{super.performClick();return true}
}

class BrushPreview(context:Context,val preset:BrushPreset,var chosen:Boolean=false,val eraser:Boolean=false):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var stroke:Bitmap?=null
    init{minimumHeight=(70*resources.displayMetrics.density).toInt();contentDescription="${preset.name}, ${preset.group}${if(eraser)", eraser" else ""}"}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        if(w<=0||h<=0)return
        stroke?.recycle();stroke=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val nativeTip=runCatching{context.assets.open("brushes/tip-${preset.kind}.png").use{BitmapFactory.decodeStream(it)}}.getOrNull()
        if(nativeTip!=null && preset.colorMode=="Solid"){
            val c=Canvas(stroke!!);val ink=if(eraser)0xff273440.toInt()else if(StudioTheme.of(context).light)0xff374a5c.toInt()else 0xffedf3f4.toInt()
            p.colorFilter=PorterDuffColorFilter(ink,PorterDuff.Mode.SRC_IN);c.drawBitmap(nativeTip,null,RectF(8f,h*.22f,w-8f,h.toFloat()),p);p.colorFilter=null;nativeTip.recycle();return
        }
        nativeTip?.recycle()
        val c=Canvas(stroke!!);val d=resources.displayMetrics.density
        p.color=if(eraser)0xff273440.toInt() else if(StudioTheme.of(context).light)0xff374a5c.toInt() else 0xffedf3f4.toInt();p.style=Paint.Style.FILL
        p.maskFilter=if(preset.kind in listOf(2,8,9))BlurMaskFilter((if(preset.kind==2)12 else if(preset.kind==9)2 else 6)*d,BlurMaskFilter.Blur.NORMAL) else if(preset.kind==4)BlurMaskFilter(3*d,BlurMaskFilter.Blur.NORMAL) else null
        fun shade(value:Float):Int{val t=(value-floor(value));if(preset.colorMode!="Palette" || preset.palette.size<2)return Color.HSVToColor(floatArrayOf(t*360f,.85f,1f));val at=t*preset.palette.size;val a=preset.palette[at.toInt()%preset.palette.size];val b=preset.palette[(at.toInt()+1)%preset.palette.size];val f=at-at.toInt();return Color.rgb((Color.red(a)*(1-f)+Color.red(b)*f).roundToInt(),(Color.green(a)*(1-f)+Color.green(b)*f).roundToInt(),(Color.blue(a)*(1-f)+Color.blue(b)*f).roundToInt())}
        for(i in 0..140){val t=i/140f;val x=22*d+t*(w-44*d);val y=h*.72f-t*14*d+sin(t*2*Math.PI).toFloat()*2*d
            if(!eraser && preset.colorMode=="Rainbow")p.color=Color.HSVToColor(floatArrayOf(t*360f,.85f,1f))
            if(!eraser && preset.colorMode=="Palette" && preset.palette.isNotEmpty()){
                val at=t*preset.palette.size;val a=preset.palette[at.toInt()%preset.palette.size];val b=preset.palette[(at.toInt()+1)%preset.palette.size];val f=at-at.toInt()
                p.color=Color.rgb((Color.red(a)*(1-f)+Color.red(b)*f).roundToInt(),(Color.green(a)*(1-f)+Color.green(b)*f).roundToInt(),(Color.blue(a)*(1-f)+Color.blue(b)*f).roundToInt())
            }
            var radius=if(preset.kind in listOf(0,1)&&preset.size<24) (1.3f+sin(t*Math.PI).toFloat()*1.6f)*d else (12+sin(t*Math.PI).toFloat()*4)*d
            if(!eraser && preset.colorMode!="Solid" && preset.colorLayout!="Along"){
                val stripes=if(preset.colorLayout=="Bristles")17 else 9
                val colors=IntArray(stripes){j->shade(t*240f/preset.colorCycle.coerceAtLeast(20f)+(j.toFloat()/(stripes-1)-.5f))}
                p.shader=LinearGradient(x,y-radius,x,y+radius,colors,null,Shader.TileMode.CLAMP)
            }else p.shader=null
            p.alpha=((if(preset.kind==4)28 else 240)*if(preset.fadeLength>0)((1-t)/.35f).coerceIn(0f,1f) else 1f).roundToInt();c.drawCircle(x,y,radius,p)
            if(preset.kind in listOf(1,3,10,11,13,14)){p.alpha=130;for(j in 0..3)c.drawCircle(x+sin(i*3f+j)*3*d,y+cos(i*7f+j)*radius,max(.4f*d,radius*.12f),p)}
        };p.maskFilter=null;p.shader=null;p.alpha=255
    }
    override fun onDraw(c:Canvas){val d=resources.displayMetrics.density
        p.style=Paint.Style.FILL;p.color=if(chosen)StudioColors.accentSoft else Color.TRANSPARENT;c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),StudioShape.CORNER_DP*d,StudioShape.CORNER_DP*d,p)
        p.alpha=255
        if(eraser){p.color=0xffc6d1d7.toInt();c.drawRoundRect(4*d,24*d,width-4*d,height-4*d,StudioShape.CORNER_DP*d,StudioShape.CORNER_DP*d,p)}
        stroke?.let{if(StudioTheme.of(context).light && chosen && preset.colorMode=="Solid")p.colorFilter=PorterDuffColorFilter(Color.WHITE,PorterDuff.Mode.SRC_IN);c.drawBitmap(it,0f,0f,p);p.colorFilter=null}
        p.color=if(chosen)Color.WHITE else StudioTheme.of(context).text;p.textSize=11*d;p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);c.drawText(preset.name,7*d,15*d,p)
        if(chosen){p.style=Paint.Style.STROKE;p.strokeWidth=1.5f*d;p.color=0xff8bd7ff.toInt();c.drawRoundRect(d,d,width-d,height-d,(StudioShape.CORNER_DP-1)*d,(StudioShape.CORNER_DP-1)*d,p);p.style=Paint.Style.FILL}
    }
}

class ColorDisc(context:Context,initial:Int,var classic:Boolean=false,val changed:(Int)->Unit):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv=FloatArray(3).also{Color.colorToHSV(initial,it)}
    fun setColor(color:Int){Color.colorToHSV(color,hsv);invalidate()}
    private var ring=false
    private var disc:Bitmap?=null
    private var cachedHue=-1f
    private var lastSize=0
    init {minimumHeight=(280*resources.displayMetrics.density).toInt();contentDescription="Color picker. Outer ring selects hue; inner area selects saturation and brightness."}
    private fun square():RectF {val d=resources.displayMetrics.density;val side=min(width-24*d,height-52*d);return RectF((width-side)/2,8*d,(width+side)/2,8*d+side)}
    private fun drawClassic(c:Canvas){
        val box=square();p.style=Paint.Style.FILL
        p.shader=LinearGradient(box.left,0f,box.right,0f,Color.WHITE,Color.HSVToColor(floatArrayOf(hsv[0],1f,1f)),Shader.TileMode.CLAMP);c.drawRect(box,p)
        p.shader=LinearGradient(0f,box.top,0f,box.bottom,Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP);c.drawRect(box,p)
        val colors=IntArray(7){Color.HSVToColor(floatArrayOf(it*60f,1f,1f))}
        p.shader=LinearGradient(box.left,0f,box.right,0f,colors,null,Shader.TileMode.CLAMP);c.drawRect(box.left,box.bottom+16,box.right,box.bottom+32,p);p.shader=null
        p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=Color.WHITE
        c.drawCircle(box.left+hsv[1]*box.width(),box.top+(1-hsv[2])*box.height(),6f,p)
        c.drawCircle(box.left+hsv[0]/360f*box.width(),box.bottom+24,9f,p)
    }
    override fun onDraw(c:Canvas){
        if(classic){drawClassic(c);return}
        val cx=width/2f;val cy=height/2f;val r=min(width,height)*.46f
        val colors=IntArray(13){Color.HSVToColor(floatArrayOf(it*30f,1f,1f))}
        p.shader=SweepGradient(cx,cy,colors,null);p.style=Paint.Style.STROKE;p.strokeWidth=r*.13f
        c.drawCircle(cx,cy,r*.91f,p);p.shader=null;p.style=Paint.Style.FILL
        val inner=r*.76f
        c.save();c.clipPath(Path().apply{addCircle(cx,cy,inner,Path.Direction.CW)})
        p.shader=LinearGradient(cx-inner,cy,cx+inner,cy,Color.WHITE,Color.HSVToColor(floatArrayOf(hsv[0],1f,1f)),Shader.TileMode.CLAMP)
        c.drawRect(cx-inner,cy-inner,cx+inner,cy+inner,p)
        p.shader=LinearGradient(cx,cy-inner,cx,cy+inner,Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP)
        c.drawRect(cx-inner,cy-inner,cx+inner,cy+inner,p);p.shader=null;c.restore()
        val angle=Math.toRadians(hsv[0].toDouble());val hx=cx+cos(angle).toFloat()*r*.91f;val hy=cy+sin(angle).toFloat()*r*.91f
        p.style=Paint.Style.STROKE;p.color=Color.WHITE;p.strokeWidth=2.5f*resources.displayMetrics.density;c.drawCircle(hx,hy,r*.065f,p)
        val sx=cx+(hsv[1]*2-1)*inner;val sy=cy+(1-hsv[2]*2)*inner
        p.color=Color.BLACK;p.strokeWidth=3.5f*resources.displayMetrics.density;c.drawCircle(sx,sy,6*resources.displayMetrics.density,p)
        p.color=Color.WHITE;p.strokeWidth=2f*resources.displayMetrics.density;c.drawCircle(sx,sy,6*resources.displayMetrics.density,p);p.style=Paint.Style.FILL
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        parent.requestDisallowInterceptTouchEvent(true)
        if(classic){val box=square();if(e.actionMasked==MotionEvent.ACTION_DOWN)ring=e.y>box.bottom
            if(e.actionMasked==MotionEvent.ACTION_DOWN||e.actionMasked==MotionEvent.ACTION_MOVE){
                if(ring)hsv[0]=((e.x-box.left)/box.width()).coerceIn(0f,.9999f)*360f
                else{hsv[1]=((e.x-box.left)/box.width()).coerceIn(0f,1f);hsv[2]=(1-(e.y-box.top)/box.height()).coerceIn(0f,1f)}
                changed(Color.HSVToColor(hsv));invalidate()
            };if(e.actionMasked==MotionEvent.ACTION_UP)performClick();return true
        }
        val cx=width/2f;val cy=height/2f;val r=min(width,height)*.46f;val dx=e.x-cx;val dy=e.y-cy
        if(e.actionMasked==MotionEvent.ACTION_DOWN)ring=hypot(dx,dy)>r*.81f
        if(e.actionMasked==MotionEvent.ACTION_DOWN||e.actionMasked==MotionEvent.ACTION_MOVE){
            if(ring)hsv[0]=((atan2(dy,dx)*180f/Math.PI.toFloat())+360f)%360f
            else {var nx=dx/(r*.76f);var ny=dy/(r*.76f);val len=hypot(nx,ny);if(!classic&&len>1){nx/=len;ny/=len};hsv[1]=((nx+1)/2).coerceIn(0f,1f);hsv[2]=((1-ny)/2).coerceIn(0f,1f)}
            changed(Color.HSVToColor(hsv));invalidate()
        }
        if(e.actionMasked==MotionEvent.ACTION_UP)performClick();return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
