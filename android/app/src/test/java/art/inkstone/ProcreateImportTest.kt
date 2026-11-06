package art.inkstone

import android.graphics.Bitmap
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes binary property lists, enough to build test Procreate brush archives. */
private class PlistWriter{
    private val objects=mutableListOf<ByteArray>()
    class Uid(val value:Int)
    private fun add(bytes:ByteArray):Int{objects.add(bytes);return objects.size-1}
    private fun header(type:Int,n:Int,out:ByteArrayOutputStream){if(n<15)out.write(type shl 4 or n) else{out.write(type shl 4 or 15);out.write(0x11);out.write(n shr 8);out.write(n and 255)}}
    fun value(v:Any?):Int=when(v){
        null->add(byteArrayOf(0))
        is Boolean->add(byteArrayOf(if(v)9 else 8))
        is Int->add(byteArrayOf(0x12,(v shr 24).toByte(),(v shr 16).toByte(),(v shr 8).toByte(),v.toByte()))
        is Double->{val b=java.lang.Double.doubleToLongBits(v);add(byteArrayOf(0x23)+ByteArray(8){(b shr (56-8*it)).toByte()})}
        is String->{val o=ByteArrayOutputStream();header(5,v.length,o);o.write(v.toByteArray(Charsets.US_ASCII));add(o.toByteArray())}
        is Uid->add(byteArrayOf(0x80.toByte(),v.value.toByte()))
        is List<*>->{val refs=v.map{value(it)};val o=ByteArrayOutputStream();header(10,refs.size,o);refs.forEach{o.write(it shr 8);o.write(it and 255)};add(o.toByteArray())}
        is Map<*,*>->{val keys=v.keys.map{value(it)};val values=v.values.map{value(it)};val o=ByteArrayOutputStream();header(13,keys.size,o);(keys+values).forEach{o.write(it shr 8);o.write(it and 255)};add(o.toByteArray())}
        else->error("unsupported")
    }
    fun write(root:Any?):ByteArray{
        val top=value(root);val out=ByteArrayOutputStream();out.write("bplist00".toByteArray())
        val offsets=objects.map{val at=out.size();out.write(it);at}
        val table=out.size();offsets.forEach{for(s in listOf(24,16,8,0))out.write(it shr s and 255)}
        val trailer=ByteArray(32);trailer[6]=4;trailer[7]=2
        fun long(at:Int,v:Int){for(i in 0 until 8)trailer[at+i]=(v.toLong() shr (56-8*i)).toByte()}
        long(8,objects.size);long(16,top);long(24,table);out.write(trailer);return out.toByteArray()
    }
}

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ProcreateImportTest{
    private fun archive(name:String,extra:Map<String,Any?> = emptyMap()):ByteArray{
        val brush=linkedMapOf<String,Any?>("name" to PlistWriter.Uid(2),"paintSize" to .4,"maxSize" to 1.0,"plotSpacing" to .25,"maxOpacity" to .8,"minOpacity" to .2,"dynamicsPressureOpacity" to 1.0,"dynamicsSpeedOpacity" to .4,"dynamicsMix" to .3,"shapeInverted" to false,"\$class" to PlistWriter.Uid(3))+extra
        return PlistWriter().write(mapOf("\$archiver" to "NSKeyedArchiver","\$version" to 100000,"\$top" to mapOf("root" to PlistWriter.Uid(1)),
            "\$objects" to listOf("\$null",brush,name,mapOf("\$classname" to "SilicaBrush"))))
    }
    private fun png(w:Int,color:(Int,Int)->Int):ByteArray{
        val b=Bitmap.createBitmap(IntArray(w*w){color(it%w,it/w)},w,w,Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also{b.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
    }
    private fun zip(files:Map<String,ByteArray>)=ByteArrayOutputStream().also{o->ZipOutputStream(o).use{z->for((k,v) in files){z.putNextEntry(ZipEntry(k));z.write(v);z.closeEntry()}}}.toByteArray()
    private val white=0xffffffff.toInt();private val black=0xff000000.toInt()

    @Test fun readsBrushSetsAndKeyedArchives(){
        BrushTips.init(RuntimeEnvironment.getApplication())
        val set=zip(mapOf(
            "brushset.plist" to "<?xml version=\"1.0\"?><plist><dict><key>name</key><string>Test Set</string><key>brushes</key><array><string>B</string><string>A</string></array></dict></plist>".toByteArray(),
            "A/Brush.archive" to archive("Half Ink",mapOf("pencilTaperStartLength" to .2,"pencilTaperSize" to .5,"taperStartLength" to .9,"blendMode" to 1,"dynamicsTiltSize" to .4,"dynamicsSpeedSize" to -0.3,"dynamicsPressureSizeCurve" to mapOf("points" to mapOf("NS.objects" to listOf("{0.000000, 0.000000}","{0.400000, 0.700000}","{1.000000, 1.000000}"))))),
            "A/Shape.png" to png(64){x,_->if(x>=32)white else black},
            "A/Grain.png" to png(128){x,_->if(x/4%2==0)white else 0xff808080.toInt()},
            "B/Brush.archive" to archive("Bundled",mapOf("shapeInverted" to true,"bundledShapePath" to PlistWriter.Uid(0))),
            "../evil/Brush.archive" to archive("Escape"),
        ))
        val read=ProcreateBrushes.read(set.inputStream())
        assertEquals("Test Set",read.setName)
        assertEquals(listOf("Bundled","Half Ink"),read.brushes.map{it.name})
        val half=ProcreateBrushes.preset(read.brushes[1],"Test Set")
        assertEquals(IMAGE_TIP_KIND,half.kind);assertEquals(40f,half.size,.01f);assertEquals(.25f,half.tipSpacing,.001f);assertEquals(.8f,half.opacity,.001f);assertEquals(.75f,half.pressureOpacity,.001f);assertEquals(.4f,half.tipSpeedOpacity,.001f);assertEquals(.3f,half.wetMix,.001f)
        // Pencil taper values win over finger ones; blend 1 is Multiply.
        assertEquals(TipDynamics(taperStart=.2f,taperSize=.5f,tiltSize=.4f,speedSize=-.3f,blend=1,sizeCurve=listOf(0f,0f,.4f,.7f,1f,1f)),half.tipDynamics)
        val tip=checkNotNull(BrushTips.load(half.tip))
        assertEquals(64,tip.size);assertEquals(128,tip.grainSize);assertEquals(1f,half.grainScale,.001f)
        assertTrue(tip.shape[32*64+10].toInt()==0 && tip.shape[32*64+50].toInt() and 255==255)
        // A brush without Shape.png gets a round tip, even when its settings say inverted.
        val round=checkNotNull(BrushTips.load(ProcreateBrushes.preset(read.brushes[0],"Test Set").tip))
        assertEquals(255,round.shape[32*64+32].toInt() and 255);assertEquals(0,round.shape[0].toInt())

        // The library keeps the tip and its settings, and deleting the last copy deletes the tip file.
        val lib=BrushLibrary(RuntimeEnvironment.getApplication())
        val added=lib.addAll(listOf(half),"Test Set")
        val stored=lib.custom().single{it.customId==added[0].customId}
        assertEquals(half.tip,stored.tip);assertEquals(half.tipSpacing,stored.tipSpacing,.001f);assertEquals("Test Set",stored.group)
        val copy=lib.copy(stored,"Half Ink copy")
        lib.delete(stored.customId);assertNotNull(BrushTips.load(copy.tip))
        lib.delete(copy.customId);assertNull(BrushTips.load(copy.tip))

        assertThrows(IllegalArgumentException::class.java){ProcreateBrushes.read(zip(mapOf("readme.txt" to ByteArray(3))).inputStream())}
        assertThrows(Exception::class.java){BinaryPlist("bplist00garbage-garbage-garbage-garbage-garbage".toByteArray()).root()}
    }

    @Test fun stockTipsStandInForBuiltInImagesAndTile(){
        val soft=checkNotNull(StockTips.shape("Brush-Preset-Soft.png"))
        assertEquals(255,soft[128*256+128].toInt() and 255);assertEquals(0,soft[0].toInt());assertTrue((soft[128*256+64].toInt() and 255) in 40..220)
        assertNotNull(StockTips.shape("Brush-Preset-Hard.png"));assertNull(StockTips.shape("Brush-Preset-Leaf.png"))
        for(name in listOf("Brush-Preset-Grunge.jpg","Brush-Artery-Charcoal-Burnt.jpg")){
            val g=checkNotNull(StockTips.grain(name));assertEquals(512*512,g.size)
            val v={x:Int,y:Int->g[y*512+x].toInt() and 255}
            // Seamless: neighbours across the wrap differ about as little as neighbours inside.
            val seam=(0 until 512).sumOf{kotlin.math.abs(v(511,it)-v(0,it))}.toDouble()/512
            val inside=(0 until 512).sumOf{kotlin.math.abs(v(255,it)-v(256,it))}.toDouble()/512
            assertTrue("$name seam $seam inside $inside",seam<inside*2+8)
            assertTrue("$name has contrast",g.map{it.toInt() and 255}.let{(it.maxOrNull()?:0)-(it.minOrNull()?:0)}>150)
        }
        assertNull(StockTips.grain("Brush-Preset-Unknown.jpg"))
    }

    @Test fun importedTipPaintsItsSquareShape(){
        RuntimeEnvironment.getApplication().getSharedPreferences("tutorials",0).edit().putBoolean("welcomeSeen",true).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        a.newCanvas(400,300);val s=a.editor!!.session
        val set=zip(mapOf("Brush.archive" to archive("Square"),"Shape.png" to png(32){_,_->white}))
        val preset=BrushLibrary(a).addAll(read(set),"Imported")[0]
        s.choose(preset);s.color=0xff2050a0.toInt();s.size=80f;s.pressureSize=0f;s.opacity=1f
        s.stroke(0,floatArrayOf(200f,150f,1f,0f));s.stroke(2,floatArrayOf())
        var done=false;s.command("info"){done=true}
        val end=System.nanoTime()+30_000_000_000;while(!done && System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        shadowOf(Looper.getMainLooper()).idle()
        // (234, 184) lies outside a round 80 px dab but inside the square tip.
        assertTrue("corner painted",s.bitmap.getPixel(234,184) ushr 24>200)
        assertEquals("outside the square",s.bitmap.getPixel(10,10),s.bitmap.getPixel(246,196))
        c.pause().stop().destroy()
    }
    private fun read(bytes:ByteArray)=ProcreateBrushes.read(bytes.inputStream()).brushes.map{ProcreateBrushes.preset(it,"Imported")}

    /** Draws real brushes from INKSTONE_BRUSH_DIR into artifacts/brush-import, for checking by eye. Skipped without it. */
    @Test fun rendersRealBrushes(){
        val dir=System.getenv("INKSTONE_BRUSH_DIR")?.let{java.io.File(it)}?.takeIf{it.isDirectory}?:return
        RuntimeEnvironment.getApplication().getSharedPreferences("tutorials",0).edit().putBoolean("welcomeSeen",true).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        val wanted=System.getenv("INKSTONE_BRUSH_NAMES")?.split(",")?:listOf("Spiderbaby","Noodles","Bubble","Rising")
        // Brushes from .brush files and from inside .brushset files, matched by file or brush name.
        val found=dir.walkTopDown().filter{it.extension=="brush" || it.extension=="brushset"}.flatMap{f->
            f.inputStream().use{ProcreateBrushes.read(it)}.brushes.filter{b->wanted.any{w->f.name.startsWith(w) || b.name.trim().equals(w.trim(),true)}}.map{f to it}
        }.toList()
        assertTrue(found.isNotEmpty())
        for((name,pixels,n) in listOf(Triple("soft-round",StockTips.softRound(256),256),Triple("grunge",StockTips.grunge(512),512),Triple("charcoal",StockTips.charcoal(512),512))){
            val b=Bitmap.createBitmap(IntArray(n*n){val v=pixels[it].toInt() and 255;0xff000000.toInt() or (v*0x10101)},n,n,Bitmap.Config.ARGB_8888)
            java.io.File(System.getProperty("inkstone.artifacts"),"brush-import/stock-$name.png").also{it.parentFile?.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        for((file,brush) in found){
            a.newCanvas(900,700);val s=a.editor!!.session
            val preset=ProcreateBrushes.preset(brush,"Test")
            // Light brushes (Screen, Add) show on a dark background.
            if(preset.tipDynamics.blend>=2)s.command("background",org.json.JSONObject().put("color",0xff1b1f2aL))
            s.choose(preset);s.color=0xff1d3b8f.toInt();s.size=preset.size.coerceAtMost(140f);s.pressureSize=0f
            fun line(vararg xy:Float){
                val pts=FloatArray(xy.size*2);for(i in xy.indices step 2){pts[i*2]=xy[i];pts[i*2+1]=xy[i+1];pts[i*2+2]=1f;pts[i*2+3]=0f}
                s.stroke(0,pts.copyOfRange(0,4));for(i in 4 until pts.size step 4)s.stroke(1,pts.copyOfRange(i,i+4));s.stroke(2,floatArrayOf())
            }
            fun path(x0:Float,y0:Float,x1:Float,y1:Float)=FloatArray(42){i->val t=(i/2)/20f;if(i%2==0)x0+(x1-x0)*t else y0+(y1-y0)*t}
            line(*path(80f,110f,820f,110f))      // right
            line(*path(820f,260f,80f,260f))      // left
            line(*path(150f,330f,150f,660f))     // down
            line(*path(330f,660f,800f,360f))     // diagonal up
            line(*FloatArray(62){i->val t=(i/2)/30f;if(i%2==0)420f+t*360f else 560f+kotlin.math.sin(t*6.28f)*80f}) // wave
            // A leaning pen, for tilt settings.
            val tilted=FloatArray(84){i->val t=(i/4)/20f;when(i%4){0->260f+t*120f;1->400f+t*230f;2->1f;else->1.3f}}
            s.stroke(0,tilted.copyOfRange(0,4));for(i in 4 until tilted.size step 4)s.stroke(1,tilted.copyOfRange(i,i+4));s.stroke(2,floatArrayOf())
            var done=false;s.command("info"){done=true}
            val end=System.nanoTime()+60_000_000_000;while(!done && System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            shadowOf(Looper.getMainLooper()).idle()
            val out=java.io.File(System.getProperty("inkstone.artifacts"),"brush-import/${file.nameWithoutExtension}-${brush.name.trim().replace(' ','_')}.png");out.parentFile?.mkdirs()
            out.outputStream().use{s.bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        c.pause().stop().destroy()
    }

    /**
     * Writes artifacts/brush-import/compare-*.png for brushes in INKSTONE_BRUSH_DIR: Procreate's own
     * preview (QuickLook/Thumbnail.png) on top, Inkstone's stroke along a similar path underneath,
     * both white on dark. Skipped without INKSTONE_BRUSH_DIR and INKSTONE_COMPARE.
     */
    @Test fun comparesWithProcreatePreviews(){
        val dir=System.getenv("INKSTONE_BRUSH_DIR")?.let{java.io.File(it)}?.takeIf{it.isDirectory}?:return
        val wanted=System.getenv("INKSTONE_COMPARE")?.split(",")?.map{it.trim()}?:return
        RuntimeEnvironment.getApplication().getSharedPreferences("tutorials",0).edit().putBoolean("welcomeSeen",true).commit()
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        for(f in dir.listFiles{x->x.extension=="brushset"}!!.sorted()){
            val zip=java.util.zip.ZipFile(f)
            val read=f.inputStream().use{ProcreateBrushes.read(it)}
            for(brush in read.brushes.filter{b->wanted.any{it.equals(b.name.trim(),true)}}){
                val folder=zip.entries().asSequence().map{it.name}.first{n->n.endsWith("Brush.archive") && ProcreateBrushes.keyedArchive(zip.getInputStream(zip.getEntry(n)).readBytes())["name"].toString().trim()==brush.name.trim()}.removeSuffix("Brush.archive")
                val thumb=zip.getEntry(folder+"QuickLook/Thumbnail.png")?.let{e->zip.getInputStream(e).use{android.graphics.BitmapFactory.decodeStream(it)}}?:continue
                val w=thumb.width;val h=thumb.height
                a.newCanvas(w,h);val s=a.editor!!.session
                s.command("background",org.json.JSONObject().put("color",0xff1b1f2aL))
                val preset=ProcreateBrushes.preset(brush,"Test")
                // Match the stroke's width to the thickest column of Procreate's preview.
                val thick=(0 until w step 4).maxOf{x->(0 until h).count{y->(thumb.getPixel(x,y) ushr 24)>64}}
                s.choose(preset);s.color=0xffffffff.toInt();s.size=thick.toFloat().coerceIn(2f,600f);s.opacity=preset.opacity
                // A gentle rising curve with pressure rising and falling, like Procreate's preview stroke.
                val n=80;val pts=FloatArray(n*4){i->val t=(i/4)/(n-1f);when(i%4){0->w*(.1f+.8f*t);1->h*(.6f-.22f*t+.07f*kotlin.math.sin(t*6.283f));2->(.25f+.75f*kotlin.math.sin(t*3.1416f));else->0f}}
                s.stroke(0,pts.copyOfRange(0,4));for(i in 4 until pts.size step 4)s.stroke(1,pts.copyOfRange(i,i+4));s.stroke(2,floatArrayOf())
                var done=false;s.command("info"){done=true}
                val end=System.nanoTime()+60_000_000_000;while(!done && System.nanoTime()<end){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
                shadowOf(Looper.getMainLooper()).idle()
                val sheet=Bitmap.createBitmap(w,h*2+8,Bitmap.Config.ARGB_8888);val cv=android.graphics.Canvas(sheet)
                cv.drawColor(0xff1b1f2a.toInt());cv.drawBitmap(thumb,0f,0f,null);cv.drawBitmap(s.bitmap,0f,h+8f,null)
                java.io.File(System.getProperty("inkstone.artifacts"),"brush-import/compare-${brush.name.trim().replace(' ','_')}.png").also{it.parentFile?.mkdirs()}.outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            zip.close()
        }
        c.pause().stop().destroy()
    }
}
