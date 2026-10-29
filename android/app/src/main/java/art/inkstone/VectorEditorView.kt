package art.inkstone

import android.app.AlertDialog
import android.graphics.Color
import android.os.SystemClock
import android.view.*
import android.widget.*
import kotlin.math.*

internal class VectorEditorView(val activity:MainActivity,val document:VectorDocument):FrameLayout(activity){
    val canvas=VectorCanvas(activity,document).apply{onAtlas={activity.showAtlas(true)}}
    private val theme get()=StudioTheme.of(activity)
    private val density=resources.displayMetrics.density
    private fun dp(v:Int)=(v*density).roundToInt()
    private val colorDrop by lazy{VectorColorDropController(this,canvas)}
    private val toolbar=FrameLayout(activity).apply{setBackgroundColor(theme.toolbar)}
    private val rail=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(10));setBackgroundColor(theme.rail);contentDescription="Vector side controls"}
    private lateinit var widthSlider:VerticalSlider
    private lateinit var alphaSlider:VerticalSlider
    private var compact=resources.configuration.orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE || resources.displayMetrics.widthPixels/density<700
    private val barHeight get()=if(compact)88 else 44
    private var groupedShapes=false
    private val history=FrameLayout(activity).apply{setBackgroundColor(theme.toolbar);contentDescription="History and save"}
    private val galleryButton by lazy{GalleryButton(activity){activity.gallery()}}
    private val saveButton by lazy{button("Save"){save()}.apply{translationX=dp(71).toFloat()}}
    private val saveWidth get()=max(dp(54),ceil(saveButton.paint.measureText(saveButton.text.toString())).toInt()+saveButton.paddingLeft+saveButton.paddingRight)
    private val historyWidth get()=max(dp(196),2*(dp(44)+saveWidth))
    private val icons=mutableMapOf<String,ToolIcon>()
    private val nodeControls=LinearLayout(activity).apply{gravity=Gravity.CENTER;background=android.graphics.drawable.GradientDrawable().apply{setColor(theme.surface);cornerRadius=dp(StudioShape.CORNER_DP).toFloat()}}
    private lateinit var nodeScroll:HorizontalScrollView
    private var panel:View?=null
    private var panelName=""
    private val hud=BrushHud(activity)
    private val hideHud=Runnable{StudioMotion.chrome(hud,false)}
    private var styleBefore:ByteArray?=null
    private var penTouchActive=false;private var blocked=false
    val busy get()=canvas.busy || styleBefore!=null
    fun snapshotBytes()=styleBefore?.clone()?:canvas.snapshot()
    init {
        post{if(ReferencePanel.wasOpen(activity))showReference()}
        addView(canvas,LayoutParams(-1,-1).apply{topMargin=dp(barHeight);leftMargin=dp(44)})
        addView(toolbar,LayoutParams(-1,dp(barHeight),Gravity.TOP))
        widthSlider=VerticalSlider(activity,"Vector stroke width",sqrt(canvas.strokeWidth/160f)){value->
            canvas.strokeWidth=(value*value*160).coerceAtLeast(.5f)
            editableSelection()?.width=canvas.strokeWidth;canvas.invalidate();updateHud(widthSlider,false)
        }
        alphaSlider=VerticalSlider(activity,"Vector opacity",1f){value->
            val alpha=(value*255).roundToInt();canvas.ink=(canvas.ink and 0xffffff) or (alpha shl 24)
            editableSelection()?.let{it.stroke=(it.stroke and 0xffffff) or (alpha shl 24);it.fill=it.fill?.let{f->(f and 0xffffff) or (alpha shl 24)}};canvas.invalidate();updateHud(alphaSlider,true)
        }
        for(slider in listOf(widthSlider,alphaSlider)){
            slider.tracking={start->if(start){canvas.cancelGesture();styleBefore=document.encode();activity.deferAutosave();dismissPanel();hud.removeCallbacks(hideHud);updateHud(slider,slider===alphaSlider);hud.bringToFront();StudioMotion.reveal(hud)}else{styleBefore?.let{previous->runCatching{document.commit(previous)}.onFailure{document.restore(previous);activity.message(it.message?:"Could not change style")}};styleBefore=null;hud.postDelayed(hideHud,650);activity.scheduleSave();refresh()}}
            slider.setOnTouchListener{_,event->if(event.actionMasked==MotionEvent.ACTION_CANCEL){styleBefore?.let{document.restore(it)};styleBefore=null;canvas.invalidate()};false}
        }
        rail.addView(widthSlider,LinearLayout.LayoutParams(dp(32),0,1f))
        rail.addView(ToolIcon(activity,"Eyedropper"){colors()}.apply{swatch=canvas.ink;contentDescription="Vector colors"},LinearLayout.LayoutParams(dp(40),dp(44)))
        rail.addView(alphaSlider,LinearLayout.LayoutParams(dp(32),0,1f))
        addView(rail,LayoutParams(dp(44),-1,Gravity.LEFT or Gravity.TOP).apply{topMargin=dp(barHeight)})
        addView(hud,LayoutParams(dp(160),dp(160)));hud.visibility=INVISIBLE
        nodeScroll=HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(nodeControls)}
        addView(nodeScroll,LayoutParams(min(dp(536),resources.displayMetrics.widthPixels-dp(80)),dp(44),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{leftMargin=dp(50);rightMargin=dp(8);bottomMargin=dp(if(compact)52 else 12)})
        canvas.onDismissPanel={dismissPanel()}
        canvas.onChanged={activity.scheduleSave();refresh()};canvas.onSelection=::refresh
        buildToolbar()
        refresh()
    }
    private fun editableSelection():VectorObject?=canvas.selected?.takeIf{document.owner(it.id)?.effectiveLocked!=true}
    private fun button(label:String,chosen:Boolean=false,action:()->Unit)=TextView(activity).apply{
        text=label;textSize=13f;setTextColor(if(chosen)StudioColors.accent else theme.text);gravity=Gravity.CENTER
        setPadding(dp(12),0,dp(12),0);contentDescription=label;isSingleLine=true;isClickable=true;isFocusable=true
        setOnClickListener{StudioMotion.bounce(this);action()}
    }
    private fun tool(label:String,symbol:String=label,action:()->Unit)=ToolIcon(activity,symbol,action).apply{contentDescription=label;tooltipText=when(label){"Pen"->"Vector pen";"Nodes"->"Edit anchors and curve handles";"Select"->"Select / move / resize objects";else->label};icons[label]=this}
    private fun buildToolbar(){
        toolbar.removeAllViews();(history.parent as? ViewGroup)?.removeView(history);history.removeAllViews();icons.clear()
        val left=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        (galleryButton.parent as? ViewGroup)?.removeView(galleryButton)
        left.addView(galleryButton,LinearLayout.LayoutParams(galleryButton.requiredWidth,dp(44)))
        left.addView(tool("Actions"){actions()},LinearLayout.LayoutParams(dp(40),dp(44)))
        if(groupedShapes)left.addView(tool("Shapes","Rectangle"){AlertDialog.Builder(activity).setTitle("Shape").setItems(arrayOf("Line","Rectangle","Ellipse")){_,i->dismissPanel();canvas.changeTool(listOf("Line","Rectangle","Ellipse")[i])}.show()},LinearLayout.LayoutParams(dp(40),dp(44)))
        for(name in if(groupedShapes)listOf("Pen","Nodes","Select") else listOf("Pen","Line","Rectangle","Ellipse","Nodes","Select"))left.addView(tool(name,if(name=="Pen")"Paint" else if(name=="Select")"Transform" else name){dismissPanel();canvas.changeTool(name)},LinearLayout.LayoutParams(dp(40),dp(44)))
        val right=LinearLayout(activity).apply{gravity=Gravity.RIGHT or Gravity.CENTER_VERTICAL}
        right.addView(tool("Layers"){layers()},LinearLayout.LayoutParams(dp(48),dp(44)))
        right.addView(tool("Colors","Color"){colors()},LinearLayout.LayoutParams(dp(48),dp(44)))
        icons["Colors"]?.let{colorDrop.bind(it){colors()}}
        val undoRedo=LinearLayout(activity)
        undoRedo.addView(tool("Undo"){canvas.undo()},LinearLayout.LayoutParams(dp(44),dp(44)))
        undoRedo.addView(tool("Redo"){canvas.redo()},LinearLayout.LayoutParams(dp(44),dp(44)))
        history.addView(undoRedo,LayoutParams(dp(88),dp(44),Gravity.CENTER))
        (saveButton.parent as? ViewGroup)?.removeView(saveButton)
        saveButton.translationX=dp(44)+saveWidth/2f
        history.addView(saveButton,LayoutParams(saveWidth,dp(44),Gravity.CENTER))
        toolbar.addView(left,LayoutParams(-2,dp(44),Gravity.TOP or Gravity.LEFT))
        toolbar.addView(right,LayoutParams(-2,dp(44),Gravity.TOP or Gravity.RIGHT).apply{topMargin=if(compact)dp(44) else 0})
        if(compact)addView(history,LayoutParams(-1,dp(44),Gravity.BOTTOM))
        else toolbar.addView(history,LayoutParams(historyWidth,dp(44),Gravity.CENTER))
    }
    override fun onMeasure(wSpec:Int,hSpec:Int){
        val w=MeasureSpec.getSize(wSpec);val h=MeasureSpec.getSize(hSpec);val next=w<h || w<max(dp(700),2*(galleryButton.requiredWidth+dp(if(w/density<820)200 else 280))+historyWidth+dp(24))
        val group=w/density<820
        if(next!=compact || group!=groupedShapes){compact=next;groupedShapes=group;dismissPanel();buildToolbar();refresh()}
        (toolbar.layoutParams as LayoutParams).height=dp(barHeight)
        (canvas.layoutParams as LayoutParams).apply{topMargin=dp(barHeight);bottomMargin=if(compact)dp(44) else 0}
        (rail.layoutParams as LayoutParams).apply{topMargin=dp(barHeight);bottomMargin=if(compact)dp(44) else 0}
        (nodeScroll.layoutParams as LayoutParams).apply{bottomMargin=dp(if(compact)52 else 12);width=min(dp(536),max(dp(128),w-dp(80)))}
        super.onMeasure(wSpec,hSpec)
    }
    internal fun acceptAtlasColor(color:Int){canvas.ink=color;refresh();canvas.invalidate()}
    fun refresh(){
        icons.forEach{(name,icon)->icon.activeTool=canvas.tool==name || name==panelName || name=="Shapes" && canvas.tool in listOf("Line","Rectangle","Ellipse");icon.swatch=canvas.ink;icon.invalidate()}
        widthSlider.value=sqrt((canvas.selected?.width?:canvas.strokeWidth)/160f)
        alphaSlider.value=Color.alpha(canvas.selected?.stroke?:canvas.ink)/255f
        StudioTheme.apply(toolbar);StudioTheme.apply(history);StudioTheme.apply(nodeControls);updateNodeControls();canvas.invalidate()
    }
    private fun updateHud(slider:VerticalSlider,opacity:Boolean){
        hud.value=slider.value;hud.opacityMode=opacity;hud.soft=false
        (hud.layoutParams as? LayoutParams)?.let{it.leftMargin=dp(50);it.topMargin=(rail.top+slider.top+slider.thumbY-dp(102)).roundToInt().coerceIn(dp(barHeight+8),max(dp(barHeight+8),height-dp(168)));hud.layoutParams=it};hud.invalidate()
    }
    private var layersScroll=0
    fun dismissPanel():Boolean{val old=panel?:return false;if(panelName=="Layers"){layersScroll=(old as? android.view.ViewGroup)?.let{root->(0 until root.childCount).map{root.getChildAt(it)}.filterIsInstance<ScrollView>().firstOrNull()}?.scrollY?:0};panel=null;panelName="";StudioMotion.dismiss(old){removeView(old)};refresh();return true}
    private var replacingLayerPanel=false
    private fun panel(name:String,left:Boolean=false,body:(LinearLayout)->Unit){
        val old=if(replacingLayerPanel && panelName==name)panel else null
        if(old==null){if(panelName==name){dismissPanel();return};dismissPanel()};canvas.cancelGesture()
        val content=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16));background=android.graphics.drawable.GradientDrawable().apply{setColor(theme.glass);cornerRadius=dp(StudioShape.CORNER_DP).toFloat()}}
        val heading=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        heading.minimumHeight=dp(52)
        heading.addView(TextView(activity).apply{text=name;textSize=26f;setTextColor(theme.text);maxLines=3;setPadding(0,dp(8),0,dp(8));setTypeface(null,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(ToolIcon(activity,"Close"){dismissPanel()},LinearLayout.LayoutParams(dp(44),dp(44)));content.addView(heading)
        val items=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL};body(items)
        content.addView(ScrollView(activity).apply{addView(items);if(name=="Layers")post{scrollTo(0,layersScroll)}},LinearLayout.LayoutParams(-1,-2))
        val pw=min(dp(430),(width.takeIf{it>0}?:resources.displayMetrics.widthPixels)-dp(56))
        if(old!=null)removeView(old)
        addView(content,LayoutParams(pw,min(dp(620),max(dp(160),height-dp(barHeight+60))),Gravity.TOP or if(left)Gravity.LEFT else Gravity.RIGHT).apply{topMargin=dp(barHeight);if(left)leftMargin=dp(48) else rightMargin=dp(10)})
        StudioTheme.apply(content);panel=content;panelName=name;if(old==null)StudioMotion.reveal(content,if(left).15f else .85f);refresh()
    }
    private fun updateNodeControls(){
        nodeControls.removeAllViews();if(canvas.movingGroup!=null){nodeScroll.visibility=VISIBLE;nodeControls.addView(button("Done moving group"){canvas.finishGroupMove();refresh()});return};if(canvas.guides.editing){nodeScroll.visibility=VISIBLE;nodeControls.addView(button("Done editing guides"){canvas.guides.editing=false;canvas.guides.save();canvas.invalidate();refresh()});return};nodeScroll.visibility=if(canvas.tool=="Nodes" && canvas.selected!=null)VISIBLE else GONE
        val obj=canvas.selected?:return;val anchors=VectorGeometry.anchors(obj);val index=anchors.indexOfFirst{it.first==canvas.selectedNode}
        if(obj.kind in listOf("Line","Pen","Bezier"))nodeControls.addView(button(if(canvas.addingAnchor)"Tap path · Cancel" else "+ Anchor"){canvas.addAnchorMode()}.apply{contentDescription="Add anchor";tooltipText="Tap the selected path to add an anchor point"},LinearLayout.LayoutParams(dp(if(canvas.addingAnchor)150 else 106),dp(44)))
        if(obj.kind in listOf("Line","Pen","Bezier")){
            nodeControls.addView(button(if(obj.closed)"Open path"else"Close path"){canvas.closePath()},LinearLayout.LayoutParams(dp(108),dp(44)))
            if(!obj.closed)nodeControls.addView(button(if(canvas.joiningPaths)"Cancel join"else"Join ends"){canvas.joinPathMode()},LinearLayout.LayoutParams(dp(108),dp(44)))
        }
        nodeControls.addView(button("‹"){canvas.cycleNode(-1)}.apply{contentDescription="Previous point"},LinearLayout.LayoutParams(dp(44),dp(44)))
        nodeControls.addView(button(if(index<0)"Tap a point"else"Point ${index+1}/${anchors.size}"){nodeCoordinates()}.apply{contentDescription="Point coordinates"},LinearLayout.LayoutParams(dp(134),dp(44)))
        nodeControls.addView(button("›"){canvas.cycleNode(1)}.apply{contentDescription="Next point"},LinearLayout.LayoutParams(dp(44),dp(44)))
    }
    private fun nodeCoordinates(){
        val point=canvas.nodePosition()?:run{canvas.cycleNode(1);return};val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0)}
        val fields=listOf("Local X" to point.x,"Local Y" to point.y).map{(label,value)->EditText(activity).apply{hint=label;contentDescription=label;isSingleLine=true;inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED;setText(value.toString());body.addView(this)}}
        AlertDialog.Builder(activity).setTitle("Point coordinates").setView(body).setPositiveButton("Apply"){_,_->val x=fields[0].text.toString().toFloatOrNull();val y=fields[1].text.toString().toFloatOrNull();if(x!=null && y!=null && x.isFinite() && y.isFinite())canvas.setNodePosition(x,y)else activity.message("Enter valid coordinates")}.setNegativeButton("Cancel",null).show()
    }
    private fun strokeSettings(){
        panel("Stroke settings",true){body->
            val value=TextView(activity).apply{setTextColor(theme.text);text="Stabilization ${(canvas.stabilization*100).roundToInt()}%"};body.addView(value)
            body.addView(SeekBar(activity).apply{max=100;progress=(canvas.stabilization*100).roundToInt();contentDescription="Vector stabilization";progressTintList=android.content.res.ColorStateList.valueOf(StudioColors.accent)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(s:SeekBar){};override fun onStopTrackingTouch(s:SeekBar){activity.vectorStabilization=canvas.stabilization};override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){if(user){canvas.stabilization=p/100f;value.text="Stabilization $p%"}}})},LinearLayout.LayoutParams(-1,dp(48)))
            body.addView(TextView(activity).apply{text="0% follows input directly. Higher values reduce small shakes and add trailing behind the pen. The setting is saved for Vector canvases.";setTextColor(0xffb9c2ca.toInt())})
        }
    }
    private var reference:ReferencePanel?=null
    internal fun showReference(){reference?.let{it.bringToFront();return};reference=ReferencePanel(activity,{color->canvas.ink=color;canvas.edit{editableSelection()?.stroke=color};refresh()},{reference=null}).also{it.attach(this)}}
    private fun shortcutSettings(){
        val actions = listOf("undo" to "Undo", "redo" to "Redo", "toggle_eraser" to "Select / Previous")
        val view = LinearLayout(activity).apply{
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        fun refreshDialog() {
            view.removeAllViews()
            view.addView(TextView(activity).apply{
                text = "Tap 'Set' then press a key combination (Ctrl, Alt, Shift supported)."
                setTextColor(0xffb9c2ca.toInt())
                setPadding(0, 0, 0, dp(12))
            })
            for ((key, name) in actions) {
                val current = activity.getShortcut(key)
                val isSetting = activity.recordingShortcutAction == key
                val row = LinearLayout(activity).apply{
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(52)
                }
                row.addView(TextView(activity).apply{
                    text = name; textSize = 16f; setTextColor(theme.text)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(activity).apply{
                    text = if(isSetting) "Press a new key" else current.display()
                    setTextColor(if(isSetting) StudioColors.accent else 0xffaab8bf.toInt())
                    setPadding(dp(8), 0, dp(12), 0)
                })
                row.addView(Button(activity).apply{
                    text = if(isSetting) "Cancel" else "Set"
                    setOnClickListener {
                        if (isSetting) {
                            activity.cancelRecordingShortcut()
                            refreshDialog()
                        } else {
                            activity.startRecordingShortcut(key) { _, _ -> refreshDialog() }
                            refreshDialog()
                        }
                    }
                })
                row.addView(Button(activity).apply{
                    text = "Reset"
                    setOnClickListener {
                        activity.resetShortcut(key)
                        if (activity.recordingShortcutAction == key) activity.cancelRecordingShortcut()
                        refreshDialog()
                    }
                })
                view.addView(row)
            }
        }
        refreshDialog()
        AlertDialog.Builder(activity)
            .setTitle("Shortcut keys")
            .setOnKeyListener { _,_,event -> activity.recordShortcutKey(event) }
            .setView(view)
            .setPositiveButton("Done") { _, _ -> activity.cancelRecordingShortcut() }
            .setOnDismissListener { activity.cancelRecordingShortcut() }
            .show()
    }
    private fun actions(){panel("Actions",true){body->
        for((label,action) in listOf<Pair<String,()->Unit>>("Canvas Atlas" to {dismissPanel();activity.showAtlas()},"Reference image" to {dismissPanel();showReference()},"Drawing guides" to {canvas.guides.settings(activity,{canvas.invalidate()},{canvas.cancelGesture();dismissPanel();refresh()})},"Import Inkstone / PSD" to {activity.importDocument()},"Stroke settings" to {strokeSettings()},"Object" to {objectActions()},"Fill" to {canvas.fillColor=if(canvas.fillColor==null)canvas.ink else null;canvas.edit{editableSelection()?.takeIf{it.isClosed}?.fill=canvas.fillColor}},"Fit" to {canvas.fit()},"Shortcut keys" to {shortcutSettings()},"Prefs" to {preferences()},"Save artwork" to {save()}))body.addView(button(label){action()},LinearLayout.LayoutParams(-1,dp(48)))
        body.addView(TextView(activity).apply{text="Select an object to move it. Drag a corner to resize, the upper handle to rotate. Nodes selects anchors on the curve. Drag a selected anchor or its white handles; use Previous/Next and Point coordinates for precise edits.";setTextColor(0xffb9c2ca.toInt());setPadding(dp(8),dp(12),dp(8),dp(12))})
    }}
    private fun save(){
        canvas.cancelGesture()
        AlertDialog.Builder(activity).setTitle("Save Vector artwork")
            .setItems(arrayOf("Save project to Inkstone","Export SVG · editable vectors","Save PNG to photo gallery","Save JPG to photo gallery","Export PNG to folder","Export JPG to folder","Export PSD · rasterized layers","Export editable Inkstone")){_,which->
                when(which){0->activity.saveCurrent{if(it)activity.message("Vector project saved")};1->activity.export("SVG");2->activity.saveImageToGallery("PNG");3->activity.saveImageToGallery("JPEG");4->activity.export("PNG");5->activity.export("JPEG");6->activity.export("PSD");7->activity.export("Inkstone")}
            }.setNegativeButton("Cancel",null).show()
    }
    private fun colors(){
        canvas.cancelGesture();panel("Colors"){body->
            body.addView(RecentColorRow(activity){canvas.ink=it;dismissPanel();colors();refresh()})
            val hex=EditText(activity).apply{setTextColor(theme.text);setText(String.format("#%08X",canvas.ink));isSingleLine=true;contentDescription="Vector color hex"}
            val preview=View(activity).apply{setBackgroundColor(canvas.ink);contentDescription="Vector active color preview"}
            body.addView(preview,LinearLayout.LayoutParams(dp(42),dp(28)).apply{gravity=Gravity.RIGHT;bottomMargin=dp(8)})
            var syncing=false
            lateinit var picker:ColorDisc
            fun active(color:Int,fromPicker:Boolean){
                canvas.ink=color;preview.setBackgroundColor(color);refresh()
                if(!fromPicker)picker.setColor(color)
                if(fromPicker){syncing=true;hex.setText(String.format("#%08X",color));syncing=false}
            }
            picker=ColorDisc(activity,canvas.ink,false){active((it and 0xffffff) or (Color.alpha(canvas.ink) shl 24),true)}
            hex.addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                    if(syncing)return
                    val value=s.toString().trim()
                    if(value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")))runCatching{Color.parseColor(value)}.onSuccess{active(it,false)}
                }
                override fun afterTextChanged(s:android.text.Editable?){}
            })
            body.addView(picker,LinearLayout.LayoutParams(-1,dp(270)));body.addView(hex)
            val choices=LinearLayout(activity)
            for(label in listOf("Stroke","Fill","No fill"))choices.addView(button(label){
                if(canvas.selected!=null && editableSelection()==null){activity.message("This layer is locked");return@button}
                val color=runCatching{Color.parseColor(hex.text.toString().trim())}.getOrElse{activity.message("Invalid hex color");return@button}
                val used=editableSelection()?.takeIf{label=="Stroke" || label=="Fill" && it.isClosed}
                canvas.edit{when(label){"Stroke"->{canvas.ink=color;editableSelection()?.stroke=color};"Fill"->{canvas.fillColor=color;editableSelection()?.takeIf{it.isClosed}?.fill=color};else->{canvas.fillColor=null;editableSelection()?.fill=null}}};if(used!=null)RecentColors(activity).record(color)
            },LinearLayout.LayoutParams(0,dp(48),1f));body.addView(choices)
        }
    }
    private fun objectActions(){
        canvas.cancelGesture();val obj=canvas.selected
        if(obj==null){activity.message("Choose Select, then tap an object. Drag a corner to resize or the upper handle to rotate. Nodes edits path points.");return}
        AlertDialog.Builder(activity).setTitle("Selected ${obj.kind}").setItems(arrayOf("Duplicate","Delete","Bring forward","Send backward")){_,which->
            if(editableSelection()==null){activity.message("This layer is locked");return@setItems}
            canvas.edit{
                val layer=document.owner(obj.id)!!;val index=layer.objects.indexOfFirst{it.id==obj.id}
                when(which){
                    0->{val copy=VectorObject(document.nextId(),obj.kind,obj.points.map{android.graphics.PointF(it.x,it.y)}.toMutableList(),obj.stroke,obj.fill,obj.width,android.graphics.Matrix(obj.matrix).apply{postTranslate(20f,20f)});layer.objects.add(index+1,copy);canvas.selectedId=copy.id}
                    1->{layer.objects.removeAt(index);canvas.selectedId=null}
                    2->if(index<layer.objects.lastIndex){layer.objects.removeAt(index);layer.objects.add(index+1,obj)}
                    3->if(index>0){layer.objects.removeAt(index);layer.objects.add(index-1,obj)}
                }
            }
        }.show()
    }
    private fun layers(){
        canvas.cancelGesture();panel("Layers"){body->
            body.tag="layer-list"
            body.addView(button("Add layer"){run{canvas.edit{val id=document.nextId();document.layers.add(document.active+1,VectorLayer(id,"Layer $id").apply{groupId=document.layers[document.active].groupId});document.active++;canvas.selectedId=null};dismissPanel();layers()}},LinearLayout.LayoutParams(-1,dp(44)))
            body.addView(button("Create layer group"){activity.textDialog("Group name","New group"){name->canvas.edit{document.createGroup(name)};dismissPanel();layers()}},LinearLayout.LayoutParams(-1,dp(44)))
            val seen=mutableSetOf<Long>()
            fun header(group:VectorGroup){
                val line=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
                line.addView(button((if(group.collapsed)"▸ "else"▾ ")+group.name+(if(group.opacity<1f)" · ${(group.opacity*100).roundToInt()}%"else"")){canvas.edit{group.collapsed=!group.collapsed};dismissPanel();layers()},LinearLayout.LayoutParams(0,dp(48),1f))
                line.addView(button(GroupAppearance.label(group.blend)){vectorGroupOptions(group)},LinearLayout.LayoutParams(dp(44),dp(48)))
                line.addView(CheckBox(activity).apply{isChecked=group.visible;contentDescription=group.name+" group visible";setOnCheckedChangeListener{_,v->canvas.edit{group.visible=v}}},LinearLayout.LayoutParams(dp(44),dp(48)))
                line.tag="group-${group.id}"
                LayerDrag.target(line,document,folder=true,open=!group.collapsed,canJoin={source->document.layers.firstOrNull{it.id==source}?.groupId!=group.id}){id,above,join->
                    val own=document.layers.firstOrNull{it.id==id}?.groupId==group.id
                    dragVectorLayer(id,if(join)null else document.layers.lastOrNull{it.groupId==group.id}?.id,if(join || (own && !above))group.id else null,if(own && !above)true else above)
                }
                body.addView(line)
            }
            for(layer in document.layers.asReversed()){
                val group=document.groups.firstOrNull{it.id==layer.groupId}
                if(group!=null){if(seen.add(group.id))header(group);if(group.collapsed)continue}
                val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(4),dp(8),dp(4));background=android.graphics.drawable.GradientDrawable().apply{setColor(if(document.layers[document.active]===layer)StudioColors.accent else 0x226e7175);cornerRadius=dp(StudioShape.CORNER_DP).toFloat()}}
                val thumbnail=document.bitmap(72,layer)
                row.addView(ImageView(activity).apply{setImageBitmap(thumbnail);scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(dp(56),dp(56)))
                row.addView(button(layer.name){document.active=document.layers.indexOf(layer);canvas.selectedId=null;activity.scheduleSave();dismissPanel();layers()},LinearLayout.LayoutParams(0,dp(56),1f))
                row.addView(ToolIcon(activity,"Actions"){layerOptions(layer)},LinearLayout.LayoutParams(dp(40),dp(44)))
                row.addView(CheckBox(activity).apply{isChecked=layer.visible;buttonTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);contentDescription=layer.name+" visible";setOnCheckedChangeListener{_,v->canvas.edit{layer.visible=v}}},LinearLayout.LayoutParams(dp(44),dp(44)))
                LayerDrag.source(row,document,layer.id,layer.name)
                LayerDrag.source(row.getChildAt(1),document,layer.id,layer.name)
                row.tag="layer-${layer.id}"
                LayerDrag.target(row,document){id,above,_->val index=document.layers.indexOf(layer);val bottom=layer.groupId!=null && (index==0 || document.layers[index-1].groupId!=layer.groupId);dragVectorLayer(id,layer.id,if(!above && bottom)null else layer.groupId,above)}
                body.addView(row,LinearLayout.LayoutParams(-1,dp(68)).apply{leftMargin=if(group!=null)dp(20)else 0;bottomMargin=dp(4)})
            }
            document.groups.filter{seen.add(it.id)}.forEach{header(it)}
            body.addView(button("Background colour"){backgroundSettings()},LinearLayout.LayoutParams(-1,dp(48)))
        }
    }
    private fun layerOptions(layer:VectorLayer){
        document.active=document.layers.indexOf(layer);canvas.selectedId=null;refresh()
        AlertDialog.Builder(activity).setTitle(layer.name).setItems(arrayOf("Rename",if(layer.locked)"Unlock" else "Lock","Opacity","Move up","Move down","Delete layer","Layer group")){_,op->
            when(op){
                0->activity.textDialog("Layer name",layer.name){name->canvas.edit{layer.name=name};dismissPanel();layers()}
                1->canvas.edit{layer.locked=!layer.locked}
                2->{val seek=SeekBar(activity).apply{max=100;progress=(layer.opacity*100).roundToInt()};AlertDialog.Builder(activity).setTitle("Layer opacity").setView(seek).setPositiveButton("Apply"){_,_->canvas.edit{layer.opacity=seek.progress/100f}}.show()}
                3->canvas.edit{val i=document.layers.indexOf(layer);if(i<document.layers.lastIndex){document.layers.removeAt(i);document.layers.add(i+1,layer);document.active=i+1}}
                4->canvas.edit{val i=document.layers.indexOf(layer);if(i>0){document.layers.removeAt(i);document.layers.add(i-1,layer);document.active=i-1}}
                6->chooseVectorGroup(layer)
                5->if(document.layers.size>1)canvas.edit{document.layers.remove(layer);document.active=document.active.coerceAtMost(document.layers.lastIndex)}else activity.message("Keep at least one layer")
            };dismissPanel();layers()
        }.setNegativeButton("Done",null).show()
    }
    private fun dragVectorLayer(id:Long,target:Long?,group:Long?,above:Boolean){
        val motion=LayerDrag.capture(this,document);canvas.edit{document.dragLayer(id,target,group,above)}
        replacingLayerPanel=true;try{layers()}finally{replacingLayerPanel=false};motion.settle(this)
    }
    private fun chooseVectorGroup(layer:VectorLayer){
        val options=listOf("New group","Remove from group")+document.groups.map{it.name}
        AlertDialog.Builder(activity).setTitle("Layer group").setItems(options.toTypedArray()){_,which->
            when(which){
                0->activity.textDialog("Group name","New group"){name->canvas.edit{document.createGroup(name)};dismissPanel();layers()}
                else->{canvas.edit{document.moveToGroup(layer,if(which==1)null else document.groups[which-2].id)};dismissPanel();layers()}
            }
        }.show()
    }
    private fun vectorGroupOptions(group:VectorGroup){
        val body=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));setBackgroundColor(theme.surface)}
        val popup=PopupWindow(body,dp(260).coerceAtMost(width-dp(20)),-2,true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(16).toFloat();isOutsideTouchable=true}
        fun item(label:String,action:()->Unit){body.addView(button(label){popup.dismiss();action()},LinearLayout.LayoutParams(-1,dp(48)))}
        fun finish(){dismissPanel();layers()}
        item(if(group.collapsed)"Expand group"else"Collapse group"){canvas.edit{group.collapsed=!group.collapsed};finish()}
        item(if(group.locked)"Unlock group"else"Lock group"){canvas.edit{group.locked=!group.locked};finish()}
        item("Rename"){panel("Rename group"){b->val field=EditText(activity).apply{setText(group.name)};b.addView(field);b.addView(button("Save"){canvas.edit{group.name=field.text.toString().take(100)};finish()})}}
        item("Move group contents"){dismissPanel();canvas.moveGroup(group.id);activity.message("Drag on the canvas to move the group. Lift to apply; Undo restores.");refresh()}
        body.addView(button("Blend & opacity"){
            val b=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));setBackgroundColor(theme.surface)}
            val child=PopupWindow(ScrollView(activity).apply{addView(b)},dp(240),min(dp(560),height-dp(100)),true).apply{setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));elevation=dp(18).toFloat();isOutsideTouchable=true}
            b.addView(button("← Back"){child.dismiss()})
            val label=button("Opacity ${(group.opacity*100).toInt()}%"){};b.addView(label)
            b.addView(SeekBar(activity).apply{max=100;progress=(group.opacity*100).toInt();contentDescription="Group opacity";setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onStartTrackingTouch(v:SeekBar){};override fun onStopTrackingTouch(v:SeekBar){}
                override fun onProgressChanged(v:SeekBar,p:Int,user:Boolean){if(user){canvas.edit{group.opacity=p/100f};label.text="Opacity $p%"}}
            })})
            for(mode in GroupAppearance.modes)b.addView(button(mode){canvas.edit{group.blend=mode}})
            val pos=IntArray(2);body.getLocationOnScreen(pos);val decor=IntArray(2);activity.window.decorView.getLocationOnScreen(decor);pos[0]-=decor[0];pos[1]-=decor[1];child.width=min(dp(240),(pos[0]-dp(8)).coerceAtLeast(dp(160)))
            popup.setOnDismissListener{child.dismiss()}
            child.showAtLocation(this,Gravity.TOP or Gravity.LEFT,(pos[0]-child.width-dp(6)).coerceAtLeast(dp(4)),dp(barHeight))
        },LinearLayout.LayoutParams(-1,dp(48)))
        item("Ungroup"){canvas.edit{document.ungroup(group)};finish()}
        popup.showAtLocation(this,Gravity.TOP or Gravity.RIGHT,dp(350).coerceAtMost((width-dp(270)).coerceAtLeast(0)),dp(100))
    }
    internal fun backgroundSettings(){panel("Background colour"){body->
        body.addView(BackgroundControls(activity,document.background,document.backgroundVisible,{canvas.ink}){color,visible->
            canvas.edit{if(color!=null)document.background=color;document.backgroundVisible=visible}
        })
    }}
    private fun preferences(){
        canvas.cancelGesture()
        val labels=arrayOf("Pen-only input","Protect controls from palm","Two-finger tap: Undo","Three-finger tap: Redo")
        AlertDialog.Builder(activity).setTitle("Vector preferences").setMultiChoiceItems(labels,booleanArrayOf(activity.penOnlyInput,activity.protectVectorControls,activity.twoFingerUndo,activity.threeFingerRedo)){_,index,enabled->activity.setVectorInputPreference(index,enabled)}
            .setMessage(null).setPositiveButton("Done",null).show()
    }
    override fun dispatchTouchEvent(e:MotionEvent):Boolean{
        val pen=(0 until e.pointerCount).any{e.getToolType(it) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER)}
        if(e.actionMasked==MotionEvent.ACTION_CANCEL)penTouchActive=false
        if(pen)when(e.actionMasked){MotionEvent.ACTION_DOWN->penTouchActive=true;MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->penTouchActive=false;MotionEvent.ACTION_POINTER_UP->if(e.getToolType(e.actionIndex) in listOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_ERASER))penTouchActive=false}
        if(e.actionMasked==MotionEvent.ACTION_DOWN && !pen){val dock=e.x<dp(44) && e.y>=dp(barHeight);blocked=activity.protectVectorControls && penTouchActive && (dock || e.y<dp(barHeight))}
        if(blocked && !pen){if(e.actionMasked in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL))blocked=false;return true}
        return super.dispatchTouchEvent(e)
    }
    override fun onDetachedFromWindow(){colorDrop.cancel();super.onDetachedFromWindow()}
    fun key(code:Int,event:KeyEvent):Boolean {
        if(event.isCtrlPressed && code==KeyEvent.KEYCODE_S){activity.saveCurrent();return true}
        if(code==KeyEvent.KEYCODE_ESCAPE){canvas.cancelGesture();canvas.selectedId=null;refresh();return true}
        return false
    }
}
