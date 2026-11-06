package art.inkstone

import android.graphics.*
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w1280dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VectorTest {
    private fun until(check:()->Boolean){val end=System.nanoTime()+20_000_000_000;while(System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10)};error("Vector operation timed out")}
    private fun named(root:View,name:String):View?{if(root.contentDescription==name || root is TextView && root.text.toString()==name)return root;if(root is ViewGroup)for(i in 0 until root.childCount)named(root.getChildAt(i),name)?.let{return it};return null}
    private fun click(root:View,name:String){assertTrue(checkNotNull(named(root,name)){"Missing $name"}.performClick());shadowOf(Looper.getMainLooper()).idle()}
    private fun render(view:View,name:String,w:Int=1280,h:Int=800){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));view.rootView.takeIf{it!==view}?.let{root->root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)};view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h)
        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);view.draw(Canvas(image));File(System.getProperty("inkstone.artifacts"),"$name.png").outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()}
    private fun touch(canvas:VectorCanvas,action:Int,x:Float,y:Float){val p=canvas.viewPoint(x,y);val time=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(time,time,action,p.x,p.y,0);canvas.onTouchEvent(e);e.recycle()}
    private fun gesture(canvas:VectorCanvas,x:Float,y:Float,x1:Float,y1:Float,cancel:Boolean=false){touch(canvas,MotionEvent.ACTION_DOWN,x,y);touch(canvas,MotionEvent.ACTION_MOVE,x1,y1);touch(canvas,if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,x1,y1)}
    private fun finish(controller:org.robolectric.android.controller.ActivityController<MainActivity>){var saved=false;controller.get().saveCurrent{assertTrue(it);saved=true};until{saved};controller.pause().stop().destroy();until{controller.get().io.isTerminated}}
    private fun fixture():VectorDocument {
        val doc=VectorDocument(240,180,300,Color.WHITE,false)
        doc.layers[0].name="Paths & 底层"
        doc.edit{
            doc.layers[0].objects.add(VectorObject(doc.nextId(),"Pen",mutableListOf(PointF(20f,30f),PointF(50f,15f),PointF(85f,60f),PointF(130f,30f)),0xff285c9d.toInt(),null,5f))
            doc.layers[0].objects.add(VectorObject(doc.nextId(),"Rectangle",mutableListOf(PointF(30f,70f),PointF(110f,135f)),0xff31445a.toInt(),0x90ef9250.toInt(),3f,Matrix().apply{postRotate(-12f,70f,100f)}))
            val layer=VectorLayer(doc.nextId(),"Shapes <top>",opacity=.6f);doc.layers.add(layer);doc.active=1
            layer.objects.add(VectorObject(doc.nextId(),"Ellipse",mutableListOf(PointF(110f,60f),PointF(205f,145f)),0xff2a7669.toInt(),0xb06eb6a4.toInt(),4f,Matrix().apply{postScale(1.1f,.8f,150f,100f)}))
            layer.objects.add(VectorObject(doc.nextId(),"Line",mutableListOf(PointF(120f,25f),PointF(210f,50f)),0xffbd5267.toInt(),null,7f))
        };doc.layers[0].objects[0].toBezier();return doc
    }
    @Test fun vectorRoundTripHistoryAndRealExportFixtures(){
        val doc=fixture();val saved=doc.encode();assertArrayEquals(saved,VectorDocument.decode(saved).encode())
        val image=doc.bitmap();assertEquals(0,image.getPixel(0,0) ushr 24);assertTrue(doc.undo());assertEquals(0,doc.layers[0].objects.size);assertTrue(doc.redo());assertArrayEquals(saved,doc.encode())
        val folder=File(System.getProperty("inkstone.artifacts"));File(folder,"vector-fixture.svg").writeText(doc.svg());File(folder,"vector-fixture.json").writeBytes(doc.encode())
        File(folder,"vector-fixture.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        assertFalse(doc.svg().contains("<image"));assertFalse(doc.svg().contains("data:image"));assertTrue(doc.svg().contains("<path"));assertTrue(doc.svg().contains("<ellipse"));assertTrue(doc.svg().contains("&amp;"));assertTrue(doc.svg().contains("&lt;top&gt;"))
        val xml=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}.newDocumentBuilder().parse(doc.svg().byteInputStream())
        assertEquals("svg",xml.documentElement.localName);assertEquals(2,xml.getElementsByTagName("path").length)
        doc.writePsd(File(folder,"vector-fixture.psd"));assertEquals("8BPS",File(folder,"vector-fixture.psd").inputStream().use{val b=ByteArray(4);it.read(b);String(b)})
    }
    @Test fun insertedAnchorPreservesCubicThenBendsWithHistoryAndExport(){
        val obj=VectorObject(2,"Bezier",mutableListOf(PointF(10f,20f),PointF(40f,-5f),PointF(70f,110f),PointF(110f,60f)),Color.BLACK,null,3f,Matrix().apply{postRotate(18f);postTranslate(15f,30f)})
        val original=obj.points.map{PointF(it.x,it.y)};val target=VectorGeometry.cubic(original[0],original[1],original[2],original[3],.37f)
        assertEquals(3,VectorGeometry.insertAnchor(obj,VectorGeometry.mapped(obj,target),2f));assertEquals(7,obj.points.size)
        val split=obj.points[3];assertEquals(target.x,split.x,.01f);assertEquals(target.y,split.y,.01f)
        for(j in 0..100){val t=j/100f;val expected=VectorGeometry.cubic(original[0],original[1],original[2],original[3],t)
            val i=if(t<=.37f)0 else 3;val u=if(i==0)t/.37f else (t-.37f)/.63f
            val actual=VectorGeometry.cubic(obj.points[i],obj.points[i+1],obj.points[i+2],obj.points[i+3],u)
            assertEquals(expected.x,actual.x,.02f);assertEquals(expected.y,actual.y,.02f)
        }
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(320,240,mode="vector")
        val e=checkNotNull(a.vectorEditor);val canvas=e.canvas;val doc=checkNotNull(a.vectorDocument);render(e,"anchor-before")
        click(e,"Line");gesture(canvas,30f,100f,260f,100f);val id=doc.layers[0].objects[0].id
        click(e,"Nodes");canvas.selectedId=id;e.refresh()
        touch(canvas,MotionEvent.ACTION_DOWN,260f,100f);touch(canvas,MotionEvent.ACTION_UP,260f,100f)
        assertEquals(1,canvas.selectedNode);assertEquals(260f,checkNotNull(canvas.nodePosition()).x,0f);assertEquals("Line",canvas.selected!!.kind)
        val straight=doc.encode();click(e,"Add anchor");assertTrue(canvas.addingAnchor)
        touch(canvas,MotionEvent.ACTION_DOWN,140f,100f);touch(canvas,MotionEvent.ACTION_UP,140f,100f)
        assertFalse(canvas.addingAnchor);assertEquals(3,canvas.selectedNode);assertEquals(7,canvas.selected!!.points.size)
        val added=doc.encode();gesture(canvas,140f,100f,140f,145f);assertEquals(145f,canvas.selected!!.points[3].y,.01f)
        val bent=doc.encode();assertTrue(doc.svg().contains(" C"));assertArrayEquals(bent,VectorDocument.decode(bent).encode())
        render(e,"anchor-added-and-bent");canvas.undo();assertArrayEquals(added,doc.encode());canvas.undo();assertArrayEquals(straight,doc.encode());canvas.redo();canvas.redo();assertArrayEquals(bent,doc.encode());finish(controller)
    }
    @Test fun malformedVectorIsRejectedAndFailedEditRollsBack(){
        val doc=fixture();val before=doc.encode()
        val malformed=org.json.JSONObject(before.toString(Charsets.UTF_8)).put("width",Int.MAX_VALUE).toString().toByteArray();assertTrue(runCatching{VectorDocument.decode(malformed)}.isFailure)
        assertTrue(runCatching{doc.edit{doc.layers[0].objects[0].matrix.setScale(0f,0f)}}.isFailure);assertArrayEquals(before,doc.encode())
        val oversizedId=org.json.JSONObject(before.toString(Charsets.UTF_8));oversizedId.getJSONArray("layers").getJSONObject(0).put("id",Long.MAX_VALUE-1);assertTrue(runCatching{VectorDocument.decode(oversizedId.toString().toByteArray())}.isFailure)
        val invalid=org.json.JSONObject(before.toString(Charsets.UTF_8));invalid.getJSONArray("layers").getJSONObject(1).put("id",1);assertTrue(runCatching{VectorDocument.decode(invalid.toString().toByteArray())}.isFailure)
    }
    @Test fun canvasShapesSelectionTransformNodesAndCancellation(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();val old=a.session
        a.newCanvas(320,240,mode="vector");until{old==null || old.fullyClosed};val editor=checkNotNull(a.vectorEditor);val canvas=editor.canvas;val doc=checkNotNull(a.vectorDocument)
        render(editor,"vector-editor-empty");canvas.fillColor=0xff8badcb.toInt();click(editor,"Rectangle");gesture(canvas,40f,40f,110f,95f);assertEquals(1,doc.layers[0].objects.size)
        click(editor,"Pen");gesture(canvas,160f,45f,230f,80f);assertEquals(2,doc.layers[0].objects.size)
        click(editor,"Select");gesture(canvas,65f,65f,85f,85f);val obj=checkNotNull(canvas.selected);assertEquals("Rectangle",obj.kind);assertTrue(obj.bounds().left>50)
        val afterMove=doc.encode();val bounds=obj.bounds();gesture(canvas,bounds.right,bounds.bottom,bounds.right+25,bounds.bottom+20);assertTrue(canvas.selected!!.bounds().width()>bounds.width())
        val scaled=doc.encode();val r=canvas.selected!!.bounds()
        val top=canvas.viewPoint(r.centerX(),r.top);val handle=canvas.pagePoint(top.x,top.y-24*resourcesDensity(canvas))
        touch(canvas,MotionEvent.ACTION_DOWN,handle.x,handle.y);touch(canvas,MotionEvent.ACTION_MOVE,r.right+20,r.centerY())
        assertFalse(scaled.contentEquals(doc.encode()));assertArrayEquals(scaled,editor.snapshotBytes())
        touch(canvas,MotionEvent.ACTION_CANCEL,r.right+20,r.centerY());assertArrayEquals(scaled,doc.encode())
        gesture(canvas,handle.x,handle.y,r.right+20,r.centerY());assertFalse(scaled.contentEquals(doc.encode()));canvas.undo();assertArrayEquals(scaled,doc.encode())
        canvas.undo();assertArrayEquals(afterMove,doc.encode());canvas.redo();assertArrayEquals(scaled,doc.encode())
        click(editor,"Nodes");val selected=canvas.selected
        if(selected==null){canvas.selectedId=doc.layers[0].objects[0].id;editor.refresh()}
        val node=doc.layers[0].objects[0];val xy=floatArrayOf(node.points[0].x,node.points[0].y);node.matrix.mapPoints(xy)
        val before=node.points[0].x;gesture(canvas,xy[0],xy[1],xy[0]+8,xy[1]+4);assertNotEquals(before,doc.layers[0].objects[0].points[0].x)
        val committed=editor.snapshotBytes();click(editor,"Pen");touch(canvas,MotionEvent.ACTION_DOWN,170f,130f);touch(canvas,MotionEvent.ACTION_MOVE,230f,150f);assertArrayEquals(committed,editor.snapshotBytes());touch(canvas,MotionEvent.ACTION_CANCEL,230f,150f);assertArrayEquals(committed,doc.encode())
        a.setVectorInputPreference(0,true);gesture(canvas,20f,20f,60f,30f);assertArrayEquals(committed,doc.encode())
        render(editor,"vector-editor-objects");finish(controller)
    }
    @Test fun vectorChromeMatchesRasterAndRotatesLive(){
        RuntimeEnvironment.setQualifiers("w400dp-h820dp-mdpi")
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(320,240,mode="vector")
        val e=checkNotNull(a.vectorEditor);render(e,"feedback2-vector-portrait",400,820);assertEquals(88,e.canvas.top)
        assertTrue(checkNotNull(named(e,"History and save")).top>700)
        RuntimeEnvironment.setQualifiers("w1280dp-h800dp-land-mdpi");controller.configurationChange();render(e,"feedback2-vector-landscape",1280,800);assertEquals(44,e.canvas.top)
        val gallery=checkNotNull(named(e,"Home")) as TextView;assertEquals("Home",gallery.text.toString());assertTrue(gallery.lineCount>0)
        val color=checkNotNull(named(e,"Colors"));val rect=android.graphics.Rect();assertTrue(color.getGlobalVisibleRect(rect));assertEquals(1280,rect.right)
        fun center(v:View):Float{val xy=IntArray(2);v.getLocationOnScreen(xy);return xy[0]+v.width/2f}
        assertEquals(640f,(center(checkNotNull(named(e,"Undo")))+center(checkNotNull(named(e,"Redo"))))/2,1f)
        click(e,"Colors");render(e,"feedback2-vector-colors");e.dismissPanel()
        click(e,"Layers");render(e,"feedback2-vector-layers");e.dismissPanel()
        val slider=checkNotNull(named(e,"Vector stroke width"));val time=android.os.SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,12f,100f,0);slider.dispatchTouchEvent(down);down.recycle()
        render(e,"feedback2-vector-slider");val up=MotionEvent.obtain(time,time+10,MotionEvent.ACTION_UP,12f,100f,0);slider.dispatchTouchEvent(up);up.recycle()
        finish(controller)
    }
    @Test fun directNodesHaveStableTargetsHandlesAndUndo(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(240,180,mode="vector")
        val e=checkNotNull(a.vectorEditor);val c=e.canvas;val d=checkNotNull(a.vectorDocument)
        val curve=VectorObject(d.nextId(),"Bezier",mutableListOf(PointF(30f,60f),PointF(45f,30f),PointF(65f,30f),PointF(80f,60f),PointF(95f,90f),PointF(115f,90f),PointF(130f,60f)),0xff2186ca.toInt(),null,3f)
        d.edit{d.layers[0].objects.add(curve)};render(e,"preview3-node-before");click(e,"Nodes")
        gesture(c,82f,61f,82f,61f);assertEquals(curve.id,c.selectedId);assertEquals(3,c.selectedNode);assertEquals(1,d.undoCount)
        gesture(c,82f,61f,92f,67f);val moved=checkNotNull(c.selected)
        assertEquals(90f,moved.points[3].x,.01f);assertEquals(66f,moved.points[3].y,.01f)
        assertEquals(75f,moved.points[2].x,.01f);assertEquals(105f,moved.points[4].x,.01f);assertEquals(30f,moved.points[0].x,0f)
        val committed=d.encode();gesture(c,75f,36f,75f,25f,cancel=true);assertArrayEquals(committed,d.encode())
        gesture(c,75f,36f,75f,25f);assertEquals(25f,c.selected!!.points[2].y,.01f);assertEquals(66f,c.selected!!.points[3].y,.01f)
        render(e,"preview3-selected-node-handles");c.undo();assertArrayEquals(committed,d.encode());c.redo();assertEquals(25f,d.layers[0].objects[0].points[2].y,.01f)
        c.selectedId=curve.id;c.changeTool("Nodes");c.cycleNode(1);val selected=c.selectedNode;click(e,"Next point");assertNotEquals(selected,c.selectedNode)
        val p=checkNotNull(c.nodePosition());c.setNodePosition(p.x+1,p.y+2);assertEquals(p.x+1,c.nodePosition()!!.x,.01f)
        assertArrayEquals(d.encode(),VectorDocument.decode(d.encode()).encode());finish(controller)
    }
    @Test fun vectorcraftPickingAndLegacyCurveConversionPreserveGeometry(){
        val d=VectorDocument(160,100);val bottom=VectorObject(d.nextId(),"Line",mutableListOf(PointF(25f,30f),PointF(80f,30f)),Color.BLACK,null,2f)
        val top=VectorObject(d.nextId(),"Line",mutableListOf(PointF(27f,30f),PointF(82f,30f)),Color.RED,null,2f)
        d.layers[0].objects.addAll(listOf(bottom,top))
        assertEquals(bottom.id,VectorGeometry.hitAnchor(d,bottom.id,PointF(27f,30f),10f)!!.objectId)
        assertEquals(top.id,VectorGeometry.hitAnchor(d,null,PointF(27f,30f),10f)!!.objectId)
        val pen=VectorObject(d.nextId(),"Pen",mutableListOf(PointF(10f,70f),PointF(30f,20f),PointF(70f,80f),PointF(110f,30f)),Color.BLUE,null,4f)
        d.layers[0].objects.add(pen);val before=d.bitmap();pen.toBezier();val after=d.bitmap()
        val a=IntArray(16000);val b=IntArray(16000);before.getPixels(a,0,160,0,0,160,100);after.getPixels(b,0,160,0,0,160,100)
        val error=a.indices.sumOf{i->(0..2).sumOf{k->kotlin.math.abs((a[i] ushr (k*8) and 255)-(b[i] ushr(k*8) and 255))}}/48000.0
        assertTrue(error<.1);before.recycle();after.recycle();assertTrue(d.svg().contains(" C"));assertArrayEquals(d.encode(),VectorDocument.decode(d.encode()).encode())
        val straight=(0..100).map{PointF(it.toFloat(),it.toFloat())};assertEquals(2,VectorGeometry.simplify(straight,1f).size)
    }
    @Test fun vectorStabilizerReducesJitterAndPersists(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.newCanvas(300,200,mode="vector")
        val e=checkNotNull(a.vectorEditor);val c=e.canvas;render(e,"preview3-stabilizer-before")
        for((index,amount)in listOf(0f,1f).withIndex()){
            c.stabilization=amount;val y=50f+index*90;touch(c,MotionEvent.ACTION_DOWN,30f,y)
            for(i in 1..30){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));touch(c,MotionEvent.ACTION_MOVE,30f+i*6,y+if(i%2==0)8f else -8f)}
            touch(c,MotionEvent.ACTION_UP,210f,y)
        }
        val paths=checkNotNull(a.vectorDocument).layers[0].objects;assertEquals(2,paths.size);assertTrue(paths.all{it.kind=="Bezier"})
        fun jitter(o:VectorObject,base:Float)=VectorGeometry.anchors(o).map{kotlin.math.abs(it.second.y-base)}.average()
        assertTrue(jitter(paths[1],140f)<jitter(paths[0],50f)*.7)
        a.vectorStabilization=1f;click(e,"Actions");click(e,"Stroke settings");assertEquals(100,(checkNotNull(named(e,"Vector stabilization")) as android.widget.SeekBar).progress)
        render(e,"preview3-vector-stabilizer");a.newCanvas(320,240,mode="vector");assertEquals(1f,a.vectorEditor!!.canvas.stabilization,0f);finish(controller)
    }
    private fun resourcesDensity(canvas:VectorCanvas)=canvas.resources.displayMetrics.density
    @Test fun newCanvasModeGalleryReopenAndExportPicker(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();a.showGallery();a.createDialog()
        val menu=checkNotNull(a.canvasPopup).contentView;render(menu,"vector-new-canvas",400,650);click(menu,"Vector document");click(menu,"Create Square")
        until{a.vectorDocument!=null};val doc=checkNotNull(a.vectorDocument);assertEquals(2048,doc.width);val editor=checkNotNull(a.vectorEditor)
        render(editor,"vector-square-editor");gesture(editor.canvas,500f,500f,1000f,650f)
        var saved=false;a.saveCurrent{assertTrue(it);saved=true};until{saved};val art=a.store.list().first{it.id==a.documentId};assertEquals("vector",art.mode)
        val expected=doc.encode();a.showGallery();a.openArtwork(art);until{a.vectorDocument!=null};assertArrayEquals(expected,a.vectorDocument!!.encode())
        a.export("SVG");var started:org.robolectric.shadows.ShadowActivity.IntentForResult?=null;until{started=shadowOf(a).nextStartedActivityForResult;started!=null};assertEquals("image/svg+xml",started!!.intent.type)
        shadowOf(a).receiveResult(started!!.intent,android.app.Activity.RESULT_CANCELED,null)
        RuntimeEnvironment.setQualifiers("w400dp-h820dp-mdpi");render(checkNotNull(a.vectorEditor),"vector-editor-phone",400,820);finish(controller)
    }
}
