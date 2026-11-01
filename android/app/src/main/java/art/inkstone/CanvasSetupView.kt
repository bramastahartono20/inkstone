package art.inkstone

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

internal data class CanvasPreset(val name:String,val width:Int,val height:Int,val dpi:Int=300)
internal object CanvasPresets {
    fun custom(activity:MainActivity):List<CanvasPreset> = runCatching{
        val array=JSONArray(activity.getSharedPreferences("canvases",0).getString("presets","[]"))
        (0 until array.length()).map{val p=array.getJSONObject(it);CanvasPreset(p.getString("name"),p.getInt("width"),p.getInt("height"),p.optInt("dpi",300))}
    }.getOrDefault(emptyList())
    fun add(activity:MainActivity,preset:CanvasPreset){
        val values=(listOf(preset)+custom(activity)).distinctBy{listOf(it.name,it.width,it.height,it.dpi)}.take(12)
        val array=JSONArray();values.forEach{array.put(JSONObject().put("name",it.name).put("width",it.width).put("height",it.height).put("dpi",it.dpi))}
        activity.getSharedPreferences("canvases",0).edit().putString("presets",array.toString()).apply()
    }
}

internal class NewCanvasMenu(private val activity:MainActivity,private val close:()->Unit):LinearLayout(activity){
    private var mode="raster"
    private val d=resources.displayMetrics.density
    private fun dp(n:Int)=(n*d).roundToInt()
    private fun round(c:Int,r:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun text(s:String,size:Float,color:Int=0xff333333.toInt())=TextView(activity).apply{text=s;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL}
    init{
        orientation=VERTICAL;background=PopoverBackground(d,0f,true);setPadding(dp(12),dp(26),dp(12),dp(12))
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(4),dp(10))}
        header.addView(text("New canvas",25f).apply{typeface=Typeface.DEFAULT_BOLD},LayoutParams(0,dp(50),1f))
        header.addView(ToolIcon(activity,"NewCanvas"){close();activity.customCanvas(mode)}.apply{light=true;contentDescription="Custom Canvas"},LayoutParams(dp(48),dp(48)));addView(header)
        val modeRow=RadioGroup(activity).apply{orientation=RadioGroup.HORIZONTAL;contentDescription="New document type"}
        for((value,title)in listOf("raster" to "Raster","vector" to "Vector · Experimental"))modeRow.addView(RadioButton(activity).apply{setTextColor(0xff333333.toInt());text=title;contentDescription="${title.substringBefore(" ·")} document";id=View.generateViewId();isChecked=value==mode;setOnClickListener{mode=value}},LayoutParams(0,dp(44),1f))
        addView(modeRow,LayoutParams(-1,dp(44)))
        val list=LinearLayout(activity).apply{orientation=VERTICAL}
        val metrics=resources.displayMetrics
        fun item(preset:CanvasPreset,detail:String="${preset.width} × ${preset.height}px"){
            val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(12),0);background=round(0x66ffffff,StudioShape.CORNER_DP);isClickable=true;isFocusable=true;contentDescription="Create ${preset.name}";setOnClickListener{close();activity.newCanvas(preset.width,preset.height,preset.dpi,preset.name,mode=mode)}}
            row.addView(text(preset.name,17f),LayoutParams(0,-1,1f));row.addView(text("sRGB",11f,0xff656565.toInt()).apply{setPadding(dp(7),0,dp(7),0)},LayoutParams(-2,-1))
            row.addView(text(detail,12f,0xff656565.toInt()),LayoutParams(-2,-1));list.addView(row,LayoutParams(-1,dp(52)).apply{bottomMargin=dp(3)})
        }
        item(CanvasPreset("Screen Size",metrics.widthPixels,metrics.heightPixels))
        list.addView(View(activity),LayoutParams(1,dp(10)))
        for(p in listOf(CanvasPreset("Square",2048,2048),CanvasPreset("4K",4096,1714),CanvasPreset("A4",2480,3508),CanvasPreset("Photo 4 × 6",1800,1200),CanvasPreset("Letter",3300,2550),CanvasPreset("Comic",1800,2850)) + CanvasPresets.custom(activity))item(p)
        val scroll=ScrollView(activity).apply{isVerticalScrollBarEnabled=false;addView(list)};addView(scroll,LayoutParams(-1,0,1f))
    }
}

