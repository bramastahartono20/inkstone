package art.inkstone

import android.graphics.*
import android.view.*
import android.widget.*
import kotlin.math.*

internal class ReferencePanel(private val activity:MainActivity,private val picked:(Int)->Unit,private val closed:()->Unit):FrameLayout(activity){
    private val density=resources.displayMetrics.density
    private fun dp(v:Int)=(v*density).roundToInt()
    private val id=activity.documentId
    private val prefs=activity.getSharedPreferences("reference-$id",0)
    private val image=ReferenceImage(activity){color->picked(color);activity.message("Reference color selected")}
    private val file get()=java.io.File(activity.filesDir,"references/$id.png")
    private var generation=0L
    private var host:FrameLayout?=null
    private var startX=0f;private var startY=0f;private var startLeft=0;private var startTop=0;private var startW=0;private var startH=0
    private val layoutListener=OnLayoutChangeListener{_,_,_,_,_,_,_,_,_->clamp()}
    init{
        contentDescription="Reference image companion";elevation=dp(18).toFloat();background=android.graphics.drawable.GradientDrawable().apply{setColor(StudioColors.surface);cornerRadius=dp(StudioShape.CORNER_DP).toFloat()};clipToOutline=true;isClickable=true
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(StudioColors.toolbar)}
        header.addView(TextView(activity).apply{text="Reference";setTextColor(Color.WHITE);textSize=15f;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,0,0);contentDescription="Move reference window";setOnTouchListener{_,e->drag(e,false)}},LinearLayout.LayoutParams(0,-1,1f))
        header.addView(button("×","Close reference"){prefs.edit().putBoolean("open",false).apply();(parent as? FrameLayout)?.removeView(this);closed()},LinearLayout.LayoutParams(dp(40),-1))
        addView(header,LayoutParams(-1,dp(40),Gravity.TOP));addView(image,LayoutParams(-1,-1).apply{topMargin=dp(40);bottomMargin=dp(42)})
        val tools=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        for((label,description,action)in listOf(Triple("Import","Import reference image",{activity.chooseReference{setImage(it)}}),Triple("Canvas","Use canvas as reference",{captureCanvas()}),Triple("Fit","Fit reference image",{image.fit()}),Triple("Pick","Pick reference color",{image.picking=!image.picking;activity.message(if(image.picking)"Tap the reference to pick a color"else"Reference navigation restored")}))){tools.addView(button(label,description,action),LinearLayout.LayoutParams(0,-1,1f))}
        tools.addView(button("↘","Resize reference window"){}.apply{setOnTouchListener{_,e->drag(e,true)}},LinearLayout.LayoutParams(dp(32),-1))
        addView(tools,LayoutParams(-1,dp(42),Gravity.BOTTOM))
    }
    private fun button(label:String,description:String,action:()->Unit)=TextView(activity).apply{text=label;textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;isClickable=true;isFocusable=true;contentDescription=description;tooltipText=description;setOnClickListener{action()}}
    fun attach(parent:FrameLayout){
        host=parent;val pw=parent.width.takeIf{it>0}?:resources.displayMetrics.widthPixels;val ph=parent.height.takeIf{it>0}?:resources.displayMetrics.heightPixels
        val w=(prefs.getFloat("w",.3f)*pw).roundToInt().coerceAtLeast(dp(230));val h=(prefs.getFloat("h",.48f)*ph).roundToInt().coerceAtLeast(dp(240))
        parent.addView(this,LayoutParams(w,h).apply{leftMargin=(prefs.getFloat("x",.1f)*pw).roundToInt();topMargin=(prefs.getFloat("y",.15f)*ph).roundToInt()});parent.addOnLayoutChangeListener(layoutListener);prefs.edit().putBoolean("open",true).apply();post{clamp()}
        val loadGeneration=generation
        activity.io.execute{val bitmap=if(file.exists())BitmapFactory.decodeFile(file.absolutePath)else null;activity.runOnUiThread{if(isAttachedToWindow && generation==loadGeneration)image.replace(bitmap)else bitmap?.recycle()}}
    }
    internal fun setImage(bitmap:Bitmap){
        generation++;image.replace(bitmap);val copy=bitmap.copy(Bitmap.Config.ARGB_8888,false)
        activity.io.execute{runCatching{file.parentFile?.mkdirs();val temp=java.io.File(file.parentFile,file.name+".tmp");temp.outputStream().use{check(copy.compress(Bitmap.CompressFormat.PNG,100,it))};check(temp.renameTo(file)){"Cannot save reference"}}.onFailure{activity.runOnUiThread{activity.message("Reference could not be saved: ${it.message}")}};copy.recycle()}
    }
    private fun captureCanvas(){activity.session?.imageSnapshot{bitmap->bitmap?.let{val scale=min(1f,1448f/max(it.width,it.height));val small=Bitmap.createScaledBitmap(it,max(1,(it.width*scale).toInt()),max(1,(it.height*scale).toInt()),true);if(small!==it)it.recycle();if(isAttachedToWindow)setImage(small)else small.recycle()}}?:activity.vectorDocument?.let{setImage(it.bitmap(1448))}}
    private fun drag(e:MotionEvent,resize:Boolean):Boolean{
        val p=layoutParams as LayoutParams
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{bringToFront();parent.requestDisallowInterceptTouchEvent(true);startX=e.rawX;startY=e.rawY;startLeft=p.leftMargin;startTop=p.topMargin;startW=p.width;startH=p.height}
            MotionEvent.ACTION_MOVE->{if(resize){p.width=startW+(e.rawX-startX).roundToInt();p.height=startH+(e.rawY-startY).roundToInt()}else{p.leftMargin=startLeft+(e.rawX-startX).roundToInt();p.topMargin=startTop+(e.rawY-startY).roundToInt()};layoutParams=p;clamp()}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->saveFrame()
        };return true
    }
    private fun clamp(){val parent=host?:return;if(parent.width<=0 || parent.height<=0)return;val p=layoutParams as LayoutParams
        val maxW=max(dp(160),parent.width-dp(56));val maxH=max(dp(180),parent.height-dp(110))
        val w=p.width.coerceIn(min(dp(230),maxW),maxW);val h=p.height.coerceIn(min(dp(240),maxH),maxH)
        val x=p.leftMargin.coerceIn(dp(44),max(dp(44),parent.width-w));val y=p.topMargin.coerceIn(dp(88),max(dp(88),parent.height-h))
        if(w!=p.width || h!=p.height || x!=p.leftMargin || y!=p.topMargin){p.width=w;p.height=h;p.leftMargin=x;p.topMargin=y;layoutParams=p}
    }
    private fun saveFrame(){val parent=host?:return;if(parent.width==0 || parent.height==0)return;val p=layoutParams as LayoutParams;prefs.edit().putFloat("x",p.leftMargin.toFloat()/parent.width).putFloat("y",p.topMargin.toFloat()/parent.height).putFloat("w",p.width.toFloat()/parent.width).putFloat("h",p.height.toFloat()/parent.height).apply()}
    override fun onDetachedFromWindow(){saveFrame();host?.removeOnLayoutChangeListener(layoutListener);image.replace(null);super.onDetachedFromWindow()}
    companion object{fun wasOpen(activity:MainActivity)=activity.getSharedPreferences("reference-${activity.documentId}",0).getBoolean("open",false)}
}

