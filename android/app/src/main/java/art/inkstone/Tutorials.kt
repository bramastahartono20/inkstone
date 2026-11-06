package art.inkstone

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

internal object Tutorials {
    private val popups=java.util.WeakHashMap<MainActivity,java.lang.ref.WeakReference<PopupWindow>>()
    fun dismiss(activity:MainActivity){popups.remove(activity)?.get()?.dismiss()}
    data class Step(val title:String,val body:String)
    data class Lesson(val title:String,val steps:List<Step>)
    val lessons=listOf(
        Lesson("Drawing basics",listOf(
            Step("Choose your brush","Tap the brush icon to open the library. Pick a brush, then draw on your canvas."),
            Step("Size, opacity and erasing","The two side sliders change brush size and opacity. Choose the eraser to erase; it remembers its own brush settings."),
            Step("Move around your artwork","Use two fingers to pan, pinch to zoom, and twist to rotate. With Pen-only enabled, your fingers still control these gestures."),
            Step("Undo and redo","Tap the undo and redo arrows. Two-finger and three-finger taps also work when enabled in Actions → Prefs."))),
        Lesson("Colors & Paint Mixing",listOf(
            Step("Pick or sample a color","Tap the color circle to open Colors. Hold on the canvas or reference image to sample an existing color."),
            Step("Fill an enclosed area","Drag the active color circle onto a closed shape. Keep holding and move left or right to adjust the fill threshold."),
            Step("Mix with existing paint","Tap the palette-shaped Paint Mixing icon. A soft green ring means mixing is on; a gray ring means it is off. It uses the brush you already selected."),
            Step("Carry color, then refill","Cross paint on the active layer to pick it up. In clear areas, the brush gradually returns to your selected color. Cross another color to mix again. Pickup controls how much paint you collect; Wetness controls how long you carry it."))),
        Lesson("Layers & groups",listOf(
            Step("Keep parts separate","Open Layers and tap +. Draw different parts of your artwork on different layers so they can be edited independently."),
            Step("Organize with groups","Create a group, then hold and drag a layer into it. Indentation shows which group it belongs to. Tap the group arrow to expand or collapse it."),
            Step("Move and blend","Tap a group’s options to move its contents on the canvas, or adjust Blend & opacity. Those appearance changes update live."))),
        Lesson("Smart Shape & transforms",listOf(
            Step("Perfect a shape","Draw a rough line, circle or polygon and hold the pen still at the end. Smart Shape recognizes the outline when Hold to perfect shape is enabled."),
            Step("Adjust before committing","After lifting the pen, drag the shape’s handles to resize or rotate. Done commits one undo step; Cancel restores the original artwork."),
            Step("Select and transform","Use Selection for part of a layer, then Transform to move, resize or rotate it. The grouped bottom toolbar reveals more options when tapped."))),
        Lesson("Canvas Atlas",listOf(
            Step("See your other canvases","Zoom out with two fingers to enter Canvas Atlas, or open it from Actions → Canvas."),
            Step("Navigate, then draw","Pan around the board and choose an artwork to activate it. The surrounding canvases remain previews while you work on the active canvas."))),
        Lesson("Save & time-lapse",listOf(
            Step("Your editable artwork","Artwork autosaves locally. Home returns to your library after saving. Actions → Share exports PNG, JPEG, PSD or an editable Inkstone document."),
            Step("Record your progress","Actions → Video controls time-lapse recording for this artwork. It records your drawing progress without your menus or reference window."),
            Step("Export your video","Choose 30 seconds, 1 minute or 2 minutes in Actions → Video. Tap Export time-lapse, watch the progress, then choose where to save the MP4. Recording starts from when it is enabled; it cannot recreate earlier work.")))
    )
    private fun prefs(a:MainActivity)=a.getSharedPreferences("tutorials",0)
    private fun dp(a:MainActivity,n:Int)=(n*a.resources.displayMetrics.density).toInt()
    private fun text(a:MainActivity,s:String,size:Float)=TextView(a).apply{text=s;textSize=size;setTextColor(StudioTheme.of(a).text);setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));gravity=Gravity.CENTER_VERTICAL}
    private fun surface(a:MainActivity)=GradientDrawable().apply{setColor(StudioTheme.of(a).surface);cornerRadius=dp(a,18).toFloat();setStroke(dp(a,1),StudioTheme.of(a).track)}
    fun welcome(a:MainActivity){
        if(prefs(a).getBoolean("welcomeSeen",false))return
        val host=a.findViewById<ViewGroup>(android.R.id.content)?:return
        if(host.findViewWithTag<View>("tutorial-welcome")!=null)return
        val card=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;background=surface(a);tag="tutorial-welcome";elevation=dp(a,10).toFloat()}
        card.addView(text(a,"Welcome to Inkstone",20f).apply{typeface=Typeface.DEFAULT_BOLD})
        card.addView(text(a,"A few short lessons will help you find your tools and make your first artwork.",15f))
        val actions=LinearLayout(a)
        fun dismiss(){prefs(a).edit().putBoolean("welcomeSeen",true).apply();host.removeView(card)}
        actions.addView(text(a,"Not now",15f).apply{contentDescription="Dismiss welcome tutorial";isFocusable=true;setOnClickListener{dismiss()}},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(text(a,"Get started",15f).apply{setTextColor(StudioColors.accent);contentDescription="Start tutorials";isFocusable=true;setOnClickListener{dismiss();show(a)}},LinearLayout.LayoutParams(0,-2,1f))
        card.addView(actions)
        host.addView(card,FrameLayout.LayoutParams(minOf(dp(a,440),a.resources.displayMetrics.widthPixels-dp(a,32)),-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=dp(a,20)})
        StudioMotion.reveal(card)
    }
    fun show(a:MainActivity,initial:Int?=null){
        dismiss(a)
        val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;background=surface(a);setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8))}
        val popup=PopupWindow(body,minOf(dp(a,460),a.resources.displayMetrics.widthPixels-dp(a,24)),minOf(dp(a,600),a.resources.displayMetrics.heightPixels-dp(a,80)),true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));isOutsideTouchable=true;elevation=dp(a,12).toFloat()}
        popups[a]=java.lang.ref.WeakReference(popup)
        popup.setOnDismissListener{if(popups[a]?.get()===popup)popups.remove(a)}
        var topic=initial;var index=0
        fun build(){
            body.removeAllViews()
            val header=LinearLayout(a).apply{gravity=Gravity.CENTER_VERTICAL}
            header.addView(text(a,if(topic==null)"Learn Inkstone" else lessons[topic!!].title,20f).apply{typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(0,-2,1f))
            header.addView(ToolIcon(a,"Close"){popup.dismiss()}.apply{light=StudioTheme.of(a).light;contentDescription="Close tutorials"},LinearLayout.LayoutParams(dp(a,48),dp(a,48)))
            body.addView(header)
            val content=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            body.addView(ScrollView(a).apply{addView(content)},LinearLayout.LayoutParams(-1,0,1f))
            if(topic==null){
                val completed=prefs(a).getStringSet("completed",emptySet())?:emptySet()
                lessons.forEachIndexed{i,lesson->content.addView(text(a,lesson.title+if(i.toString() in completed)"  ✓" else "",17f).apply{contentDescription="Tutorial ${lesson.title}";isFocusable=true;setOnClickListener{topic=i;index=0;build()}},LinearLayout.LayoutParams(-1,-2))}
            }else{
                val lesson=lessons[topic!!];val step=lesson.steps[index]
                content.addView(text(a,"${index+1} / ${lesson.steps.size}",13f).apply{setTextColor(StudioColors.accent)})
                content.addView(text(a,step.title,23f).apply{typeface=Typeface.DEFAULT_BOLD})
                val preview=TutorialPreview(a,topic!!,index)
                content.addView(preview,LinearLayout.LayoutParams(-1,dp(a,230)))
                content.addView(text(a,step.body,17f))
                if(preview.findTarget()!=null)content.addView(text(a,"Show on workspace ↗",15f).apply{setTextColor(StudioColors.accent);contentDescription="Show tutorial control";isFocusable=true;setOnClickListener{popup.dismiss();preview.spotlight()}},LinearLayout.LayoutParams(-1,-2))
                val buttons=LinearLayout(a)
                buttons.addView(text(a,if(index==0)"All lessons" else "Back",15f).apply{contentDescription="Tutorial back";isFocusable=true;setOnClickListener{if(index>0)index-- else topic=null;build()}},LinearLayout.LayoutParams(0,-2,1f))
                buttons.addView(text(a,if(index==lesson.steps.lastIndex)"Done" else "Next",15f).apply{setTextColor(StudioColors.accent);contentDescription="Tutorial next";isFocusable=true;setOnClickListener{
                    if(index<lesson.steps.lastIndex)index++ else{val done=(prefs(a).getStringSet("completed",emptySet())?:emptySet()).toMutableSet();done.add(topic.toString());prefs(a).edit().putStringSet("completed",done).apply();topic=null;index=0};build()
                }},LinearLayout.LayoutParams(0,-2,1f))
                body.addView(buttons)
            }
        }
        build();popup.showAtLocation(a.window.decorView,Gravity.CENTER,0,0);StudioMotion.reveal(body)
    }
}
