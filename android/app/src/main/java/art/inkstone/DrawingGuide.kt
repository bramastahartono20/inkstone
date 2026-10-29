package art.inkstone

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.widget.*
import kotlin.math.*

internal class DrawingGuide(context:Context){
    private val density=context.resources.displayMetrics.density
    private val prefs=context.getSharedPreferences("drawing-guides",Context.MODE_PRIVATE)
    var enabled=prefs.getBoolean("enabled",false)
    var mode=prefs.getString("mode","2D Grid")?:"2D Grid"
    var spacing=prefs.getFloat("spacing",80f)
    var opacity=prefs.getFloat("opacity",.35f)
    var editing=false
    val points=MutableList(3){i->PointF(prefs.getFloat("x$i",listOf(.18f,.82f,.5f)[i]),prefs.getFloat("y$i",if(i==2).95f else .35f))}
    private var dragging=-1
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    val perspective get()=mode.contains("Perspective")
    private val count get()=when(mode){"1-point Perspective"->1;"3-point Perspective"->3;else->2}
    fun save(){prefs.edit().putBoolean("enabled",enabled).putString("mode",mode).putFloat("spacing",spacing).putFloat("opacity",opacity).also{e->points.forEachIndexed{i,p->e.putFloat("x$i",p.x).putFloat("y$i",p.y)}}.apply()}
    fun draw(c:Canvas,w:Float,h:Float,zoom:Float){
        if(!enabled)return
        val save=c.save();c.clipRect(0f,0f,w,h);paint.color=StudioColors.accent;paint.alpha=(opacity.coerceIn(.05f,1f)*255).toInt();paint.strokeWidth=1.1f*density/zoom;paint.style=Paint.Style.STROKE
        val gap=spacing.coerceIn(20f,400f);val extent=hypot(w,h)*3
        if(perspective){
            for(i in 0 until count){val p=points[i];val x=p.x*w;val y=p.y*h
                for(degrees in 0 until 180 step (gap/16f).roundToInt().coerceIn(2,24)){val angle=degrees*PI/180;val dx=cos(angle).toFloat()*extent;val dy=sin(angle).toFloat()*extent;c.drawLine(x-dx,y-dy,x+dx,y+dy,paint)}
            }
            if(count==1){var x=0f;while(x<=w){c.drawLine(x,0f,x,h,paint);x+=gap}}
            if(count>=2)c.drawLine(0f,points[0].y*h,w,points[1].y*h,paint)
        }else if(mode=="Isometric"){
            for(degrees in listOf(0,60,120)){val angle=degrees*PI/180;val nx=cos(angle).toFloat();val ny=sin(angle).toFloat()
                val steps=(extent/gap).toInt();for(i in -steps..steps){val x=w/2+nx*i*gap;val y=h/2+ny*i*gap;c.drawLine(x-ny*extent,y+nx*extent,x+ny*extent,y-nx*extent,paint)}
            }
        }else{var x=0f;while(x<=w){c.drawLine(x,0f,x,h,paint);x+=gap};var y=0f;while(y<=h){c.drawLine(0f,y,w,y,paint);y+=gap}}
        if(editing && perspective){paint.alpha=255;for(i in 0 until count){val p=points[i];paint.style=Paint.Style.FILL;paint.color=Color.WHITE;c.drawCircle(p.x*w,p.y*h,10*density/zoom,paint);paint.style=Paint.Style.STROKE;paint.color=StudioColors.accent;c.drawCircle(p.x*w,p.y*h,10*density/zoom,paint);paint.textSize=16*density/zoom;paint.style=Paint.Style.FILL;c.drawText("VP ${i+1}",p.x*w+14*density/zoom,p.y*h-12*density/zoom,paint)}}
        paint.alpha=255;c.restoreToCount(save)
    }
    fun touch(event:MotionEvent,point:PointF,w:Float,h:Float,radius:Float):Boolean{
        if(!editing)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{dragging=(0 until count).minByOrNull{hypot(points[it].x*w-point.x,points[it].y*h-point.y)}?.takeIf{hypot(points[it].x*w-point.x,points[it].y*h-point.y)<=radius}?:-1}
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP->{if(dragging>=0){points[dragging].set((point.x/w).coerceIn(-2f,3f),(point.y/h).coerceIn(-2f,3f));if(count>=2 && dragging<2){points[1-dragging].y=points[dragging].y}};if(event.actionMasked==MotionEvent.ACTION_UP){save();dragging=-1}}
            MotionEvent.ACTION_CANCEL->dragging=-1
        };return true
    }
    fun settings(context:Context,changed:()->Unit,edit:()->Unit){
        val activity=context as? android.app.Activity?:return
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(20,16,20,16);setBackgroundColor(StudioColors.surface)}
        val popup=PopupWindow(ScrollView(context).apply{addView(body)},(320*density).toInt().coerceAtMost(context.resources.displayMetrics.widthPixels-32),(540*density).toInt().coerceAtMost(context.resources.displayMetrics.heightPixels-64),true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=16*density;isOutsideTouchable=true}
        fun button(label:String,action:()->Unit){body.addView(TextView(context).apply{text=label;textSize=15f;setTextColor(Color.WHITE);setPadding(12,16,12,16);isClickable=true;setOnClickListener{action()}})}
        button("← Back"){popup.dismiss()}
        for(type in listOf("2D Grid","Isometric","1-point Perspective","2-point Perspective","3-point Perspective"))button(type){mode=type;enabled=true;save();changed()}
        button("Show / hide guide"){enabled=!enabled;save();changed()}
        fun control(title:String,initial:Int,maxValue:Int,change:(Int)->Unit){button(title){};body.addView(SeekBar(context).apply{max=maxValue;progress=initial;contentDescription=title;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(v:SeekBar){};override fun onStopTrackingTouch(v:SeekBar){save()};override fun onProgressChanged(v:SeekBar,p:Int,user:Boolean){if(user){change(p);changed()}}})})}
        control("Guide spacing",(spacing-20).toInt(),380){spacing=it+20f}
        control("Guide opacity",(opacity*100).toInt(),100){opacity=(it/100f).coerceAtLeast(.05f)}
        button("Edit vanishing points"){if(perspective){editing=true;popup.dismiss();edit();changed()}}
        popup.showAtLocation(activity.window.decorView,android.view.Gravity.CENTER,0,0)
    }
}
