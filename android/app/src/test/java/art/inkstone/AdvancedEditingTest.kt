package art.inkstone

import android.app.AlertDialog
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AdvancedEditingTest{
    private fun until(test:()->Boolean){val end=System.nanoTime()+25_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Advanced editing timed out")}
    private fun named(v:View,s:String):View?{if(v.contentDescription==s || v is TextView && v.text.toString()==s)return v;if(v is ViewGroup)for(i in 0 until v.childCount)named(v.getChildAt(i),s)?.let{return it};return null}
    private fun click(v:View,s:String){
        if(named(v,s)==null){
            val group=when(s){"Automatic","Freehand","Rectangle","Ellipse"->"Shape";"Replace","Add","Remove","Invert"->"Combine";"Copy & Paste","Copy","Cut","Paste"->"Clipboard";"Threshold","Feather"->"Refine";"Save selection","Load selection","Delete saved"->"Saved";"Color Fill","Clear","Deselect"->"Selection actions";"Freeform","Uniform","Distort","Warp"->"Mode";"Smaller","Larger","Flip H","Flip V","Rotate 45°","Fit to Canvas"->"Adjust";"Snapping","Bilinear","Nearest"->"Options";else->null}
            if(group!=null){assertTrue(checkNotNull(named(v,group)){"Missing group $group"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
        }
        assertTrue(checkNotNull(named(v,s)){"Missing $s"}.performClick());shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(v:View,name:String){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));v.rootView.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.rootView.layout(0,0,1280,800);v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,800);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));val b=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getProperty("inkstone.artifacts"),"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun command(s:DrawingSession,op:String,values:JSONObject=JSONObject()){var done=false;s.command(op,values){assertFalse(it.toString(),it.has("error"));done=true};until{done}}
    private fun pick(s:DrawingSession,x:Int,y:Int):Int{var result:JSONObject?=null;s.command("pick",JSONObject().put("x",x).put("y",y)){result=it};until{result!=null};return result!!.getLong("color").toInt()}
    private fun bytes(s:DrawingSession):ByteArray{var result:ByteArray?=null;s.documentSnapshot{result=it};until{result!=null};return result!!}
    private fun point(e:EditorView,x:Float,y:Float)=e.canvas.transformViewPoint(x,y).apply{offset(e.canvas.left.toFloat(),e.canvas.top.toFloat())}
    private fun touch(e:EditorView,action:Int,p:PointF){val time=android.os.SystemClock.uptimeMillis();val event=MotionEvent.obtain(time,time,action,p.x,p.y,0);e.dispatchTouchEvent(event);event.recycle()}
    private fun drag(e:EditorView,from:PointF,to:PointF){touch(e,MotionEvent.ACTION_DOWN,from);touch(e,MotionEvent.ACTION_MOVE,to);touch(e,MotionEvent.ACTION_UP,to)}
    private fun rect(s:DrawingSession,x0:Int,y0:Int,x1:Int,y1:Int,mode:String="Replace")=command(s,"select",JSONObject().put("shape","Rectangle").put("mode",mode).put("points",JSONArray().put(JSONArray().put(x0).put(y0)).put(JSONArray().put(x1).put(y1))))
    private fun seed(s:DrawingSession){rect(s,60,60,180,140);command(s,"selectionFill",JSONObject().put("color",0xff228855L));rect(s,120,60,180,140);command(s,"selectionFill",JSONObject().put("color",0xffdd6633L));command(s,"deselect")}
    private fun done(c:org.robolectric.android.controller.ActivityController<MainActivity>){val s=c.get().session;c.pause().stop().destroy();until{s==null || s.fullyClosed};until{c.get().io.isTerminated}}
    @Test fun shelfShowsSixGroupsExpandsOneAtATimeAndScrollsOnPortrait(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val e=c.get().editor!!;layout(e,"compact-toolbar-before");click(e,"Select");layout(e,"compact-selection-collapsed")
        val shelf=e.findViewWithTag<View>("canvas-tool-shelf");val main=e.findViewWithTag<LinearLayout>("canvas-tool-main-row");assertEquals(6,main.childCount);assertTrue(shelf.width<=536);assertNull(named(e,"Rectangle"))
        click(e,"Shape");layout(e,"compact-selection-expanded");assertNotNull(named(e,"Rectangle"));assertEquals(4,e.findViewWithTag<LinearLayout>("canvas-tool-expanded-row").childCount)
        click(e,"Clipboard");assertNull(named(e,"Rectangle"));assertNotNull(named(e,"Copy & Paste"));click(e,"Clipboard");assertEquals(View.GONE,e.findViewWithTag<View>("canvas-tool-options-scroll").visibility)
        org.robolectric.RuntimeEnvironment.setQualifiers("w400dp-h820dp-mdpi")
        e.rootView.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(820,View.MeasureSpec.EXACTLY));e.rootView.layout(0,0,400,820);e.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(820,View.MeasureSpec.EXACTLY));e.layout(0,0,400,820);val scroll=e.findViewWithTag<HorizontalScrollView>("canvas-tool-groups-scroll");assertTrue(scroll.getChildAt(0).width>scroll.width);scroll.scrollTo(80,0);assertTrue(scroll.scrollX>0);assertTrue(shelf.width<=320);done(c)
    }
    @Test fun rectangleAddRemoveFeatherSavedSelectionAndCopyPasteWorkThroughEditor(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val s=e.session;layout(e,"advanced-selection-before");click(e,"Select");click(e,"Rectangle")
        drag(e,point(e,40f,40f),point(e,120f,120f));command(s,"info");click(e,"Add");drag(e,point(e,100f,100f),point(e,180f,160f));command(s,"info");click(e,"Remove");drag(e,point(e,60f,60f),point(e,80f,80f));command(s,"info")
        s.color=Color.RED;click(e,"Color Fill");command(s,"info");assertEquals(Color.RED,pick(s,150,130));assertEquals(0,Color.alpha(pick(s,70,70)));assertEquals(0,Color.alpha(pick(s,200,100)))
        command(s,"selectionFeather",JSONObject().put("radius",5));click(e,"Save selection")
        val dialog=ShadowAlertDialog.getLatestAlertDialog();fun edit(v:View):EditText?{if(v is EditText)return v;if(v is ViewGroup)for(i in 0 until v.childCount)edit(v.getChildAt(i))?.let{return it};return null};edit(dialog.window!!.decorView)!!.setText("Subject");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();until{s.info.optJSONArray("savedSelections")?.length()==1}
        click(e,"Deselect");command(s,"info");assertFalse(s.info.getBoolean("selection"));click(e,"Load selection");ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0);command(s,"info");assertTrue(s.info.getBoolean("selection"))
        layout(e,"advanced-feathered-selection");click(e,"Copy & Paste");until{s.info.getJSONArray("layers").length()==2 && s.tool=="Transform"};until{e.canvas.rasterTransform.ready};assertNotNull(named(e,"Mode"));assertTrue(Color.alpha(pick(s,150,130))>0);assertEquals(0,Color.alpha(pick(s,200,100)));
        a.newCanvas(160,120,visible=false);val other=a.editor!!;layout(other,"advanced-paste-new-document");click(other,"Select");click(other,"Paste");until{other.session.info.getJSONArray("layers").length()==2};assertTrue(Color.alpha(pick(other.session,80,60))>0);done(c)
    }
    @Test fun automaticSelectionAndClearOnlyAffectMatchingConnectedRegion(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val s=e.session;seed(s);layout(e,"advanced-auto-before");click(e,"Select");click(e,"Automatic");drag(e,point(e,80f,90f),point(e,80f,90f));command(s,"info")
        assertTrue(s.info.getBoolean("selection"));click(e,"Clear");command(s,"info");assertEquals(0,Color.alpha(pick(s,80,90)));assertEquals(0xffdd6633.toInt(),pick(s,150,90));command(s,"undo");assertEquals(0xff228855.toInt(),pick(s,80,90));done(c)
    }
    @Test fun freeformUniformDistortResetAndFitHaveLiveHandlesAndOneCommit(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val s=e.session;seed(s);layout(e,"advanced-transform-before");click(e,"Transform");until{e.canvas.rasterTransform.ready};val t=e.canvas.rasterTransform
        click(e,"Freeform");drag(e,point(e,180f,140f),point(e,220f,145f));val free=t.bounds!!;assertTrue(free.width()/120f>free.height()/80f+.15f)
        click(e,"Reset");click(e,"Uniform");drag(e,point(e,180f,140f),point(e,220f,145f));val uniform=t.bounds!!;assertEquals(uniform.width()/120f,uniform.height()/80f,.01f)
        click(e,"Reset");click(e,"Snapping");drag(e,point(e,120f,100f),point(e,159f,100f));assertEquals(160f,t.bounds!!.centerX(),.1f);click(e,"Reset");click(e,"Bilinear");ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null,0,0);assertEquals("Nearest",t.interpolation);click(e,"Distort");drag(e,point(e,180f,60f),point(e,160f,78f));val values=FloatArray(9);t.currentMatrix().getValues(values);assertTrue(kotlin.math.abs(values[6])+kotlin.math.abs(values[7])>.00001f);layout(e,"advanced-distort-live")
        val undo=s.info.getInt("undo");click(e,"Done");until{s.info.getInt("undo")==undo+1 && !t.displaying};assertTrue(Color.alpha(pick(s,100,100))>0);command(s,"undo")
        click(e,"Transform");until{t.ready};click(e,"Fit to Canvas");assertTrue(t.bounds!!.width()>=319f || t.bounds!!.height()>=239f);click(e,"Cancel");done(c)
    }
    @Test fun warpGridCommitCancelAndUndoPreserveSourceArtwork(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val s=e.session;seed(s);val original=bytes(s)
        layout(e,"advanced-warp-before");click(e,"Transform");until{e.canvas.rasterTransform.ready};click(e,"Warp");drag(e,point(e,100f,86.6667f),point(e,116f,86.6667f));layout(e,"advanced-warp-live");val preview=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888);assertTrue(e.canvas.rasterTransform.draw(Canvas(preview)));val previewColor=preview.getPixel(126,100);preview.recycle();val undo=s.info.getInt("undo");click(e,"Done");until{s.info.getInt("undo")==undo+1 && !e.canvas.rasterTransform.displaying};layout(e,"advanced-warp-committed");assertEquals("Warp preview must match the saved geometry",previewColor,pick(s,126,100));val warped=bytes(s);assertFalse(original.contentEquals(warped));command(s,"undo");assertArrayEquals(original,bytes(s));command(s,"redo");assertArrayEquals(warped,bytes(s))
        click(e,"Transform");until{e.canvas.rasterTransform.ready};click(e,"Warp");drag(e,point(e,100f,86.6667f),point(e,105f,89f));click(e,"Cancel");assertArrayEquals(warped,bytes(s));done(c)
    }
    @Test fun smoothWarpHasContinuousTangentsAndInterpolatesEveryHandle(){
        val controls=(0..3).flatMap{y->(0..3).map{x->PointF(x*100f,y*100f)}}
        controls[6].offset(35f,0f)
        for(y in 0..3)for(x in 0..3){val p=SmoothWarp.point(controls,x.toFloat(),y.toFloat());assertEquals(controls[y*4+x].x,p.x,.001f);assertEquals(controls[y*4+x].y,p.y,.001f)}
        for(v in listOf(1f,2f)){val a=SmoothWarp.point(controls,2f,v-.001f);val b=SmoothWarp.point(controls,2f,v);val c=SmoothWarp.point(controls,2f,v+.001f);assertEquals((b.x-a.x)/.001f,(c.x-b.x)/.001f,.5f)}
        assertTrue(SmoothWarp.point(controls,2f,1.5f).x>217f)
    }
    @Test fun recentBrushesSurviveReopeningAndCategoryScrollKeepsItsView(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val e=a.editor!!;val s=e.session
        a.getSharedPreferences("recent-brushes",0).edit().clear().commit()
        val chosen=brushPresets.take(4);chosen.forEach{s.choose(it)}
        assertEquals(chosen.reversed().map{it.name},RecentBrushes(a).list("Paint").map{it.name})
        s.tool="Erase";s.choose(chosen[0]);assertEquals(1,RecentBrushes(a).list("Erase").size);assertEquals(4,RecentBrushes(a).list("Paint").size);s.tool="Paint"
        layout(e,"brush-scroll-before");click(e,"Paint");layout(e,"brush-scroll-open")
        val scroll=e.findViewWithTag<ScrollView>("brush-categories-scroll");scroll.scrollTo(0,160);val before=scroll.scrollY
        click(e,"Painting");layout(e,"brush-scroll-after");assertSame(scroll,e.findViewWithTag<ScrollView>("brush-categories-scroll"));assertEquals(before,scroll.scrollY)
        click(e,"Recent");val count=e.findViewWithTag<ScrollView>("brush-presets-scroll").getChildAt(0) as LinearLayout;assertEquals(4,count.childCount)
        done(c)
    }
    @Test fun vectorGroupsPreserveMemberFlagsAcrossSaveUndoAndMovement(){
        val doc=VectorDocument(320,240);val first=doc.layers[0]
        first.objects.add(VectorObject(doc.nextId(),"Line",mutableListOf(PointF(20f,20f),PointF(100f,80f)),Color.RED,null,4f))
        doc.edit{doc.createGroup("Sketch")};val id=doc.groups[0].id
        val second=VectorLayer(doc.nextId(),"Ink").apply{groupId=id;visible=false};doc.edit{doc.layers.add(second)}
        doc.edit{doc.groups[0].visible=false;doc.groups[0].locked=true}
        val restored=VectorDocument.decode(doc.encode());assertFalse(restored.layers[0].effectiveVisible);assertTrue(restored.layers[0].effectiveLocked);assertTrue(restored.layers[0].visible);assertFalse(restored.layers[1].visible)
        doc.undo();assertTrue(doc.layers[0].effectiveVisible);assertFalse(doc.layers[1].effectiveVisible)
        val before=doc.layers[0].objects[0].bounds();doc.edit{doc.translateGroup(doc.groups[0],10,15)};val after=doc.layers[0].objects[0].bounds();assertEquals(before.left+10,after.left,.01f);assertEquals(before.top+15,after.top,.01f)
        doc.undo();assertEquals(before.left,doc.layers[0].objects[0].bounds().left,.01f)
    }

    @Test fun timedInputReachesNativeFadeAcrossMergedMotionBatches(){
        fun draw(dt:Float):Int{
            val handle=NativeBridge.create(800,80)
            try{
                NativeBridge.command(handle,JSONObject().put("op","background").put("visible",false).toString())
                NativeBridge.stroke(handle,0,floatArrayOf(20f,40f,1f,0f,0f),0,Color.RED,28f,1f,1f,0f,0f,0f,150f,5)
                for(batch in 0..4){val samples=FloatArray(30*5){at->val sample=batch*30+at/5+1;when(at%5){0->20f+sample*5;1->40f;2->1f;3->0f;else->sample*dt}};NativeBridge.stroke(handle,1,samples,0,Color.RED,28f,1f,1f,0f,0f,0f,150f,5)}
                NativeBridge.stroke(handle,2,floatArrayOf(),0,Color.RED,28f,1f,1f,0f,0f,0f,150f,5)
                return Color.alpha(JSONObject(NativeBridge.command(handle,JSONObject().put("op","pick").put("x",300).put("y",40).toString())).getLong("color").toInt())
            }finally{NativeBridge.destroy(handle)}
        }
        assertTrue(draw(35f)>draw(2f)+40)
    }

    @Test fun rasterGroupHeaderCollapsesAndRetainsSavedMembers(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120,visible=false);val e=a.editor!!;val s=e.session
        command(s,"group",JSONObject().put("action","create").put("name","Sketch"));command(s,"add")
        layout(e,"group-raster-before");click(e,"Layers");until{named(e,"Toggle group Sketch")!=null};layout(e,"group-raster-open")
        assertNotNull(named(e,"Layer row Layer 1"));click(e,"Collapse group Sketch");until{s.info.getJSONArray("groups").getJSONObject(0).getBoolean("collapsed")};until{named(e,"Layer row Layer 1")==null};layout(e,"group-raster-collapsed")
        click(e,"Collapse group Sketch");until{named(e,"Layer row Layer 1")!=null}
        val saved=bytes(s);val handle=NativeBridge.create(16,16);try{val info=JSONObject(NativeBridge.load(handle,saved));assertEquals("Sketch",info.getJSONArray("groups").getJSONObject(0).getString("name"));assertEquals(1L,info.getJSONArray("layers").getJSONObject(1).getLong("groupId"))}finally{NativeBridge.destroy(handle)}
        done(c)
    }

    @Test fun layerDragDropsIntoFolderAndBackOutsideWithOneUndoEach(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120,visible=false);val e=a.editor!!;val s=e.session
        command(s,"group",JSONObject().put("action","create").put("name","Sketch"));command(s,"add");command(s,"moveLayer",JSONObject().put("id",2).put("groupId",JSONObject.NULL))
        layout(e,"drag-layer-before");click(e,"Layers");until{named(e,"Toggle group Sketch")!=null};layout(e,"drag-layer-open")
        fun event(action:Int,y:Float):DragEvent{
            val method=DragEvent::class.java.declaredMethods.first{it.name=="obtain" && it.parameterTypes.size>1};method.isAccessible=true;var f=0
            val args=method.parameterTypes.map{type->when(type){Integer.TYPE->action;java.lang.Float.TYPE->if(f++==0)50f else y;java.lang.Boolean.TYPE->false;Any::class.java->LayerDrag.Token(s,2);else->null}}.toTypedArray()
            return method.invoke(null,*args) as DragEvent
        }
        fun drop(target:View,y:Float=target.height*.5f){for(action in listOf(DragEvent.ACTION_DRAG_STARTED,DragEvent.ACTION_DRAG_ENTERED,DragEvent.ACTION_DROP,DragEvent.ACTION_DRAG_ENDED)){val event=event(action,y);val accepted=target.dispatchDragEvent(event);if(action==DragEvent.ACTION_DRAG_STARTED || action==DragEvent.ACTION_DROP)assertTrue("Drag action $action",accepted)}}
        val before=s.info.getInt("undo");val heading=named(e,"Toggle group Sketch")!!.parent as View;drop(heading)
        until{s.info.getInt("undo")==before+1};assertEquals(1L,s.info.getJSONArray("layers").getJSONObject(1).getLong("groupId"));until{named(e,"Layer row Layer 1")!=null};layout(e,"drag-layer-in-group");assertNull(named(e,"Outside groups"))
        val last=named(e,"Layer row Layer 1")!!;drop(last,last.height*.9f);until{s.info.getInt("undo")==before+2};assertTrue(s.info.getJSONArray("layers").getJSONObject(0).isNull("groupId"));command(s,"undo");assertEquals("Undo: Move layer",s.info.getString("historyNotice"));done(c)
    }
    @Test fun guidesRenderIsometricAndPerspectiveAndVanishingPointsDrag(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val guide=e.canvas.guides
        guide.enabled=true;guide.mode="Isometric";guide.spacing=50f;layout(e,"isometric-guide")
        guide.mode="2-point Perspective";guide.editing=true;e.session.tool="Guide";e.refreshTools();layout(e,"perspective-guide-before")
        val start=guide.points[0].let{point(e,it.x*320,it.y*240)};drag(e,start,point(e,100f,80f));assertEquals(100f/320,guide.points[0].x,.01f);assertEquals(guide.points[0].y,guide.points[1].y,.001f)
        layout(e,"perspective-guide-after");click(e,"Done");assertFalse(guide.editing);assertEquals("2-point Perspective",DrawingGuide(a).mode)
        guide.enabled=false;guide.save();done(c)
    }
    @Test fun liquifyEditorPushCancelDoneAndNamedUndoUseRealEngine(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(320,240,visible=false);val e=a.editor!!;val s=e.session;seed(s);val original=bytes(s);layout(e,"liquify-before")
        fun open(){click(e,"Adjustments");click(e,"Liquify");command(s,"info");assertEquals("Liquify",s.tool);layout(e,"liquify-open")}
        val before=s.info.getInt("undo");open();drag(e,point(e,120f,100f),point(e,165f,110f));command(s,"info");assertEquals(before,s.info.getInt("undo"));layout(e,"liquify-push-live");click(e,"Cancel");command(s,"info");assertArrayEquals(original,bytes(s))
        open();drag(e,point(e,120f,100f),point(e,165f,110f));command(s,"info");click(e,"Done");until{s.info.getInt("undo")==before+1};assertFalse(original.contentEquals(bytes(s)))
        click(e,"Undo");until{s.info.getInt("undo")==before};assertEquals("Undo: Liquify",s.info.getString("historyNotice"));assertArrayEquals(original,bytes(s));done(c)
    }
    @Test fun vectorHistoryNamesShapeCreationAndLayerDragMembership(){
        val doc=VectorDocument(320,240);doc.edit{doc.layers[0].objects.add(VectorObject(doc.nextId(),"Rectangle",mutableListOf(PointF(10f,10f),PointF(100f,80f)),Color.RED,null,4f))}
        doc.undo();assertEquals("Undo: Draw shape",doc.historyNotice);doc.redo();assertEquals("Redo: Draw shape",doc.historyNotice)
        doc.edit{doc.createGroup("Sketch")};val group=doc.groups[0].id;val id=doc.nextId();doc.edit{doc.layers.add(VectorLayer(id,"Ink"));doc.active=1}
        doc.edit{doc.dragLayer(id,null,group,true)};assertEquals(group,doc.layers[1].groupId);doc.edit{doc.dragLayer(id,null,null,false)};assertNull(doc.layers[0].groupId);doc.undo();assertEquals(group,doc.layers[1].groupId);assertEquals("Undo: Move layer",doc.historyNotice)
    }

    @Test fun layerDropGlowUsesInsertionEdgesAndFolderBoxAndRowsAnimate(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();val owner=Any();val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;tag="layer-list"};val first=View(a).apply{tag="layer-1"};val second=View(a).apply{tag="layer-2"}
        body.addView(first,LinearLayout.LayoutParams(300,80));body.addView(second,LinearLayout.LayoutParams(300,80));a.setContentView(body);layout(body,"layer-motion-before")
        LayerDrag.target(first,owner){_,_,_->};LayerDrag.target(second,owner,folder=true){_,_,_->}
        fun event(v:View,action:Int,y:Float){val method=DragEvent::class.java.declaredMethods.first{it.name=="obtain" && it.parameterTypes.size>1};method.isAccessible=true;var f=0;val args=method.parameterTypes.map{type->when(type){Integer.TYPE->action;java.lang.Float.TYPE->if(f++==0)50f else y;java.lang.Boolean.TYPE->false;Any::class.java->LayerDrag.Token(owner,1);else->null}}.toTypedArray();v.dispatchDragEvent(method.invoke(null,*args) as DragEvent)}
        event(first,DragEvent.ACTION_DRAG_STARTED,10f);event(first,DragEvent.ACTION_DRAG_ENTERED,10f);val line=first.foreground as LayerDrag.DropGlow;assertFalse(line.join);assertTrue(line.above)
        event(first,DragEvent.ACTION_DRAG_LOCATION,70f);assertFalse(line.above);event(first,DragEvent.ACTION_DRAG_ENDED,70f);assertNull(first.foreground)
        event(second,DragEvent.ACTION_DRAG_STARTED,40f);event(second,DragEvent.ACTION_DRAG_ENTERED,40f);assertTrue((second.foreground as LayerDrag.DropGlow).join);event(second,DragEvent.ACTION_DRAG_ENDED,40f)
        val motion=LayerDrag.capture(body,owner);body.removeView(second);body.addView(second,0);body.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));body.layout(0,0,1280,800);motion.settle(body);body.viewTreeObserver.dispatchOnPreDraw();assertTrue(kotlin.math.abs(first.translationY)>1f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));assertEquals(0f,first.translationY,.01f)
        val session=a.session;c.pause().stop().destroy();until{session==null || session.fullyClosed};until{a.io.isTerminated}
    }

    @Test fun groupedLayerRowsIndentInRasterAndVectorAndReturnToRoot(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120,visible=false);val e=a.editor!!;val s=e.session
        command(s,"group",JSONObject().put("action","create"));command(s,"add");command(s,"moveLayer",JSONObject().put("id",2).put("groupId",JSONObject.NULL));layout(e,"indent-raster-before");click(e,"Layers");until{named(e,"Layer row Layer 1")!=null};layout(e,"indent-raster-grouped")
        val grouped=named(e,"Layer row Layer 1")!!;val root=named(e,"Layer row Layer 2")!!;assertEquals(20f,((grouped.layoutParams as LinearLayout.LayoutParams).leftMargin-(root.layoutParams as LinearLayout.LayoutParams).leftMargin).toFloat(),0f)
        command(s,"moveLayer",JSONObject().put("id",1).put("targetId",1).put("groupId",JSONObject.NULL));click(e,"Layers");click(e,"Layers");until{named(e,"Layer row Layer 1")!=null};layout(e,"indent-raster-root");assertEquals(2,(named(e,"Layer row Layer 1")!!.layoutParams as LinearLayout.LayoutParams).leftMargin)
        a.newCanvas(160,120,mode="vector");val v=a.vectorEditor!!;val doc=v.document;doc.edit{doc.createGroup("Sketch");doc.layers.add(VectorLayer(doc.nextId(),"Outside"))};v.refresh();layout(v,"indent-vector-before");click(v,"Layers");layout(v,"indent-vector-grouped")
        val member=v.findViewWithTag<View>("layer-1");assertEquals(20,(member.layoutParams as LinearLayout.LayoutParams).leftMargin);val other=doc.layers.first{it.name=="Outside"};assertEquals(0,(v.findViewWithTag<View>("layer-${other.id}").layoutParams as LinearLayout.LayoutParams).leftMargin)
        done(c)
    }

    @Test fun draggedRowSettlesFromReleaseCoordinatesInBothDirections(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val owner=Any()
        val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;tag="layer-list"};val first=View(a).apply{tag="layer-1"};val second=View(a).apply{tag="layer-2"}
        body.addView(first,LinearLayout.LayoutParams(300,80));body.addView(second,LinearLayout.LayoutParams(300,80));a.setContentView(body);layout(body,"release14-before")
        LayerDrag.target(second,owner){_,_,_->}
        fun event(action:Int,y:Float){val method=DragEvent::class.java.declaredMethods.first{it.name=="obtain" && it.parameterTypes.size>1};method.isAccessible=true;var f=0;val args=method.parameterTypes.map{type->when(type){Integer.TYPE->action;java.lang.Float.TYPE->if(f++==0)220f else y;java.lang.Boolean.TYPE->false;Any::class.java->LayerDrag.Token(owner,1,80,300);else->null}}.toTypedArray();second.dispatchDragEvent(method.invoke(null,*args) as DragEvent)}
        for(y in listOf(70f,5f)){
            val at=IntArray(2);second.getLocationOnScreen(at);val releaseX=at[0]+220f-150f;val releaseY=at[1]+y-40f
            event(DragEvent.ACTION_DRAG_STARTED,y);event(DragEvent.ACTION_DROP,y)
            val motion=LayerDrag.capture(body,owner);body.removeView(first);body.addView(first,if(y>40)1 else 0);body.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));body.layout(0,0,1280,800);motion.settle(body);body.viewTreeObserver.dispatchOnPreDraw()
            first.getLocationOnScreen(at);assertEquals(releaseX,at[0].toFloat(),1f);assertEquals(releaseY,at[1].toFloat(),1f)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));assertEquals(0f,first.translationY,.01f);assertEquals(0f,first.translationX,.01f)
            event(DragEvent.ACTION_DRAG_ENDED,y)
        }
        val session=a.session;controller.pause().stop().destroy();until{session==null || session.fullyClosed};until{a.io.isTerminated}
    }


    @Test fun groupHeadersSelectDragNestAndIndentChildren(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val e=c.get().editor!!;val s=e.session
        command(s,"group",JSONObject().put("action","create").put("name","Inner"))
        command(s,"group",JSONObject().put("action","create").put("name","Outer").put("empty",true))
        layout(e,"nested-before");click(e,"Layers");until{named(e,"Group row Outer")!=null};layout(e,"nested-open")
        (named(e,"Toggle group Inner")!!.parent as View).performClick();layout(e,"nested-selected");assertNotNull(named(e,"Layer row Layer 1"))
        val target=named(e,"Group row Outer")!!
        fun drop(action:Int){
            val method=DragEvent::class.java.declaredMethods.first{it.name=="obtain" && it.parameterTypes.size>1};method.isAccessible=true;var f=0
            val args=method.parameterTypes.map{type->when(type){Integer.TYPE->action;java.lang.Float.TYPE->if(f++==0)50f else target.height*.5f;java.lang.Boolean.TYPE->false;Any::class.java->LayerDrag.Token(s,-1);else->null}}.toTypedArray()
            target.dispatchDragEvent(method.invoke(null,*args) as DragEvent)
        }
        drop(DragEvent.ACTION_DRAG_STARTED);drop(DragEvent.ACTION_DRAG_ENTERED);drop(DragEvent.ACTION_DROP);drop(DragEvent.ACTION_DRAG_ENDED)
        until{s.info.getJSONArray("groups").getJSONObject(0).optLong("parent_id",-1)==2L};until{(named(e,"Group row Inner")?.layoutParams as? LinearLayout.LayoutParams)?.leftMargin==20};layout(e,"nested-after")
        assertEquals(20,(named(e,"Group row Inner")!!.layoutParams as LinearLayout.LayoutParams).leftMargin)
        assertEquals(42,(named(e,"Layer row Layer 1")!!.layoutParams as LinearLayout.LayoutParams).leftMargin)
        click(e,"Collapse group Outer");until{named(e,"Group row Inner")==null};assertNull(named(e,"Layer row Layer 1"))
        click(e,"Collapse group Outer");until{named(e,"Layer row Layer 1")!=null}
        command(s,"undo");command(s,"undo");command(s,"undo");assertTrue(s.info.getJSONArray("groups").getJSONObject(0).isNull("parent_id"));done(c)
    }

    @Test fun emptyFolderCanMoveAndBottomDropCanStayInsideGroup(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120);val e=a.editor!!;val s=e.session
        command(s,"group",JSONObject().put("action","create").put("name","Sketch"));command(s,"add");command(s,"moveLayer",JSONObject().put("id",2).put("groupId",JSONObject.NULL).put("targetId",1).put("above",true))
        command(s,"group",JSONObject().put("action","create").put("name","Empty").put("empty",true))
        command(s,"group",JSONObject().put("action","move").put("id",2).put("targetId",1).put("above",false))
        layout(e,"gaps-before");click(e,"Layers");until{named(e,"Group row Empty")!=null};layout(e,"gaps-empty-folder")
        assertTrue(named(e,"Group row Empty")!!.top>named(e,"Layer row Layer 1")!!.top)
        val target=named(e,"Layer row Layer 1")!!.let{if(it is ViewGroup)it.getChildAt(it.childCount-1)else it}
        val before=s.info.getInt("undo")
        for(action in listOf(DragEvent.ACTION_DRAG_STARTED,DragEvent.ACTION_DRAG_ENTERED,DragEvent.ACTION_DROP,DragEvent.ACTION_DRAG_ENDED)){
            val method=DragEvent::class.java.declaredMethods.first{it.name=="obtain" && it.parameterTypes.size>1};method.isAccessible=true;var f=0
            val args=method.parameterTypes.map{type->when(type){Integer.TYPE->action;java.lang.Float.TYPE->if(f++==0)160f else target.height*.95f;java.lang.Boolean.TYPE->false;Any::class.java->LayerDrag.Token(s,2);else->null}}.toTypedArray();target.dispatchDragEvent(method.invoke(null,*args) as DragEvent)
        }
        until{s.info.getInt("undo")==before+1};assertEquals(1L,s.info.getJSONArray("layers").getJSONObject(0).getLong("groupId"));assertEquals(2L,s.info.getJSONArray("layers").getJSONObject(0).getLong("id"));done(c)
    }
    @Test fun exteriorBinaryFramesAndTransformPreviewIncludeNegativeCoordinates(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120);val e=a.editor!!;val s=e.session
        s.stroke(0,floatArrayOf(-30f,40f,1f,0f));s.stroke(1,floatArrayOf(30f,40f,1f,0f));s.stroke(2,floatArrayOf());until{s.exteriorTiles.isNotEmpty()};assertEquals(-128f,s.exteriorTiles.first().x,0f)
        var snap:TransformSnapshot?=null;s.transformSnapshot{snap=it};until{snap!=null};assertTrue(snap!!.bounds.left<0);snap!!.recycle()
        layout(e,"exterior-transform-before");click(e,"Transform");until{e.canvas.rasterTransform.ready};assertTrue(e.canvas.rasterTransform.bounds!!.left<0);layout(e,"exterior-transform-preview")
        e.canvas.rasterTransform.dispose();command(s,"transformProjective",JSONObject().put("matrix",JSONArray(listOf(1,0,60,0,1,0,0,0,1))).put("filter","Nearest"));until{s.exteriorTiles.isEmpty()};done(c)
    }

    @Test fun customGroupFormCreatesMultipleRootGroupsAndNestedIndentation(){
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();a.newCanvas(160,120);val e=a.editor!!;val s=e.session
        layout(e,"group-form-before");click(e,"Layers");until{named(e,"Create layer group")!=null}
        fun create(name:String,parent:String?=null,count:Int){
            click(e,"Create layer group");until{named(e,"Group name") is EditText}
            assertFalse(ShadowAlertDialog.getLatestAlertDialog()?.isShowing==true)
            (named(e,"Group name") as EditText).setText(name)
            if(parent!=null){click(e,"Location");click(e,"Inside $parent");until{named(e,"Group name") is EditText};assertEquals(name,(named(e,"Group name") as EditText).text.toString())}
            if(name=="First" || parent!=null)layout(e,"group-form-editing")
            if(name=="Third")(named(e,"Group name") as EditText).onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)else click(e,"Create group")
            until{s.info.getJSONArray("groups").length()==count};until{named(e,"Group row $name")!=null}
        }
        create("First",count=1);create("Second",count=2);create("Third",count=3)
        assertTrue((0 until 3).all{s.info.getJSONArray("groups").getJSONObject(it).isNull("parent_id")});assertTrue(s.info.getJSONArray("layers").getJSONObject(0).isNull("groupId"))
        click(e,"Collapse group First");until{s.info.getJSONArray("groups").getJSONObject(0).getBoolean("collapsed")}
        create("Child","First",4);create("Grandchild","Child",5);layout(e,"group-form-nested-result")
        assertEquals(20,(named(e,"Group row Child")!!.layoutParams as LinearLayout.LayoutParams).leftMargin)
        assertEquals(40,(named(e,"Group row Grandchild")!!.layoutParams as LinearLayout.LayoutParams).leftMargin)
        click(e,"Add");until{s.info.getJSONArray("layers").length()==2};until{named(e,"Layer row Layer 2")!=null};layout(e,"group-form-nested-layer")
        assertEquals(62,(named(e,"Layer row Layer 2")!!.layoutParams as LinearLayout.LayoutParams).leftMargin)
        click(e,"Create layer group");until{named(e,"Group name") is EditText};(named(e,"Group name") as EditText).setText("   ");click(e,"Create group");assertEquals(View.VISIBLE,named(e,"Group error")!!.visibility);assertEquals(5,s.info.getJSONArray("groups").length());click(e,"Cancel")
        done(c)
    }
}