internal class ReferenceImage(context:android.content.Context,private val picked:(Int)->Unit):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap:Bitmap?=null;private var scale=1f;private var offset=PointF();private var last=PointF();private var distance=0f;private var multi=false
    private val matrix=Matrix();var picking=false
    private var holdPicked=false
    private var pickPoint:PointF?=null
    private var pickColor=Color.TRANSPARENT
    private var holdStart=PointF()
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private val hold=Runnable{if(!multi){holdPicked=true;pick(holdStart.x,holdStart.y)}}
    override fun onDetachedFromWindow(){removeCallbacks(hold);super.onDetachedFromWindow()}
    private fun pick(x:Float,y:Float){val b=bitmap?:return;val px=kotlin.math.floor((x-offset.x)/scale).toInt();val py=kotlin.math.floor((y-offset.y)/scale).toInt();if(px in 0 until b.width && py in 0 until b.height){val color=b.getPixel(px,py);if(color ushr 24>0){pickPoint=PointF(x,y);pickColor=color;picked(color);invalidate()}}}
    init{contentDescription="Reference image. Hold to pick a color. Drag to pan and pinch to zoom."}
    fun replace(next:Bitmap?){if(bitmap!==next)bitmap?.recycle();bitmap=next;fit()}
    fun fit(){val b=bitmap?:run{invalidate();return};if(width<=0 || height<=0){post{fit()};return};scale=min(width.toFloat()/b.width,height.toFloat()/b.height);offset=PointF((width-b.width*scale)/2,(height-b.height*scale)/2);invalidate()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){fit()}
    override fun onDraw(c:Canvas){c.drawColor(0xff252b30.toInt());val b=bitmap
        if(b==null){paint.color=0xffbfc8ce.toInt();paint.textSize=13*resources.displayMetrics.density;c.drawText("Import an image or capture Canvas",12*resources.displayMetrics.density,height/2f,paint);return}
        matrix.setScale(scale,scale);matrix.postTranslate(offset.x,offset.y);c.drawBitmap(b,matrix,paint)
        pickPoint?.let{point->
            val d=resources.displayMetrics.density
            val x=point.x.coerceIn(34*d,(width-34*d).coerceAtLeast(34*d));val y=(point.y-36*d).coerceIn(34*d,(height-34*d).coerceAtLeast(34*d))
            paint.alpha=255;paint.style=Paint.Style.STROKE;paint.color=pickColor;paint.strokeWidth=10*d;c.drawCircle(x,y,28*d,paint)
            paint.color=Color.WHITE;paint.strokeWidth=2*d;c.drawCircle(x,y,33*d,paint);paint.style=Paint.Style.FILL
        }
        if(picking){paint.color=0xbb2b8ed1.toInt();c.drawRect(0f,0f,width.toFloat(),4*resources.displayMetrics.density,paint)}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{parent.requestDisallowInterceptTouchEvent(true);val b=bitmap?:return true
        if(e.actionMasked==MotionEvent.ACTION_MOVE && hypot(e.x-holdStart.x,e.y-holdStart.y)>slop)removeCallbacks(hold)
        if(e.actionMasked in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN))removeCallbacks(hold)
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{holdPicked=false;holdStart=PointF(e.x,e.y);postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong());multi=false;last=PointF(e.x,e.y)}
            MotionEvent.ACTION_POINTER_DOWN->{pickPoint=null;invalidate();multi=true;distance=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));last=PointF((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2)}
            MotionEvent.ACTION_MOVE->{if(e.pointerCount>=2){val center=PointF((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2);val d=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));val base=min(width.toFloat()/b.width,height.toFloat()/b.height);val next=(scale*if(distance>0)d/distance else 1f).coerceIn(base*.5f,base*16f);val x=(last.x-offset.x)/scale;val y=(last.y-offset.y)/scale;scale=next;offset=PointF(center.x-x*scale,center.y-y*scale);last=center;distance=d}else if(holdPicked){pick(e.x,e.y)}else if(!multi && !picking){offset.offset(e.x-last.x,e.y-last.y);last=PointF(e.x,e.y)};invalidate()}
            MotionEvent.ACTION_UP->{if(picking && !multi){val x=((e.x-offset.x)/scale).toInt();val y=((e.y-offset.y)/scale).toInt();if(x in 0 until b.width && y in 0 until b.height){val color=b.getPixel(x,y);if(color ushr 24>0){picked(color);picking=false;invalidate()}}};pickPoint=null;invalidate();performClick()}
            MotionEvent.ACTION_CANCEL->{pickPoint=null;holdPicked=false;invalidate()}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
