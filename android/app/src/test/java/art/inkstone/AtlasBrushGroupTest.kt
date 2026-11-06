package art.inkstone

import android.graphics.*
import android.os.Looper
import android.view.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AtlasBrushGroupTest {
    private fun until(test:()->Boolean){val end=System.nanoTime()+20_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(test())return;Thread.sleep(10)};error("Timed out")}
    private fun layout(v:View){v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,800);shadowOf(Looper.getMainLooper()).idle()}
    private fun render(v:View,name:String){layout(v);val b=Bitmap.createBitmap(1280,800,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File(System.getProperty("inkstone.artifacts"),"atlas-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    private fun seed(store:DocumentStore,name:String,color:Int):String{
        val doc=VectorDocument(120,100,background=color);val image=doc.bitmap(120);val id=store.newId();store.save(id,name,doc.encode(),image,120,100,"vector");image.recycle();return id
    }
    @Test fun customBrushLifecyclePreservesSettingsAndCategoriesAcrossReloads(){
        val context=RuntimeEnvironment.getApplication();val lib=BrushLibrary(context);lib.createCategory("Color studies")
        val p=lib.copy(brushPresets[0].copy(size=63f,flow=.32f,opacity=.44f,stabilization=.6f,hardness=.2f,colorMode="Palette",colorLayout="Bristles",colorCycle=450f,wetMix=.35f,palette=listOf(Color.RED,Color.BLUE)),"Twilight","Color studies")
        assertEquals(p,BrushLibrary(context).custom().single());assertTrue(p.name !in brushPresets.map{it.name})
        RecentBrushes(context).record("Paint",p);assertEquals(p,RecentBrushes(context).list("Paint").first())
        val renamed=lib.save(p.copy(name="Evening"));lib.renameCategory("Color studies","Studies")
        val moved=BrushLibrary(context).custom().single();assertEquals("Evening",moved.name);assertEquals("Studies",moved.group);assertEquals(450f,moved.colorCycle,0f);assertEquals("Bristles",moved.colorLayout)
        lib.deleteCategory("Studies");assertEquals("My Brushes",BrushLibrary(context).custom().single().group)
        lib.delete(renamed.customId);assertTrue(BrushLibrary(context).custom().isEmpty())
    }
    @Test fun groupOpacityCombinesVectorMembersOnceAndExportsAnSvgGroup(){
        val doc=VectorDocument(64,64,backgroundVisible=false)
        doc.layers[0].objects.add(VectorObject(doc.nextId(),"Rectangle",mutableListOf(PointF(4f,4f),PointF(50f,50f)),Color.RED,Color.RED,1f))
        doc.edit{doc.createGroup("Paint");doc.layers.add(VectorLayer(doc.nextId(),"Blue").apply{groupId=doc.groups[0].id;objects.add(VectorObject(doc.nextId(),"Rectangle",mutableListOf(PointF(4f,4f),PointF(50f,50f)),Color.BLUE,Color.BLUE,1f))})}
        doc.edit{doc.groups[0].opacity=.5f;doc.groups[0].blend="Normal"}
        val image=doc.bitmap();val color=image.getPixel(20,20);image.recycle();assertTrue(Color.alpha(color) in 126..129);assertEquals(255,Color.blue(color));assertEquals(0,Color.red(color))
        val loaded=VectorDocument.decode(doc.encode());assertEquals(.5f,loaded.groups[0].opacity,0f);assertTrue(loaded.svg().contains("id=\"group-"));assertTrue(loaded.svg().contains("isolation:isolate"))
        assertTrue(doc.undo());assertEquals(1f,doc.groups[0].opacity,0f);assertTrue(doc.redo());assertEquals(.5f,doc.groups[0].opacity,0f)
    }
    @Test fun atlasLayoutPersistsIndependentPositionsWithoutChangingArtwork(){
        val context=RuntimeEnvironment.getApplication();val art=Artwork("a","Sketch",400,200,1)
        AtlasLayout(context).ensure(listOf(art));val original=AtlasLayout(context).rect(art,0);assertEquals(2f,original.width()/original.height(),.001f)
        AtlasLayout(context).move(art.id,-280f,650f);val loaded=AtlasLayout(context).rect(art,0);assertEquals(-280f,loaded.left,0f);assertEquals(650f,loaded.top,0f);assertEquals(original.width(),loaded.width(),0f)
        val other=art.copy(id="b",name="New study");AtlasLayout(context).ensure(listOf(other,art));assertEquals(loaded,AtlasLayout(context).rect(art,1));assertFalse(RectF.intersects(loaded,AtlasLayout(context).rect(other,0)))
    }
    @Test fun atlasOnlyLoadsBoundedPreviewsAndReturnsToTheSameEditor(){
        val store=DocumentStore(RuntimeEnvironment.getApplication());repeat(20){seed(store,"Study ${it+1}",Color.HSVToColor(floatArrayOf(it*18f,.4f,.9f)))}
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(80,80);val s=a.session!!
        until{s.frameSerial>0};layout(a.editor!!);a.showAtlas();until{a.atlas!=null};val atlas=a.atlas!!
        assertEquals(21,atlas.artworkCount);render(a.editor!!,"overview");until{atlas.previewCount>0};repeat(8){render(a.editor!!,"overview");shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))};assertTrue(atlas.previewCount<=48)
        val before=s.info.optInt("undo");val board=a.editor!!.canvas;val now=android.os.SystemClock.uptimeMillis()
        for((action,time) in listOf(MotionEvent.ACTION_DOWN to now,MotionEvent.ACTION_MOVE to now+100,MotionEvent.ACTION_UP to now+120)){
            val e=MotionEvent.obtain(now,time,action,40f+if(action==MotionEvent.ACTION_DOWN)0f else 100f,50f,0);board.dispatchTouchEvent(e);e.recycle()
        }
        assertEquals(before,s.info.optInt("undo"));a.closeAtlas();assertNull(a.atlas);assertSame(s,a.session);assertNotNull(a.editor)
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun savedCustomBrushAndColorVariationRestoreInANewSession(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(80,80)
        val p=BrushLibrary(a).copy(brushPresets[0].copy(colorMode="Rainbow",wetMix=.25f,stabilization=.4f),"Aurora")
        a.session!!.choose(p);a.session!!.currentBrush.palette=listOf(Color.RED,Color.GREEN);a.savePreferences();a.newCanvas(80,80)
        assertEquals(p.customId,a.session!!.preset.customId);assertEquals("Rainbow",a.session!!.currentBrush.colorMode);assertEquals(.25f,a.session!!.currentBrush.wetMix,0f);assertEquals(listOf(Color.RED,Color.GREEN),a.session!!.currentBrush.palette)
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }
    private fun pinchOut(canvas:View){
        val start=android.os.SystemClock.uptimeMillis()
        fun event(action:Int,time:Long,spread:Float,count:Int=2){
            val props=Array(count){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(count){i->MotionEvent.PointerCoords().apply{x=640f+(if(i==0)-1 else 1)*spread;y=340f;pressure=1f;size=.1f}}
            val e=MotionEvent.obtain(start,start+time,action,count,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);canvas.dispatchTouchEvent(e);e.recycle()
        }
        event(MotionEvent.ACTION_DOWN,0,240f,1)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),20,240f)
        event(MotionEvent.ACTION_MOVE,120,55f)
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),160,55f)
        event(MotionEvent.ACTION_UP,180,55f,1)
    }
    @Test fun twoFingerZoomOutEntersAtlasFromRasterAndVectorWithoutDrawing(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(80,80);val raster=a.session!!;raster.penOnly=true
        until{raster.frameSerial>0};layout(a.editor!!);pinchOut(a.editor!!.canvas);until{a.atlas!=null};assertEquals(0,raster.info.optInt("undo"));a.closeAtlas()
        a.newCanvas(80,80,mode="vector");layout(a.vectorEditor!!);pinchOut(a.vectorEditor!!.canvas);until{a.atlas!=null};assertEquals(0,a.vectorDocument!!.layers.sumOf{it.objects.size});a.closeAtlas()
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun vectorGroupPsdRetainsOpacityAndTheNativeProjectRemainsEditable(){
        val doc=VectorDocument(64,64,backgroundVisible=false)
        doc.layers[0].objects.add(VectorObject(doc.nextId(),"Rectangle",mutableListOf(PointF(4f,4f),PointF(50f,50f)),Color.BLUE,Color.BLUE,1f))
        doc.edit{doc.createGroup("Blue folder");doc.groups[0].blend="Normal";doc.groups[0].opacity=.5f}
        val before=doc.encode();val file=File.createTempFile("atlas-group-",".psd");val handle=NativeBridge.create(32,32)
        try{doc.writePsd(file);val info=org.json.JSONObject(NativeBridge.importPsd(handle,file.absolutePath));assertFalse(info.has("error"));val color=org.json.JSONObject(NativeBridge.command(handle,"{\"op\":\"pick\",\"x\":20,\"y\":20}")).getLong("color").toInt();assertTrue(Color.alpha(color) in 126..129);assertEquals(255,Color.blue(color));assertArrayEquals(before,doc.encode())}finally{NativeBridge.destroy(handle);file.delete()}
    }

    @Test fun vectorGroupMultiplyUsesSourceOverAlphaRatherThanLegacyModulation(){
        val doc=VectorDocument(32,32,backgroundVisible=false)
        doc.layers[0].objects.add(VectorObject(doc.nextId(),"Rectangle",mutableListOf(PointF(2f,2f),PointF(28f,28f)),Color.RED,Color.RED,1f))
        doc.edit{doc.createGroup("Ink");doc.groups[0].blend="Multiply";doc.groups[0].opacity=.5f}
        val image=doc.bitmap();val color=image.getPixel(16,16);image.recycle();assertTrue(Color.alpha(color) in 126..129);assertEquals(255,Color.red(color))
    }
    @Test fun rainbowProfileIsAppliedThroughTheAndroidStrokePipeline(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(128,64);val s=a.session!!;s.choose(brushPresets.first{it.name=="Ink Pen"}.copy(colorMode="Rainbow",colorLayout="Across"));s.size=8f;s.stabilization=0f;s.pressureSize=0f;s.currentBrush.colorCycle=96f
        s.stroke(0,floatArrayOf(8f,32f,1f,0f));s.stroke(1,floatArrayOf(112f,32f,1f,0f));s.stroke(2,floatArrayOf(112f,32f,1f,0f));var done=false
        s.command("info"){done=true};until{done && s.frameSerial>0};var first=0;var second=0;done=false
        s.command("pick",org.json.JSONObject().put("x",16).put("y",32)){first=it.getLong("color").toInt()};s.command("pick",org.json.JSONObject().put("x",48).put("y",32)){second=it.getLong("color").toInt();done=true};until{done};assertNotEquals(first,second)
        layout(a.editor!!)
        fun find(v:View,name:String):View?{if(v.contentDescription==name)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),name)?.let{return it};return null}
        find(a.editor!!,"Paint")!!.performClick();render(a.editor!!,"rainbow-library")
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }

    @Test fun atlasSelectionEditsInPlaceAndSwitchingDocumentsKeepsTheCamera(){
        val store=DocumentStore(RuntimeEnvironment.getApplication());repeat(3){seed(store,"Neighbor ${it+1}",Color.HSVToColor(floatArrayOf(it*100f,.4f,.9f)))}
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(120,100);val root=a.editor!!;until{a.session!!.frameSerial>0};layout(root);var ready=false;a.saveCurrent{ready=it};until{ready};a.showAtlas();shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));render(root,"continuous-workspace")
        val atlas=a.atlas!!;assertFalse(atlas.editing);val center=root.canvas.transformViewPoint(60f,50f);val now=android.os.SystemClock.uptimeMillis()
        fun tap(canvas:View,p:PointF){for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(now,now+30,action,p.x,p.y,0);canvas.onTouchEvent(e);e.recycle()}}
        tap(root.canvas,center);assertTrue(atlas.editing);assertSame(root,a.editor);val same=root.canvas.transformViewPoint(60f,50f);assertEquals(center.x,same.x,.01f);assertEquals(center.y,same.y,.01f)
        atlas.enterOverview();val all=a.store.list().sortedWith(compareBy<Artwork>{it.created}.thenBy{it.id});val active=all.first{it.id==a.documentId};val activeRect=AtlasLayout(a).rect(active,all.indexOf(active));val target=all.filter{it.id!=active.id}.minBy{val r=AtlasLayout(a).rect(it,all.indexOf(it));kotlin.math.hypot(r.centerX()-activeRect.centerX(),r.centerY()-activeRect.centerY())};val r=AtlasLayout(a).rect(target,all.indexOf(target))
        val selectedAt=root.canvas.transformViewPoint((r.centerX()-activeRect.left)*120/activeRect.width(),(r.centerY()-activeRect.top)*100/activeRect.height());assertTrue(selectedAt.x in 0f..1280f);assertTrue(selectedAt.y in 0f..root.canvas.height.toFloat())
        tap(root.canvas,selectedAt);until{a.vectorEditor!=null && !a.navigationBusy};layout(a.vectorEditor!!);val after=a.vectorEditor!!.canvas.viewPoint(target.width/2f,target.height/2f)
        assertSame(atlas,a.atlas);assertTrue(atlas.editing);assertEquals(target.id,a.documentId);assertEquals(selectedAt.x,after.x,.5f);assertEquals(selectedAt.y,after.y,.5f);render(a.vectorEditor!!,"selected-neighbor-in-place")
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun multicolorDialogEditsCombinationTipLayoutAndLengthAndRestoresThem(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(128,80);val s=a.session!!;s.choose(brushPresets.first{it.name=="Ink Pen"}.copy(colorMode="Rainbow",colorLayout="Across"))
        fun find(v:View,label:String):View?{if(v.contentDescription==label)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),label)?.let{return it};return null}
        val root=a.editor!!;root.multicolorSettings();layout(root);val body=root
        find(body,"Custom")!!.performClick();find(body,"Adjust color cycle  ⌄")!!.performClick();find(body,"Bristle strands")!!.performClick();find(body,"Brush tip Bristle")!!.performClick()
        (find(body,"Color cycle length") as android.widget.EditText).setText("250");find(body,"Set color cycle length")!!.performClick()
        find(body,"Combination color 1")!!.performClick();(find(body,"Brush color hex") as android.widget.EditText).setText("#AB44CD");find(body,"Apply brush color")!!.performClick();layout(root);shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));layout(root);render(root,"multicolor-panel")
        fun assertCustom(v:View){assertFalse(v is android.widget.Spinner);assertFalse(v is android.widget.SeekBar);if(v is ViewGroup)for(i in 0 until v.childCount)assertCustom(v.getChildAt(i))}
        assertCustom(find(body,"Close multicolor")!!.parent.parent as View)
        assertEquals("Multicolor",root.panelKey);find(body,"Close multicolor")!!.performClick();assertEquals("",root.panelKey)
        assertEquals("Applied tip",11,s.currentBrush.kind);assertEquals("Palette",s.currentBrush.colorMode);assertEquals("Bristles",s.currentBrush.colorLayout);assertEquals(250f,s.currentBrush.colorCycle,0f);assertEquals(0xffab44cd.toInt(),s.currentBrush.palette[0]);a.newCanvas(128,80);val restored=a.session!!.currentBrush;assertEquals("Restored tip",11,restored.kind);assertEquals("Bristles",restored.colorLayout);assertEquals(0xffab44cd.toInt(),restored.palette[0])
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun multicolorTipsProduceWidthVariationThroughTheRealStrokePipeline(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(720,480);val s=a.session!!
        for((index,name) in listOf("House Brush","Threads","Chisel Tip").withIndex()){
            s.choose(brushPresets.first{it.name==name}.copy(colorMode="Rainbow",colorLayout=if(index==2)"Across"else"Bristles"));s.size=66f;s.pressureSize=0f;s.stabilization=0f;s.currentBrush.colorCycle=1600f
            val y=70f+index*140f;s.stroke(0,floatArrayOf(50f,y,1f,0f));s.stroke(1,floatArrayOf(250f,y,1f,0f,400f,y+45,1f,0f,640f,y,1f,0f));s.stroke(2,floatArrayOf(640f,y,1f,0f))
        }
        var saved=false;s.imageSnapshot{image->assertNotNull(image);image!!.let{b->File(System.getProperty("inkstone.artifacts"),"atlas-multicolor-strokes.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};assertNotEquals(b.getPixel(190,58),b.getPixel(190,82));b.recycle()};saved=true};until{saved}
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }

    @Test fun mixerIsSeparateAndLibraryUsesEditorPanels(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(120,100);val root=a.editor!!;layout(root)
        fun find(v:View,name:String):View?{if(v.contentDescription==name || (v is android.widget.TextView && v.text.toString()==name))return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),name)?.let{return it};return null}
        find(root,"Paint Mixing")!!.performClick();render(root,"preview14-paint-mixing");assertTrue(a.session!!.paintMixingEnabled);assertEquals("Paint",a.session!!.tool);assertNotNull(find(root,"Color pickup"));assertTrue(find(root,"Wetness") is BrushRange);a.session!!.choose(brushPresets.first{it.name=="Clear Wash"});assertTrue(a.session!!.paintMixingEnabled);assertEquals(.65f,a.session!!.paintBrush.wetMix,.001f)
        root.dismissPanel();find(root,"Paint")!!.performClick();assertFalse(a.session!!.paintMixingEnabled);find(root,"Add")!!.performClick();assertNotNull(find(root,"New category"));render(root,"preview14-library-menu");find(root,"Brush Studio")!!.performClick();assertNotNull(find(root,"Multicolor settings"));assertTrue(find(root,"Flow") is BrushRange);render(root,"preview14-brush-studio")
        assertFalse(brushPresets.any{it.name.startsWith("Rainbow ")})
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }
    @Test fun atlasEyedropperSamplesInactiveArtWithoutChangingCurrentDocument(){
        val store=DocumentStore(RuntimeEnvironment.getApplication());val other=seed(store,"Color study",Color.CYAN)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(120,100);val session=a.session!!;val root=a.editor!!;until{session.frameSerial>0};layout(root);var saved=false;a.saveCurrent{saved=it};until{saved};a.showAtlas();val atlas=a.atlas!!;render(root,"picker-overview");until{atlas.previewCount>0};render(root,"picker-overview")
        val activeId=a.documentId;val all=a.store.list().sortedWith(compareBy<Artwork>{it.created}.thenBy{it.id});val active=all.first{it.id==activeId};val activeRect=AtlasLayout(a).rect(active,all.indexOf(active));val target=all.first{it.id==other};val r=AtlasLayout(a).rect(target,all.indexOf(target));val point=root.canvas.transformViewPoint((r.centerX()-activeRect.left)*120/activeRect.width(),(r.centerY()-activeRect.top)*100/activeRect.height())
        session.tool="Eyedropper";val now=android.os.SystemClock.uptimeMillis();val event=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,point.x,point.y,0);root.canvas.onTouchEvent(event);event.recycle();until{session.color==Color.CYAN}
        val up=MotionEvent.obtain(now,now+50,MotionEvent.ACTION_UP,point.x,point.y,0);root.canvas.onTouchEvent(up);up.recycle();assertEquals(activeId,a.documentId);assertSame(session,a.session);assertNull(a.vectorEditor);assertFalse(atlas.editing)
        controller.pause().stop().destroy();until{a.io.isTerminated}
    }

}
