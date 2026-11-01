package art.inkstone

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

internal class TimelapseExportProgress(private val activity:MainActivity){
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    private val theme=StudioTheme.of(activity)
    private val title=TextView(activity).apply{text="Export time-lapse";textSize=21f;setTextColor(theme.text)}
    private val status=TextView(activity).apply{textSize=16f;setTextColor(theme.text);setPadding(0,dp(18),0,dp(14));contentDescription="Time-lapse export progress";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
    private val bar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progressTintList=android.content.res.ColorStateList.valueOf(StudioColors.accent)}
    private val close=TextView(activity).apply{text="Close";textSize=16f;gravity=Gravity.END;setTextColor(StudioColors.accent);setPadding(0,dp(22),0,dp(8));isFocusable=true;visibility=View.GONE}
    private val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(16));background=GradientDrawable().apply{setColor(theme.surface);cornerRadius=dp(18).toFloat()};addView(title);addView(status);addView(bar,LinearLayout.LayoutParams(-1,dp(8)));addView(close)}
    private val popup=PopupWindow(body,minOf(dp(390),activity.resources.displayMetrics.widthPixels-dp(32)),-2,true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));elevation=dp(14).toFloat();isOutsideTouchable=false}
    var encoding=true;private set
    init{close.setOnClickListener{popup.dismiss()}}
    fun show(){if(!activity.isDestroyed && !popup.isShowing)popup.showAtLocation(activity.window.decorView,Gravity.CENTER,0,0)}
    fun preparing(){encoding=true;status.text="Preparing your recording…";bar.isIndeterminate=true;show()}
    fun progress(value:Int){if(!encoding)return;bar.isIndeterminate=false;bar.progress=value;status.text=if(value<100)"Exporting video · $value%" else "Finishing video…"}
    fun state(value:String){encoding=false;bar.visibility=if(value.startsWith("Save failed") || value.startsWith("Export failed") || value=="Save cancelled")View.GONE else View.VISIBLE;bar.isIndeterminate=value=="Saving video…";bar.progress=100;status.text=value;close.visibility=if(value=="Saving video…")View.GONE else View.VISIBLE;show()}
    fun dismiss(){popup.dismiss()}
}