internal class CanvasSetupView(private val activity:MainActivity,initialMode:String="raster"):FrameLayout(activity){
    private val theme get()=StudioTheme.of(activity)
    private var mode=initialMode
    private val d=resources.displayMetrics.density
    private fun dp(n:Int)=(n*d).roundToInt()
    private val blue=StudioColors.accent
    private var pixelsW=2048.0;private var pixelsH=1536.0;private var dpi=300
    private var unit="Pixels";private var field="Width";private var entry="2048";private var replace=true
    private var section="Dimensions";private var bg=Color.WHITE;private var transparent=false
    private var canvasName="Untitled Canvas"
    private lateinit var details:LinearLayout
    private lateinit var error:TextView
    private val values=mutableMapOf<String,TextView>()
    private val unitButtons=mutableMapOf<String,TextView>()
    private fun round(c:Int,r:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
    private fun label(s:String,size:Float=17f,color:Int=theme.text)=TextView(activity).apply{text=s;textSize=size;setTextColor(theme.label(color));gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),0,dp(20),0)}
    init{setBackgroundColor(theme.surface);contentDescription="Custom Canvas editor"}
    private var lastWidth=0;private var lastHeight=0
    override fun onMeasure(widthSpec:Int,heightSpec:Int){
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec)
        if(w>0 && h>0 && (w!=lastWidth||h!=lastHeight)){
            lastWidth=w;lastHeight=h;build(w,h)
        }
        super.onMeasure(widthSpec,heightSpec)
    }
    private fun build(w:Int,h:Int){
        if(w<=0||h<=0)return
        removeAllViews();values.clear();unitButtons.clear()
        val narrow=w/d<650;val side=if(narrow)dp(130) else min(dp(330),(w*.29f).toInt())
        val headerHeight=dp(78);val keypadHeight=min(dp(300),(h*.40f).toInt())
        val sidebar=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;background=round(0xff2d2d2d.toInt(),StudioShape.CORNER_DP);setPadding(dp(8),dp(8),dp(8),0)}
        sidebar.addView(label("Custom Canvas",if(narrow)18f else 24f).apply{typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(-1,headerHeight-dp(12)))
        for((name,icon) in listOf("Dimensions" to "Canvas","Document type" to "Select","Colour profile" to "Color","Time-lapse settings" to "Video","Canvas properties" to "Prefs")){
            val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;background=round(if(section==name)blue else Color.TRANSPARENT,StudioShape.CORNER_DP);isClickable=true;isFocusable=true;contentDescription=name;setOnClickListener{commit();section=name;build(lastWidth,lastHeight)}}
            row.addView(ToolIcon(activity,icon){commit();section=name;build(lastWidth,lastHeight)},LinearLayout.LayoutParams(dp(if(narrow)28 else 42),dp(42)))
            row.addView(label(name,if(narrow)12f else 16f).apply{setPadding(dp(4),0,dp(4),0)},LinearLayout.LayoutParams(0,dp(52),1f));sidebar.addView(row,LinearLayout.LayoutParams(-1,dp(54)).apply{bottomMargin=dp(5)})
        }
        addView(sidebar,LayoutParams(side,-1).apply{setMargins(dp(12),dp(10),0,dp(10))})
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        val title=label(canvasName,if(narrow)16f else 23f,Color.WHITE).apply{gravity=Gravity.CENTER;isClickable=true;contentDescription="Canvas name";setOnClickListener{activity.textDialog("Canvas name",canvasName){canvasName=it;text=it}}}
        header.addView(title,LinearLayout.LayoutParams(0,-1,1f))
        header.addView(ToolIcon(activity,"Close"){activity.showGallery()}.apply{contentDescription="Cancel";tooltipText="Cancel canvas creation";light=theme.light;background=round(theme.track,StudioShape.CORNER_DP)},LinearLayout.LayoutParams(dp(48),dp(44)))
        header.addView(label("✓",28f,Color.WHITE).apply{setPadding(0,0,0,0);gravity=Gravity.CENTER;contentDescription="Create custom canvas";background=round(blue,StudioShape.CORNER_DP);isClickable=true;isFocusable=true;setOnClickListener{create()}},LinearLayout.LayoutParams(dp(48),dp(48)).apply{setMargins(dp(10),0,dp(14),0)})
        addView(header,LayoutParams(-1,headerHeight).apply{leftMargin=side+dp(16)})
        details=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(if(narrow)12 else 42),dp(18),dp(if(narrow)12 else 34),dp(10))}
        val scroll=ScrollView(activity).apply{addView(details)}
        addView(scroll,LayoutParams(-1,-1).apply{leftMargin=side+dp(14);topMargin=headerHeight;bottomMargin=if(section=="Dimensions")keypadHeight else dp(10)})
        when(section){
            "Document type"->{val g=group();for((value,title)in listOf("raster" to "Raster · textured painting","vector" to "Vector · Experimental · SVG"))g.addView(label("${if(mode==value)"✓ " else ""}$title",17f,Color.WHITE).apply{contentDescription="${value.replaceFirstChar{it.uppercase()}} document";isClickable=true;setOnClickListener{mode=value;build(lastWidth,lastHeight)}},LinearLayout.LayoutParams(-1,dp(62)))
                details.addView(label("Vector uses monoline paths, shapes and editable objects. Airbrush and smudge remain in Raster mode.",14f).apply{maxLines=4},LinearLayout.LayoutParams(-1,dp(140)))}
            "Dimensions"->{
                val dimensions=group();numberRow(dimensions,"Width");numberRow(dimensions,"Height")
                val resolution=group();numberRow(resolution,"DPI");numberRow(resolution,"Layers",false)
                error=label("",12f,0xffff7970.toInt());details.addView(error,LinearLayout.LayoutParams(-1,dp(34)))
                addKeypad(w,h,keypadHeight,side,narrow);update()
            }
            "Colour profile"->{val g=group();g.addView(label("RGB",22f,Color.WHITE),LinearLayout.LayoutParams(-1,dp(58)));g.addView(label("sRGB IEC61966-2.1    ✓",16f,Color.WHITE),LinearLayout.LayoutParams(-1,dp(58)));details.addView(label("This canvas uses 8-bit sRGB.",14f),LinearLayout.LayoutParams(-1,dp(52)))}
            "Time-lapse settings"->{
                details.addView(label("Record your artwork as periodic frames, then export an MP4 from Actions → Video.",16f).apply{maxLines=4},LinearLayout.LayoutParams(-1,dp(130)))
                val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
                row.addView(label("Record new artworks",16f),LinearLayout.LayoutParams(0,dp(58),1f))
                val prefs=activity.getSharedPreferences("timelapse",0)
                row.addView(StudioSwitch(activity,"Record new artworks",prefs.getBoolean("defaultEnabled",true)){prefs.edit().putBoolean("defaultEnabled",it).apply()},LinearLayout.LayoutParams(dp(60),dp(48)));details.addView(row)
            }
            "Canvas properties"->{
                val g=group();g.addView(label("Background colour",18f),LinearLayout.LayoutParams(-1,dp(50)))
                val swatches=LinearLayout(activity).apply{setPadding(dp(16),0,dp(16),dp(10))}
                for(c in listOf(Color.WHITE,0xfffaf9f6.toInt(),0xff7e8587.toInt(),Color.BLACK))swatches.addView(View(activity).apply{tag=StudioTheme.PRESERVE_COLOR;background=round(c,StudioShape.CORNER_DP);contentDescription="Background #${Integer.toHexString(c)}";isClickable=true;setOnClickListener{bg=c;alpha=.65f}},LinearLayout.LayoutParams(0,dp(44),1f).apply{setMargins(dp(4),0,dp(4),0)})
                g.addView(swatches);val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL};row.addView(label("Background hidden",16f),LinearLayout.LayoutParams(0,dp(54),1f));row.addView(StudioSwitch(activity,"Background hidden",transparent){transparent=it},LinearLayout.LayoutParams(dp(60),dp(44)));g.addView(row)
            }
        }
        StudioTheme.apply(this)
    }
    private fun group():LinearLayout=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;background=round(0xff2d2d2d.toInt(),StudioShape.CORNER_DP);details.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)})}
    private fun numberRow(parent:LinearLayout,name:String,editable:Boolean=true){
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;isClickable=editable;isFocusable=editable;contentDescription="Canvas $name";if(editable)setOnClickListener{commit();field=name;entry=display(name);replace=true;update()}}
        row.addView(label(name,16f),LinearLayout.LayoutParams(0,dp(50),1f));val number=label("",16f).apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL};values[name]=number;row.addView(number,LinearLayout.LayoutParams(-2,dp(50)));parent.addView(row)
        parent.addView(View(activity).apply{setBackgroundColor(0x22000000)},LinearLayout.LayoutParams(-1,dp(1)))
    }
    private fun divisor()=when(unit){"Millimetres"->dpi/25.4;"Centimetres"->dpi/2.54;"Inches"->dpi.toDouble();else->1.0}
    private fun display(name:String):String=if(name=="DPI")dpi.toString() else {
        val value=(if(name=="Width")pixelsW else pixelsH)/divisor()
        if(unit=="Pixels")value.roundToInt().toString() else "%.2f".format(java.util.Locale.US,value).trimEnd('0').trimEnd('.')
    }
    private fun commit(){val n=entry.toDoubleOrNull()?:return;if(n<=0)return
        if(field=="DPI")dpi=n.roundToInt().coerceIn(1,2400) else if(field=="Width")pixelsW=n*divisor() else pixelsH=n*divisor()
    }
    private fun update(){
        for((name,v)in values){
            v.text=if(name=="Layers"){"Memory dependent"}else (if(name==field)entry else display(name)) + if(name=="DPI")"" else when(unit){"Pixels"->" px";"Millimetres"->" mm";"Centimetres"->" cm";else->" in"}
            v.setTextColor(if(name==field)Color.WHITE else theme.secondary)
        }
        unitButtons.forEach{(name,v)->v.background=round(if(name==unit)0xffcccccc.toInt() else 0xff383838.toInt(),StudioShape.CORNER_DP);v.setTextColor(if(name==unit)Color.BLACK else theme.secondary)}
        StudioTheme.apply(this)
    }
    private fun addKeypad(w:Int,h:Int,keypadHeight:Int,side:Int,narrow:Boolean){
        val pad=LinearLayout(activity).apply{gravity=Gravity.CENTER;setPadding(dp(8),dp(10),dp(8),dp(10));setBackgroundColor(theme.track)}
        val units=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        val unitWidth=if(narrow)dp(88) else dp(142)
        for(name in listOf("Millimetres","Centimetres","Inches","Pixels")){val v=label(name,if(narrow)12f else 15f).apply{gravity=Gravity.CENTER;setPadding(0,0,0,0);isClickable=true;setOnClickListener{commit();unit=name;entry=display(field);replace=true;update()}};unitButtons[name]=v;units.addView(v,LinearLayout.LayoutParams(unitWidth,0,1f).apply{setMargins(dp(3),dp(3),dp(9),dp(3))})};pad.addView(units,LinearLayout.LayoutParams(unitWidth,-1))
        val digits=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        for(row in listOf(listOf("7","8","9"),listOf("4","5","6"),listOf("1","2","3"),listOf(".","0","⌫"))){val line=LinearLayout(activity)
            for(key in row)line.addView(label(key,25f).apply{gravity=Gravity.CENTER;setPadding(0,0,0,0);background=round(0xff575757.toInt(),StudioShape.CORNER_DP);isClickable=true;contentDescription="Key $key";setOnClickListener{input(key)}},LinearLayout.LayoutParams(0,-1,1f).apply{setMargins(dp(3),dp(3),dp(3),dp(3))})
            digits.addView(line,LinearLayout.LayoutParams(-1,0,1f))
        };pad.addView(digits,LinearLayout.LayoutParams(if(narrow)0 else dp(335),-1,if(narrow)1f else 0f))
        val actions=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
        for(key in listOf("Backspace","Next","Done"))actions.addView(label(key,if(narrow)12f else 16f).apply{gravity=Gravity.CENTER;setPadding(dp(5),0,dp(5),0);background=round(0xff383838.toInt(),StudioShape.CORNER_DP);isClickable=true;setOnClickListener{when(key){"Backspace"->input("⌫");"Next"->{commit();field=if(field=="Width")"Height" else if(field=="Height")"DPI" else "Width";entry=display(field);replace=true;update()};else->{commit();update()}}}},LinearLayout.LayoutParams(if(narrow)dp(72) else dp(145),0,1f).apply{setMargins(dp(8),dp(3),0,dp(3))})
        pad.addView(actions,LinearLayout.LayoutParams(-2,-1));addView(pad,LayoutParams(-1,keypadHeight,Gravity.BOTTOM))
    }
    private fun input(key:String){
        if(key=="⌫"){entry=entry.dropLast(1);replace=false}else{if(replace){entry="";replace=false};if(key=="."&&entry.contains('.'))return;if(entry.length<9)entry+=key}
        commit();update()
    }
    private fun create(){
        commit();val w=pixelsW.roundToInt();val h=pixelsH.roundToInt()
        if(w<16||h<16||w>8192||h>8192||w.toLong()*h>16_777_216){if(::error.isInitialized)error.text="16–8192 pixels per side; maximum area 16 megapixels.";return}
        val preset=CanvasPreset(canvasName,w,h,dpi);CanvasPresets.add(activity,preset);activity.newCanvas(w,h,dpi,canvasName,bg,!transparent,mode)
    }
}
