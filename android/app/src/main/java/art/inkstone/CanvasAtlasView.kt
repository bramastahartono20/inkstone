package art.inkstone

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONObject
import kotlin.math.*

internal class AtlasLayout(context:Context){
    private val prefs=context.getSharedPreferences("canvas-atlas",Context.MODE_PRIVATE)
    private val positions=runCatching{JSONObject(prefs.getString("positions","{}")!!)}.getOrDefault(JSONObject())
    fun ensure(artworks:List<Artwork>){
        val occupied=mutableListOf<RectF>();var changed=false
        for((i,art) in artworks.withIndex())if(positions.has(art.id))occupied.add(rect(art,i))
        for(art in artworks)if(!positions.has(art.id)){
            var slot=0;var candidate=rect(art,slot)
            while(occupied.any{RectF.intersects(it,candidate)}){slot++;candidate=rect(art,slot)}
            positions.put(art.id,JSONObject().put("x",candidate.left.toDouble()).put("y",candidate.top.toDouble()));occupied.add(candidate);changed=true
        }
        if(changed)prefs.edit().putString("positions",positions.toString()).apply()
    }
    fun rect(art:Artwork,index:Int):RectF{
        val p=positions.optJSONObject(art.id);val x=p?.optDouble("x")?.toFloat()?.takeIf{it.isFinite()}?:((index%4)*360f)
        val y=p?.optDouble("y")?.toFloat()?.takeIf{it.isFinite()}?:((index/4)*360f)
        val scale=260f/max(art.width,art.height).coerceAtLeast(1)
        return RectF(x,y,x+art.width*scale,y+art.height*scale)
    }
    fun move(id:String,x:Float,y:Float){require(x.isFinite() && y.isFinite());positions.put(id,JSONObject().put("x",x.toDouble()).put("y",y.toDouble()));prefs.edit().putString("positions",positions.toString()).apply()}
}

