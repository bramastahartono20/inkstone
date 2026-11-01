package art.inkstone

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.math.*

class GalleryView(private val activity: MainActivity) : LinearLayout(activity) {
    private val density=resources.displayMetrics.density
    private fun dp(v:Int)=(v*density).roundToInt()
    private val prefs=activity.getSharedPreferences("gallery-library",0)
    private var sort=prefs.getInt("sort",0).coerceIn(0,GalleryLibrary.sorts.lastIndex)
    private var inside=prefs.getString("inside",null)
    private var filterTag=prefs.getString("tag",null)
    private var selecting=false
    private val selected=mutableSetOf<String>()
    private val theme get()=StudioTheme.of(activity)
    private val blue=StudioColors.accent
    private var motion:GalleryMotion?=null
    private var renderedLocation:String?=null
    private val thumbnails=linkedMapOf<String,android.graphics.Bitmap>()
    private val artworkCards=mutableMapOf<String,View>()
    private fun label(text:String,size:Float=15f,color:Int=theme.text)=TextView(activity).apply{
        this.text=text;textSize=size;setTextColor(theme.label(color));gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(12),0)
    }
    init {orientation=VERTICAL;setBackgroundColor(theme.surface);rebuild()}
    private val resizeGallery=Runnable{if(width>0)rebuild()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){
        super.onSizeChanged(w,h,oldw,oldh)
        if(w>0 && w!=oldw){removeCallbacks(resizeGallery);post(resizeGallery)}
    }
    private fun rebuild(animate:Boolean=false,destination:String?=null,release:Pair<String,android.graphics.PointF>?=null){
        val oldScroll=(findViewWithTag<View>("gallery-scroll") as? ScrollView)?.scrollY?:0
        motion?.cancel(this);motion=if(animate && childCount>0)GalleryMotion.capture(this,destination,release)else null
        removeAllViews();artworkCards.clear()
        val viewportWidth=if(width>0)width else resources.displayMetrics.widthPixels
        val narrow=viewportWidth/density<600
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(if(narrow)12 else 36),dp(6),dp(16),0)}
        header.addView(label("Inkstone",if(narrow)27f else 36f).apply{typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)},LayoutParams(0,dp(80),1f))
        fun action(text:String,run:()->Unit){header.addView(label(text,15f).apply{gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setOnClickListener{run()}},LayoutParams(-2,dp(52)))}
        action(if(selecting)"Done" else "Select"){selecting=!selecting;selected.clear();rebuild();if(selecting)artworkCards.values.forEach{v->v.animate().scaleX(.97f).scaleY(.97f).setDuration(450).withEndAction{v.animate().scaleX(1f).scaleY(1f).setDuration(450).start()}.start()}}
        if(!narrow){action("Import"){importArtwork()}}
        header.addView(ToolIcon(activity,"Help"){Tutorials.show(activity)}.apply{light=theme.light;contentDescription="Tutorials"},LayoutParams(dp(44),dp(52)))
        header.addView(ToolIcon(activity,"Add"){activity.createDialog()},LayoutParams(dp(48),dp(52)))
        addView(header)
        if(narrow){val imports=LinearLayout(activity).apply{gravity=Gravity.RIGHT;setPadding(0,0,dp(18),0)}
            for((name,run)in listOf("Import" to {importArtwork()}))imports.addView(label(name).apply{isFocusable=true;setOnClickListener{run()}},LayoutParams(-2,dp(42)))
            addView(imports)
        }
        val artworks=activity.store.list();val stacks=activity.store.stacks()
        if(inside!=null && stacks.none{it.id==inside}){inside=null;prefs.edit().remove("inside").apply()}
        val controls=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(if(narrow)12 else 36),0,dp(16),0)}
        fun control(title:String,description:String=title,action:()->Unit){controls.addView(label(title,13f,blue).apply{contentDescription=description;isClickable=true;isFocusable=true;setOnClickListener{action()}},LayoutParams(-2,dp(44)))}
        control("Sort: ${GalleryLibrary.sorts[sort]}","Sort artworks"){
            AlertDialog.Builder(activity).setTitle("Sort artworks").setSingleChoiceItems(GalleryLibrary.sorts.toTypedArray(),sort){dialog,index->sort=index;prefs.edit().putInt("sort",index).apply();dialog.dismiss();rebuild(true)}.setNegativeButton("Cancel",null).show()
        }
        control(filterTag?.let{"Tag: $it"}?:"Tags","Filter by tag"){filterTags(artworks)}
        control("New stack"){createStack()}
        addView(HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(controls)},LayoutParams(-1,dp(44)))
        inside?.let{id->val stack=stacks.first{it.id==id};val count=artworks.count{it.stackId==id}
            addView(label("‹ Gallery   /   ${stack.name} · $count artworks",17f).apply{contentDescription="Back to gallery";setPadding(dp(if(narrow)22 else 48),0,dp(16),0);isClickable=true;isFocusable=true;setOnClickListener{inside=null;prefs.edit().remove("inside").apply();rebuild(true)}},LayoutParams(-1,dp(44)))
        }
        if(stacks.isNotEmpty()){
            val destinations=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(if(narrow)12 else 36),0,dp(16),0)}
            destinations.addView(label("Drag to:",12f,0xffaab1b8.toInt()),LayoutParams(-2,dp(40)))
            fun destination(name:String,id:String?){val chip=label(name,12f).apply{tag="gallery:destination:${id?:"root"}";contentDescription="Drop artwork into $name";background=GradientDrawable().apply{setColor(0xff30363c.toInt());cornerRadius=dp(StudioShape.CORNER_DP).toFloat()}}
                ArtworkDrag.target(chip,activity.store){token,point->moveArtworks(token.ids,id,token.primary to point)}
                destinations.addView(chip,LayoutParams(-2,dp(36)).apply{setMargins(dp(3),0,dp(3),0)})
            }
            destination("Gallery",null);stacks.forEach{destination(it.name,it.id)}
            addView(HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(destinations)},LayoutParams(-1,dp(44)))
        }
        val entries=GalleryLibrary.entries(artworks,stacks,inside,filterTag,sort)
        val location="$inside|$filterTag|$sort";val scrollPosition=if(renderedLocation==location)oldScroll else 0;renderedLocation=location
        val scroll=ScrollView(activity).apply{tag="gallery-scroll";isFillViewport=entries.isEmpty();isVerticalScrollBarEnabled=false}
        val content=LinearLayout(activity).apply{orientation=VERTICAL;setPadding(dp(if(narrow)16 else 42),dp(20),dp(if(narrow)16 else 42),dp(40))}
        if(entries.isEmpty()){
            val empty=LinearLayout(activity).apply{orientation=VERTICAL;gravity=Gravity.CENTER}
            empty.addView(label(if(inside!=null)"This stack is empty."else if(filterTag!=null)"No artworks with this tag."else"Your next canvas.",30f).apply{gravity=Gravity.CENTER},LayoutParams(-1,dp(65)))
            empty.addView(label(if(inside!=null)"Select artworks in the gallery, then choose Stack."else if(filterTag!=null)"Choose All tags to show your library."else"Tap + to create an artwork, or import a photo.",15f,0xffb0b0b0.toInt()).apply{gravity=Gravity.CENTER},LayoutParams(-1,dp(64)))
            empty.addView(label("Create canvas",16f,blue).apply{gravity=Gravity.CENTER;isFocusable=true;setOnClickListener{activity.createDialog()}},LayoutParams(-1,dp(52)))
            content.addView(empty,LayoutParams(-1,-1))
        } else {
            val available=viewportWidth/density-(if(narrow)32 else 84)
            val columns=if(available<500)2 else if(available<850)3 else 4
            val cardW=(available/columns).toInt()
            for(chunk in entries.chunked(columns)){
                val row=LinearLayout(activity)
                for(entry in chunk){row.addView(if(entry.art!=null)card(entry.art,cardW)else stackCard(entry,cardW),LayoutParams(0,-2,1f))}
                repeat(columns-chunk.size){row.addView(View(activity),LayoutParams(0,dp(1),1f))}
                content.addView(row)
            }
        }
        scroll.addView(content);addView(scroll,LayoutParams(-1,0,1f))
        if(selecting){val bottom=LinearLayout(activity).apply{gravity=Gravity.CENTER;setBackgroundColor(theme.surface)}
            bottom.addView(label("${selected.size} selected",14f,0xffbbbbbb.toInt()),LayoutParams(0,dp(60),1f))
            bottom.addView(label("Stack",15f,blue).apply{contentDescription="Move selected to stack";isFocusable=true;setOnClickListener{if(selected.isNotEmpty())chooseStack(selected.toList())}},LayoutParams(-2,dp(60)))
            bottom.addView(label("Tags",15f,blue).apply{contentDescription="Tag selected artworks";isFocusable=true;setOnClickListener{if(selected.isNotEmpty())editTags(selected.toList())}},LayoutParams(-2,dp(60)))
            bottom.addView(label("Duplicate",15f,blue).apply{isFocusable=true;setOnClickListener{
                val ids=selected.toList();activity.io.execute{val result=runCatching{ids.forEach{activity.store.duplicate(it)}};activity.runOnUiThread{result.onFailure{activity.message(it.message?:"Could not duplicate")};selected.clear();rebuild()}}
            }},LayoutParams(-2,dp(60)))
            bottom.addView(label("Delete",15f,0xffff6258.toInt()).apply{isFocusable=true;setOnClickListener{deleteSelected()}},LayoutParams(-2,dp(60)));addView(HorizontalScrollView(activity).apply{isHorizontalScrollBarEnabled=false;addView(bottom)},LayoutParams(-1,dp(60)))
        }
        scroll.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{if(scroll.viewTreeObserver.isAlive)scroll.viewTreeObserver.removeOnPreDrawListener(this);scroll.scrollTo(0,scrollPosition);return true}})
        StudioTheme.apply(this)
        motion?.play(this)
    }
    internal fun handleBack():Boolean{
        if(selecting){selecting=false;selected.clear();rebuild();return true}
        if(inside!=null){inside=null;prefs.edit().remove("inside").apply();rebuild(true);return true}
        if(filterTag!=null){filterTag=null;prefs.edit().remove("tag").apply();rebuild(true);return true}
        return false
    }
    internal fun setOpening(id:String?){
        if(id!=null){motion?.cancel(this);motion=null}
        artworkCards.forEach{(key,card)->card.isEnabled=id==null;card.alpha=if(key==id).55f else 1f
            if(android.os.Build.VERSION.SDK_INT>=30)card.stateDescription=if(key==id)"Opening artwork"else null
        }
    }
    private fun card(art:Artwork,widthDp:Int):View{
        val card=LinearLayout(activity).apply{orientation=VERTICAL;setPadding(dp(10),0,dp(10),dp(30));isClickable=true;isFocusable=true;contentDescription="${art.name}, ${art.width} by ${art.height} pixels";tag="gallery:art:${art.id}"}
        val frame=FrameLayout(activity).apply{
            background=GradientDrawable().apply{setColor(0xff202020.toInt());cornerRadius=dp(StudioShape.CORNER_DP).toFloat();if(art.id in selected)setStroke(dp(3),blue)}
        }
        val image=ImageView(activity).apply{scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(7),dp(7),dp(7),dp(7))}
        frame.addView(image,FrameLayout.LayoutParams(-1,-1))
        frame.addView(label("⋯",24f).apply{gravity=Gravity.CENTER;visibility=if(selecting)View.GONE else View.VISIBLE;contentDescription="Artwork options for ${art.name}";tooltipText="Rename, tags, stack and more";background=GradientDrawable().apply{setColor(0x9920252b.toInt());cornerRadius=dp(StudioShape.CORNER_DP).toFloat()};setOnClickListener{artworkOptions(art)}},FrameLayout.LayoutParams(dp(44),dp(36),Gravity.TOP or Gravity.RIGHT))
        loadThumbnail(image,art.id)
        if(selecting)frame.addView(label(if(art.id in selected)"☑" else "☐",22f,blue).apply{gravity=Gravity.RIGHT or Gravity.TOP},FrameLayout.LayoutParams(-1,dp(40)))
        card.addView(frame,LayoutParams(-1,dp((widthDp*.72f).toInt())))
        card.addView(label(art.name,14f).apply{setPadding(dp(2),0,dp(2),0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LayoutParams(-1,dp(32)))
        card.addView(label("${art.width} × ${art.height}px${if(art.mode=="vector")" · Vector" else ""}",11f,0xff999999.toInt()).apply{setPadding(dp(2),0,0,0)},LayoutParams(-1,dp(18)))
        if(art.tags.isNotEmpty())card.addView(label(art.tags.joinToString(" · "){"#$it"},11f,blue).apply{setPadding(dp(2),0,0,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LayoutParams(-1,dp(22)))
        artworkCards[art.id]=card
        card.setOnClickListener{if(activity.navigationBusy)return@setOnClickListener;if(selecting){if(!selected.add(art.id))selected.remove(art.id);rebuild()}else activity.openArtwork(art)}
        ArtworkDrag.source(card,activity.store,art.id){if(activity.navigationBusy)emptyList()else selected.toList()}
        ArtworkDrag.target(card,activity.store){token,point->
            if(art.id !in token.ids && !activity.navigationBusy){
                val stack=art.stackId?:activity.store.createStack("New stack")
                activity.store.assignStack((token.ids+art.id).distinct(),stack)
                selected.clear();selecting=false;rebuild(true,"stack:$stack","art:${token.primary}" to point)
            }
        }
        return card
    }
    private fun importArtwork(){
        AlertDialog.Builder(activity).setTitle("Import artwork").setItems(arrayOf("Photo / image","Inkstone / PSD document")){_,i->if(i==0)activity.importImage()else activity.importDocument()}.show()
    }
    private fun artworkOptions(art:Artwork){
        if(activity.navigationBusy)return
        AlertDialog.Builder(activity).setTitle(art.name).setItems(arrayOf("Rename","Duplicate","Delete","Tags","Move to stack")){_,which->when(which){
            0->activity.textDialog("Artwork name",art.name){name->activity.store.rename(art.id,name);rebuild(true)}
            1->activity.io.execute{val result=runCatching{activity.store.duplicate(art.id)};activity.runOnUiThread{result.onFailure{activity.message(it.message?:"Could not duplicate")};rebuild(true)}}
            2->{selected.clear();selected.add(art.id);deleteSelected()}
            3->editTags(listOf(art.id))
            4->chooseStack(listOf(art.id))
        }}.show()
    }
    private fun stackCard(entry:GalleryEntry,widthDp:Int):View{
        val stack=entry.stack!!
        val card=LinearLayout(activity).apply{orientation=VERTICAL;setPadding(dp(10),0,dp(10),dp(30));isClickable=true;isFocusable=true;contentDescription="Stack ${stack.name}, ${entry.total} artworks";tag="gallery:stack:${stack.id}"}
        val frame=FrameLayout(activity)
        val height=dp((widthDp*.72f).toInt())
        for((index,art) in entry.members.take(3).asReversed().withIndex()){
            val image=ImageView(activity).apply{scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(dp(6),dp(6),dp(6),dp(6));rotation=(index-1)*3f;background=GradientDrawable().apply{setColor(0xff30343a.toInt());cornerRadius=dp(StudioShape.CORNER_DP).toFloat()}}
            frame.addView(image,FrameLayout.LayoutParams(-1,-1).apply{setMargins(dp(8+index*3),dp(8+index*2),dp(8+index*3),dp(8+index*2))})
            loadThumbnail(image,art.id)
        }
        if(entry.members.isEmpty())frame.background=GradientDrawable().apply{setColor(0xff30343a.toInt());cornerRadius=dp(StudioShape.CORNER_DP).toFloat()}
        card.addView(frame,LayoutParams(-1,height));card.addView(label(stack.name,14f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LayoutParams(-1,dp(32)))
        card.addView(label(if(entry.members.size!=entry.total)"${entry.members.size} of ${entry.total} artworks"else"${entry.total} artworks",11f,0xffaaaaaa.toInt()),LayoutParams(-1,dp(22)))
        card.setOnClickListener{if(!activity.navigationBusy){inside=stack.id;prefs.edit().putString("inside",inside).apply();rebuild(true)}}
        card.setOnLongClickListener{AlertDialog.Builder(activity).setTitle(stack.name).setItems(arrayOf("Rename stack","Ungroup stack")){_,which->if(which==0)activity.textDialog("Stack name",stack.name){activity.store.renameStack(stack.id,it);rebuild(true)}else{activity.store.unstack(stack.id);rebuild(true)}}.show();true}
        ArtworkDrag.target(card,activity.store){token,point->moveArtworks(token.ids,stack.id,token.primary to point)}
        return card
    }
    private fun loadThumbnail(image:ImageView,id:String){
        thumbnails[id]?.let{image.setImageBitmap(it);return}
        activity.io.execute{val bitmap=activity.store.thumbnail(id);activity.runOnUiThread{if(bitmap!=null && isAttachedToWindow){thumbnails[id]=bitmap;while(thumbnails.size>24)thumbnails.remove(thumbnails.keys.first());if(image.isAttachedToWindow)image.setImageBitmap(bitmap)}}}
    }
    private fun moveArtworks(ids:List<String>,destination:String?,release:Pair<String,android.graphics.PointF>?=null){
        if(activity.navigationBusy)return
        val moving=activity.store.list().filter{it.id in ids && it.stackId!=destination}.map{it.id};if(moving.isEmpty())return
        activity.store.assignStack(moving,destination);selected.clear();selecting=false
        val target=if(inside==null && destination!=null)"stack:$destination"else"destination:${destination?:"root"}"
        rebuild(true,target,release?.let{"art:${it.first}" to it.second})
    }
    override fun onDetachedFromWindow(){removeCallbacks(resizeGallery);motion?.cancel(this);motion=null;thumbnails.clear();super.onDetachedFromWindow()}
    private fun createStack(ids:List<String> = emptyList()){
        activity.textDialog("Stack name","New stack"){name->val id=activity.store.createStack(name);if(ids.isNotEmpty())activity.store.assignStack(ids,id);inside=null;prefs.edit().remove("inside").apply();selected.clear();selecting=false;rebuild(true)}
    }
    private fun chooseStack(ids:List<String>){
        val stacks=activity.store.stacks()
        AlertDialog.Builder(activity).setTitle("Move to stack").setItems((listOf("New stack","Gallery (no stack)")+stacks.map{it.name}).toTypedArray()){_,which->
            if(which==0)createStack(ids)else{activity.store.assignStack(ids,if(which==1)null else stacks[which-2].id);selected.clear();selecting=false;rebuild(true)}
        }.show()
    }
    private fun editTags(ids:List<String>){
        val arts=activity.store.list().filter{it.id in ids};val initial=arts.firstOrNull()?.tags.orEmpty().filter{tag->arts.all{it.tags.contains(tag)}}.joinToString(", ")
        val input=EditText(activity).apply{setText(initial);hint="Sketch, Portrait, Practice";contentDescription="Artwork tags";setPadding(dp(24),dp(12),dp(24),dp(12))}
        AlertDialog.Builder(activity).setTitle("Artwork tags").setMessage("Separate tags with commas. Leave empty to remove tags.").setView(input).setPositiveButton("Save"){_,_->activity.store.setTags(ids,input.text.toString().split(","));rebuild(true)}.setNegativeButton("Cancel",null).show()
    }
    private fun filterTags(artworks:List<Artwork>){
        val tags=artworks.flatMap{it.tags}.distinctBy{it.lowercase(java.util.Locale.ROOT)}.sortedBy{it.lowercase(java.util.Locale.ROOT)}
        AlertDialog.Builder(activity).setTitle("Filter by tag").setSingleChoiceItems((listOf("All tags")+tags).toTypedArray(),tags.indexOfFirst{it.equals(filterTag,true)}+1){dialog,index->filterTag=if(index==0)null else tags[index-1];prefs.edit().putString("tag",filterTag).apply();dialog.dismiss();rebuild(true)}.setNegativeButton("Cancel",null).show()
    }
    private fun deleteSelected(){
        if(selected.isEmpty())return
        AlertDialog.Builder(activity).setTitle("Delete ${selected.size} artwork${if(selected.size==1)"" else "s"}?")
            .setMessage("This removes the local artwork and its layers. Export a copy first if you want to keep it.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete"){_,_->selected.forEach{activity.store.delete(it)};selected.clear();rebuild()}.show()
    }
}
