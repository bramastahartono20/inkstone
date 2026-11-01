package art.inkstone

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

internal object TimelapseControls {
    fun populate(activity:MainActivity,body:LinearLayout){
        val id=activity.documentId;val recorder=activity.timelapse;val theme=StudioTheme.of(activity)
        fun dp(n:Int)=(n*body.resources.displayMetrics.density).toInt()
        fun label(s:String,size:Float=15f)=TextView(activity).apply{text=s;textSize=size;setTextColor(theme.text);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10))}
        val status=label("",16f).apply{typeface=Typeface.DEFAULT_BOLD;contentDescription="Time-lapse status"}
        body.addView(status)
        body.addView(label("Your drawing progress is recorded locally. Menus and reference images stay out of the video.",14f))
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        row.addView(label("Time-lapse recording"),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(StudioSwitch(activity,"Time-lapse recording",recorder.enabled(id)){enabled->
            recorder.setEnabled(id,enabled)
            if(enabled){activity.session?.captureNextFrame();if(activity.vectorDocument!=null)activity.scheduleVectorRecording()}
        },LinearLayout.LayoutParams(dp(60),dp(48)));body.addView(row)
        body.addView(label("Video length",16f))
        val durations=LinearLayout(activity)
        fun refreshDurations(){for(i in 0 until durations.childCount){val v=durations.getChildAt(i) as TextView;val selected=v.tag==recorder.duration(id);v.isSelected=selected;v.setTextColor(if(selected)StudioColors.accent else theme.text);v.background=android.graphics.drawable.GradientDrawable().apply{setColor(if(selected)StudioColors.accentSoft else theme.track);cornerRadius=dp(8).toFloat()}}}
        for((seconds,title) in listOf(30 to "30 sec",60 to "1 min",120 to "2 min"))durations.addView(label(title).apply{tag=seconds;gravity=Gravity.CENTER;contentDescription="Time-lapse length $title";isFocusable=true;setOnClickListener{recorder.setDuration(id,seconds);refreshDurations()}},LinearLayout.LayoutParams(0,dp(48),1f).apply{setMargins(dp(3),dp(4),dp(3),dp(8))})
        body.addView(durations);refreshDurations()
        val export=label("Export time-lapse · MP4").apply{contentDescription="Export time-lapse";isFocusable=true;isClickable=true;setTextColor(StudioColors.accent);setOnClickListener{activity.exportTimelapse()}}
        body.addView(export)
        val clear=label("Clear recording").apply{contentDescription="Clear time-lapse";isFocusable=true;isClickable=true}
        body.addView(clear)
        val confirm=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        confirm.addView(label("Delete this artwork’s recording? Your artwork stays saved."))
        val actions=LinearLayout(activity)
        actions.addView(label("Keep recording").apply{isFocusable=true;setOnClickListener{confirm.visibility=View.GONE}},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(label("Delete recording").apply{contentDescription="Confirm clear time-lapse";isFocusable=true;setOnClickListener{recorder.clear(id){confirm.visibility=View.GONE}}},LinearLayout.LayoutParams(0,-2,1f))
        confirm.addView(actions);body.addView(confirm);clear.setOnClickListener{confirm.visibility=View.VISIBLE}
        body.addView(label("The full recording is fitted to your chosen length. Recording begins when enabled; earlier work cannot be recreated.",13f))
        val update=object:Runnable{override fun run(){
            val count=recorder.count(id);status.text=if(recorder.exporting)"Exporting video · ${recorder.progress}%" else if(count==0)"Ready to record" else if(recorder.enabled(id))"Recording on" else "Recording paused"
            export.isEnabled=!recorder.exporting && count>0;export.alpha=if(export.isEnabled)1f else .45f
            clear.isEnabled=!recorder.exporting && count>0;clear.alpha=if(clear.isEnabled)1f else .45f
            if(status.isAttachedToWindow)status.postDelayed(this,500)
        }}
        status.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){status.removeCallbacks(update);update.run()}
            override fun onViewDetachedFromWindow(v:View){status.removeCallbacks(update)}
        });update.run()
    }
}