/** Shares the editor camera; inactive documents are previews, never live engines. */
internal class CanvasAtlas(private val activity:MainActivity){
    private val d=activity.resources.displayMetrics.density
    private val layout=AtlasLayout(activity)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val images=object:LinkedHashMap<String,Bitmap>(64,.75f,true){}
    private val loading=mutableSetOf<String>();private val failed=mutableSetOf<String>()
    private val handler=Handler(Looper.getMainLooper());private var alive=true
    private var artworks=listOf<Artwork>();private var rects=listOf<RectF>()
    private var activeId="";private var pageWidth=1f;private var pageHeight=1f
    private var camera:Matrix?=null;private var viewWidth=0;private var viewHeight=0
    var editing=false;private set
    private var pending:String?=null
    val previewCount get()=images.size
    val artworkCount get()=artworks.size
    private fun invalidate(){activity.editor?.canvas?.invalidate();activity.vectorEditor?.canvas?.invalidate()}
    init{refresh()}
    fun refresh(){
        val w=activity.session?.canvasWidth?:activity.vectorDocument?.width?:80;val h=activity.session?.canvasHeight?:activity.vectorDocument?.height?:80
        artworks=activity.store.list().let{list->if(list.any{it.id==activity.documentId})list else list+Artwork(activity.documentId,activity.documentName,w,h,System.currentTimeMillis(),if(activity.vectorDocument!=null)"vector"else"raster")}.sortedWith(compareBy<Artwork>{it.created}.thenBy{it.id})
        layout.ensure(artworks);rects=artworks.mapIndexed{i,a->layout.rect(a,i)}
        if(activeId!=activity.documentId){if(activeId.isNotEmpty())images.remove(activeId)?.recycle();activeId=activity.documentId;pending=null}
        pageWidth=w.toFloat();pageHeight=h.toFloat()
    }
    private fun activeRect()=rects.getOrNull(artworks.indexOfFirst{it.id==activeId})?:RectF(0f,0f,260f,260f)
    private fun pageToWorld()=Matrix().apply{val r=activeRect();setScale(r.width()/pageWidth,r.height()/pageHeight);postTranslate(r.left,r.top)}
    fun capture(page:Matrix,w:Int,h:Int){val inverse=Matrix();pageToWorld().invert(inverse);camera=Matrix().apply{setConcat(page,inverse)};viewWidth=w;viewHeight=h}
    fun pageCamera(w:Int,h:Int):Matrix?{
        val world=camera?:return null
        if(viewWidth>0 && viewHeight>0 && (w!=viewWidth || h!=viewHeight))world.postTranslate((w-viewWidth)/2f,(h-viewHeight)/2f)
        viewWidth=w;viewHeight=h
        return Matrix().apply{setConcat(world,pageToWorld())}
    }
    fun enterOverview(){editing=false;invalidate()}
    fun activateCurrent(){pending=null;editing=true;invalidate()}
    fun switchFailed(){pending=null;invalidate()}
    fun draw(c:Canvas,page:Matrix,w:Int,h:Int){
        capture(page,w,h)
        val world=camera?:return;val inverse=Matrix();world.invert(inverse);val viewport=RectF(0f,0f,w.toFloat(),h.toFloat());inverse.mapRect(viewport)
        c.drawColor(StudioTheme.of(activity).workspace)
        val scale=FloatArray(9).also{world.getValues(it)}.let{hypot(it[0],it[3])}.coerceAtLeast(.001f)
        val save=c.save();c.concat(world);paint.style=Paint.Style.STROKE;paint.color=if(StudioTheme.of(activity).light)0x18000000 else 0x10ffffff;paint.strokeWidth=.65f*d/scale
        val step=max(16f,8*d/scale);var x=floor(viewport.left/step)*step;while(x<viewport.right){c.drawLine(x,viewport.top,x,viewport.bottom,paint);x+=step};var y=floor(viewport.top/step)*step;while(y<viewport.bottom){c.drawLine(viewport.left,y,viewport.right,y,paint);y+=step}
        val visible=artworks.indices.filter{RectF.intersects(viewport,rects[it])}.sortedBy{hypot(rects[it].centerX()-viewport.centerX(),rects[it].centerY()-viewport.centerY())}
        val nearby=visible.take(48).toSet()
        for(i in visible.sorted()){val art=artworks[i];val r=rects[i];paint.style=Paint.Style.FILL;paint.alpha=255
            if(art.id==activeId || pending==art.id || dragging==i){val frame=RectF(r).apply{inset(-r.width()*.12f,-r.height()*.12f)};paint.color=0x227f858d;c.drawRoundRect(frame,10f,10f,paint);paint.style=Paint.Style.STROKE;paint.color=0x447f858d;paint.strokeWidth=d/scale;c.drawRoundRect(frame,10f,10f,paint);paint.style=Paint.Style.FILL}
            if(art.id==activeId)continue
            paint.color=0xff414348.toInt();c.drawRect(r,paint)
            images[art.id]?.let{c.drawBitmap(it,null,r,paint)}?:run{if(i in nearby)requestPreview(art)}
        }
        c.restoreToCount(save)
        if(picking)pickedColor?.let{color->
            val x=pickLocation.x.coerceIn(30*d,max(30*d,w-30*d));val y=(pickLocation.y-54*d).coerceIn(30*d,max(30*d,h-30*d))
            paint.style=Paint.Style.FILL;paint.color=color;c.drawCircle(x,y,24*d,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=3*d;paint.color=Color.WHITE;c.drawCircle(x,y,24*d,paint);paint.style=Paint.Style.FILL
        }
    }
    private fun requestPreview(art:Artwork){
        if(!alive || art.id in loading || art.id in failed || loading.size>=3)return
        loading.add(art.id)
        activity.io.execute{val image=runCatching{activity.store.thumbnail(art.id)?.let{raw->val ratio=256f/max(raw.width,raw.height);if(ratio<1f){val small=Bitmap.createScaledBitmap(raw,max(1,(raw.width*ratio).toInt()),max(1,(raw.height*ratio).toInt()),true);raw.recycle();small}else raw}}.getOrNull()
            handler.post{loading.remove(art.id);if(!alive){image?.recycle();return@post};if(image==null)failed.add(art.id)else{images.put(art.id,image)?.recycle();while(images.size>48){val iterator=images.entries.iterator();val item=iterator.next();iterator.remove();item.value.recycle()}};invalidate()}}
    }
    private fun worldPoint(x:Float,y:Float):PointF{val inverse=Matrix();camera?.invert(inverse);val p=floatArrayOf(x,y);inverse.mapPoints(p);return PointF(p[0],p[1])}
    private fun hit(x:Float,y:Float):Int{val p=worldPoint(x,y);val active=artworks.indexOfFirst{it.id==activeId};if(active>=0 && rects[active].contains(p.x,p.y))return active;return rects.indexOfLast{it.contains(p.x,p.y)}}
    private var pickImage:Bitmap?=null;private var pickImageId="";private var pickLoading:String?=null;private var pickGeneration=0
    private var picking=false
    private var pickedColor:Int?=null
    private var pickLocation=PointF()
    private fun samplePreview(x:Float,y:Float){
        val index=hit(x,y);if(index<0 || artworks[index].id==activeId)return
        val id=artworks[index].id
        if(pickImageId!=id && pickLoading!=id){
            val generation=pickGeneration;pickLoading=id
            activity.io.execute{val image=runCatching{activity.store.thumbnail(id)}.getOrNull();handler.post{
                if(!alive || !picking || generation!=pickGeneration || hit(last.x,last.y).let{artworks.getOrNull(it)?.id}!=id){image?.recycle();if(pickLoading==id)pickLoading=null;return@post}
                pickImage?.recycle();pickImage=image;pickImageId=id;pickLoading=null;samplePreview(last.x,last.y)
            }}
        }
        val image=(if(pickImageId==id)pickImage else images[id])?:return
        val point=worldPoint(x,y);val r=rects[index]
        val color=image.getPixel(((point.x-r.left)/r.width()*image.width).toInt().coerceIn(0,image.width-1),((point.y-r.top)/r.height()*image.height).toInt().coerceIn(0,image.height-1))
        pickedColor=color;pickLocation=PointF(x,y)
        activity.session?.let{it.color=color;activity.editor?.refreshTools()}
        activity.vectorEditor?.let{it.acceptAtlasColor(color)}
        activity.savePreferences();invalidate()
    }
    private fun clearPick(){picking=false;pickedColor=null;pickGeneration++;pickLoading=null;pickImage?.recycle();pickImage=null;pickImageId=""}
    private fun eyedropperSelected()=activity.session?.tool=="Eyedropper" || activity.vectorEditor?.canvas?.tool=="Eyedropper"
    private var consuming=false;private var down=PointF();private var last=PointF();private var downHit=-1;private var moved=false;private var multi=false
    private var distance=0f;private var angle=0f;private var center=PointF();private var dragging=-1
    private var applyCamera:((Matrix)->Unit)?=null;private var touchedView:View?=null
    private val hold=Runnable{if(consuming && !multi && downHit>=0){
        if(editing){picking=true;samplePreview(last.x,last.y)}else dragging=downHit
        moved=true;touchedView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);invalidate()
    }}
    private var pinchStart=0f;private var pinchAt=0L
    private fun updateCamera(){pageCamera(viewWidth,viewHeight)?.let{applyCamera?.invoke(it)};invalidate()}
    fun touch(e:MotionEvent,view:View,page:Matrix,setCamera:(Matrix)->Unit):Boolean{
        if(activity.navigationBusy || pending!=null)return true
        if(e.actionMasked==MotionEvent.ACTION_DOWN){capture(page,view.width,view.height);downHit=hit(e.x,e.y);consuming=!editing || downHit<0 || artworks.getOrNull(downHit)?.id!=activeId
            if(!consuming)return false
            if(activity.editor?.dismissPanel()==true || activity.vectorEditor?.dismissPanel()==true)downHit=-1
            down=PointF(e.x,e.y);last=PointF(e.x,e.y);moved=false;multi=false;dragging=-1;picking=false;pickedColor=null;applyCamera=setCamera;touchedView=view;if(eyedropperSelected() && downHit>=0){picking=true;moved=true;samplePreview(e.x,e.y)}else handler.postDelayed(hold,420)
        }
        if(!consuming)return false
        when(e.actionMasked){
            MotionEvent.ACTION_POINTER_DOWN->{clearPick();handler.removeCallbacks(hold);if(dragging>=0){rects[dragging].set(layout.rect(artworks[dragging],dragging));dragging=-1};multi=true;moved=true;distance=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));pinchStart=distance;pinchAt=android.os.SystemClock.uptimeMillis();angle=atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0));center=PointF((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2)}
            MotionEvent.ACTION_MOVE->{
                val world=camera?:return true
                if(picking){samplePreview(e.x,e.y);last=PointF(e.x,e.y);return true}
                if(multi){if(e.pointerCount>=2){val mid=PointF((e.getX(0)+e.getX(1))/2,(e.getY(0)+e.getY(1))/2);val len=hypot(e.getX(1)-e.getX(0),e.getY(1)-e.getY(0));val nextAngle=atan2(e.getY(1)-e.getY(0),e.getX(1)-e.getX(0));var delta=nextAngle-angle;while(delta>Math.PI)delta-=(2*Math.PI).toFloat();while(delta< -Math.PI)delta+=(2*Math.PI).toFloat()
                    val current=FloatArray(9).also{world.getValues(it)}.let{hypot(it[0],it[3])};val ratio=(if(distance>0)len/distance else 1f).coerceIn(.04f/current,30f/current)
                    world.postScale(ratio,ratio,center.x,center.y);world.postRotate(delta*180f/Math.PI.toFloat(),center.x,center.y);world.postTranslate(mid.x-center.x,mid.y-center.y);center=mid;distance=len;angle=nextAngle;updateCamera()};return true}
                if(hypot(e.x-down.x,e.y-down.y)>7*d){moved=true;handler.removeCallbacks(hold)}
                if(dragging>=0){val before=worldPoint(last.x,last.y);val now=worldPoint(e.x,e.y);rects[dragging].offset(now.x-before.x,now.y-before.y);updateCamera()}
                else if(moved){world.postTranslate(e.x-last.x,e.y-last.y);updateCamera()}
                last=PointF(e.x,e.y)
            }
            MotionEvent.ACTION_POINTER_UP->{
                if(multi && e.pointerCount==2 && pinchStart>0 && distance/pinchStart<.78f && android.os.SystemClock.uptimeMillis()-pinchAt in 60..450){
                    activateCurrent();activity.editor?.canvas?.animateFit();consuming=false;multi=false
                }
            }
            MotionEvent.ACTION_UP->{handler.removeCallbacks(hold)
                if(dragging>=0){val r=rects[dragging];layout.move(artworks[dragging].id,r.left,r.top);dragging=-1}
                else if(!moved && !multi && downHit>=0 && hit(e.x,e.y)==downHit){val art=artworks[downHit];if(art.id==activeId)activateCurrent()else{pending=art.id;activity.activateAtlasArtwork(art)}}
                clearPick();consuming=false;multi=false;view.performClick();invalidate()
            }
            MotionEvent.ACTION_CANCEL->{handler.removeCallbacks(hold);if(dragging>=0){rects[dragging].set(layout.rect(artworks[dragging],dragging));dragging=-1;updateCamera()};clearPick();consuming=false;multi=false}
        };return true
    }
    fun close(){alive=false;clearPick();handler.removeCallbacks(hold);images.values.forEach{it.recycle()};images.clear();applyCamera=null;touchedView=null}
}
