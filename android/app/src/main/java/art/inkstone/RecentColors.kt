package art.inkstone

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import org.json.JSONArray

internal class RecentColors(context:Context){
    private val prefs=context.getSharedPreferences("recent-colors",Context.MODE_PRIVATE)
    fun list():List<Int> = runCatching{val a=JSONArray(prefs.getString("colors","[]"));(0 until a.length().coerceAtMost(30)).map{a.getLong(it).toInt()}.distinct()}.getOrDefault(emptyList())
    fun record(color:Int){if(Color.alpha(color)==0)return;synchronized(lock){val values=(listOf(color)+list().filter{it!=color}).take(30);prefs.edit().putString("colors",JSONArray(values.map{it.toLong() and 0xffffffffL}).toString()).apply()}}
    companion object{private val lock=Any()}
}
internal class RecentColorRow(context:Context,choose:(Int)->Unit):LinearLayout(context){
    init{orientation=VERTICAL;val d=resources.displayMetrics.density;val theme=StudioTheme.of(context)
        addView(TextView(context).apply{text="Recent colours";textSize=13f;setTextColor(theme.secondary);setPadding(0,(10*d).toInt(),0,(6*d).toInt())})
        val values=RecentColors(context).list()
        if(values.isEmpty())addView(TextView(context).apply{text="Colors you draw or fill with appear here.";textSize=12f;setTextColor(theme.secondary);setPadding(0,0,0,(8*d).toInt())})
        else{val row=LinearLayout(context);values.forEach{color->row.addView(android.view.View(context).apply{tag=StudioTheme.PRESERVE_COLOR;contentDescription=String.format("Recent color #%08X",color);isClickable=true;isFocusable=true;tooltipText=String.format("#%06X",color and 0xffffff);setBackgroundColor(color);setOnClickListener{choose(color)}},LayoutParams((44*d).toInt(),(44*d).toInt()))};addView(HorizontalScrollView(context).apply{isHorizontalScrollBarEnabled=false;addView(row)},LayoutParams(-1,(48*d).toInt()))}
    }
}
