package art.inkstone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads Procreate `.brush` and `.brushset` files. Both are zip archives. Each brush is a folder
 * (the archive root for `.brush`) holding `Brush.archive`, a keyed-archive binary property list
 * of settings, and optional `Shape.png` and `Grain.png` images.
 *
 * Procreate does not publish the meaning of its settings. The mapping below follows what other
 * importers have observed and is approximate.
 */
internal object ProcreateBrushes{
    private const val MAX_ENTRY=64L*1024*1024
    private const val MAX_TOTAL=256L*1024*1024
    private const val MAX_BRUSHES=200

    class Imported(val name:String,val settings:Map<String,Any?>,val shape:Bitmap?,val grain:Bitmap?,val grainSourceSize:Int)
    class Result(val setName:String?,val brushes:List<Imported>)

    fun read(input:InputStream):Result{
        val files=HashMap<String,ByteArray>();var total=0L
        ZipInputStream(input.buffered()).use{zip->
            while(true){
                val entry=zip.nextEntry?:break
                if(entry.isDirectory)continue
                val name=entry.name.replace('\\','/')
                val leaf=name.substringAfterLast('/')
                if(leaf !in listOf("Brush.archive","Shape.png","Grain.png","brushset.plist") || name.contains("__MACOSX") || name.split('/').any{it==".."})continue
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
                while(true){val n=zip.read(buffer);if(n<0)break;out.write(buffer,0,n);total+=n
                    require(out.size()<=MAX_ENTRY && total<=MAX_TOTAL){"Brush file is too large"}}
                files[name]=out.toByteArray()
            }
        }
        val folders=files.keys.filter{it.endsWith("Brush.archive")}.map{it.removeSuffix("Brush.archive")}.sorted()
        require(folders.isNotEmpty()){"No Procreate brushes found in this file"}
        require(folders.size<=MAX_BRUSHES){"A brush set can hold at most $MAX_BRUSHES brushes here"}
        val setName=files["brushset.plist"]?.let{runCatching{setName(it)}.getOrNull()}
        val order=files["brushset.plist"]?.let{runCatching{setOrder(it)}.getOrNull()}.orEmpty()
        val sorted=folders.sortedBy{f->order.indexOf(f.trimEnd('/')).let{if(it<0)Int.MAX_VALUE else it}}
        val brushes=sorted.mapNotNull{folder->runCatching{
            val settings=keyedArchive(checkNotNull(files[folder+"Brush.archive"]))
            val grainBytes=files[folder+"Grain.png"]
            Imported((settings["name"] as? String)?.trim()?.takeIf{it.isNotEmpty()}?:"Imported brush",settings,
                files[folder+"Shape.png"]?.let{decode(it,TipImages.MAX_SHAPE*2)},grainBytes?.let{decode(it,TipImages.MAX_GRAIN*2)},
                grainBytes?.let{bounds(it)}?:0)
        }.getOrNull()}
        require(brushes.isNotEmpty()){"These brushes could not be read"}
        return Result(setName,brushes)
    }

    private fun bounds(bytes:ByteArray):Int{val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,o);return maxOf(o.outWidth,o.outHeight)}
    private fun decode(bytes:ByteArray,max:Int):Bitmap?{
        val size=bounds(bytes);if(size<=0 || size>8192)return null
        var sample=1;while(size/sample>max*2)sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})
    }

    private fun num(v:Any?,default:Float)=((v as? Number)?.toFloat()?:(v as? Boolean)?.let{if(it)1f else 0f})?.takeIf{it.isFinite()}?:default

    /** Builds an Inkstone image brush from a Procreate brush, saving its tip images. */
    fun preset(b:Imported,group:String):BrushPreset{
        val s=b.settings
        fun invert(a:ByteArray)=ByteArray(a.size){(255-(a[it].toInt() and 255)).toByte()}
        // A brush may name an image built into Procreate instead of carrying one. Those images are not
        // in the file, so Inkstone's own closest tip or grain stands in (see StockTips).
        val stockShape=if(b.shape==null)StockTips.shape(s["bundledShapePath"] as? String) else null
        var (size,shape)=b.shape?.let{TipImages.gray(it,TipImages.MAX_SHAPE)}?:stockShape?.let{StockTips.SHAPE to it}?:(64 to disk(64))
        if(b.shape!=null && s["shapeInverted"]==true)shape=invert(shape)
        if(shape.all{it.toInt()==0}){size=64;shape=disk(64)}
        val stockGrain=if(b.grain==null)StockTips.grain(s["bundledGrainPath"] as? String) else null
        var (grainSize,grain)=b.grain?.let{TipImages.gray(it,TipImages.MAX_GRAIN)}?:stockGrain?.let{StockTips.GRAIN to it}?:(0 to ByteArray(0))
        val grainSource=if(stockGrain!=null)StockTips.GRAIN else b.grainSourceSize
        // Inkstone's stand-in grains are drawn as they should look, so only grains from the file are inverted.
        if(b.grain!=null && grainSize>0 && (s["textureInverted"]==true || s["grainInverted"]==true))grain=invert(grain)
        if(grainSize>0 && grain.all{(it.toInt() and 255)==255}){grainSize=0;grain=ByteArray(0)}
        val tip=BrushTips.save(TipImages(size,shape,grainSize,grain))
        val brushSize=(num(s["paintSize"],.5f)*num(s["maxSize"],1f)*100f).coerceIn(2f,400f)
        val follow=num(s["shapeRotation"],0f).let{if(it in -1f..1f)it else 0f}
        val rolling=grainSize>0 && num(s["textureMovement"],0f)>=.5f
        return BrushPreset(b.name.take(80),group,IMAGE_TIP_KIND,brushSize,
            // Light pressure reaches Procreate's minimum size or opacity, so the pressure range spans max down to min.
            pressureSize=(num(s["dynamicsPressureSize"],.5f)*(1f-num(s["minSize"],0f)/num(s["maxSize"],1f).coerceAtLeast(.0001f))).coerceIn(0f,1f),
            pressureOpacity=(num(s["dynamicsPressureOpacity"],0f)*(1f-num(s["minOpacity"],0f)/num(s["maxOpacity"],1f).coerceAtLeast(.0001f))).coerceIn(0f,1f),
            // Procreate's colour mixing becomes Paint Mixing strength; it applies when Paint Mixing is on.
            wetMix=num(s["dynamicsMix"],0f).coerceIn(0f,1f),tipSpeedOpacity=num(s["dynamicsSpeedOpacity"],0f).coerceIn(0f,1f),
            opacity=num(s["maxOpacity"],1f).coerceIn(.01f,1f),stabilization=(num(s["streamLine"],.12f)*.95f).coerceIn(0f,.95f),
            tip=tip,tipSpacing=num(s["plotSpacing"],.1f).coerceIn(.02f,4f),tipFollow=follow,
            // Procreate's shape "Scatter" turns each dab randomly; its "Jitter" moves dabs off the stroke.
            tipJitter=num(s["shapeScatter"],0f).coerceIn(0f,1f),
            tipSizeJitter=num(s["dynamicsJitterSize"],0f).coerceIn(0f,1f),tipOpacityJitter=num(s["dynamicsJitterOpacity"],0f).coerceIn(0f,1f),
            tipScatter=num(s["plotJitter"],0f).coerceIn(0f,3f),
            // "Moving" grain (textureMovement near 1) rolls along the stroke; its tile then spans about
            // half of textureScale brush sizes. Otherwise one source texel covers one canvas pixel.
            grainRolling=rolling,
            tipDynamics=dynamics(s),
            grainScale=when{grainSize==0->1f;rolling->(num(s["textureScale"],2f)/2f).coerceIn(.25f,4f);else->(grainSource.toFloat()/grainSize).coerceIn(.25f,16f)})
    }
    /** Taper, tilt, speed and blend. Inkstone is drawn with a pen, so Procreate's pencil taper values win when set. */
    private fun dynamics(s:Map<String,Any?>):TipDynamics{
        val pencil=listOf("pencilTaperStartLength","pencilTaperEndLength","pencilTaperSize","pencilTaperOpacity").any{num(s[it],0f)!=0f}
        fun taper(k:String)=num(s[if(pencil)"pencilTaper$k" else "taper$k"],0f).coerceIn(0f,1f)
        val blend=(s["blendMode"] as? Number)?.toInt()?.takeIf{it in 0..3}?:0
        return TipDynamics(taper("StartLength"),taper("EndLength"),taper("Size"),taper("Opacity"),
            num(s["dynamicsTiltSize"],0f).coerceIn(0f,1f),num(s["dynamicsTiltOpacity"],0f).coerceIn(0f,1f),num(s["dynamicsTiltAngle"],0f).coerceIn(0f,.9f),
            num(s["dynamicsSpeedSize"],0f).coerceIn(-1f,1f),blend,curve(s["dynamicsPressureSizeCurve"]),curve(s["dynamicsPressureOpacityCurve"]))
    }
    /** A pressure curve's points, read from strings like "{0.45, 0.67}". */
    private fun curve(v:Any?):List<Float>{
        val points=((v as? Map<*,*>)?.get("points") as? List<*>)?:return emptyList()
        return points.take(32).flatMap{p->Regex("[-0-9.eE]+").findAll(p.toString()).map{it.value.toFloatOrNull()?:0f}.take(2).toList().takeIf{it.size==2}?.map{it.coerceIn(0f,1f)}?:emptyList()}
    }
    private fun disk(n:Int)=ByteArray(n*n){i->val x=i%n+.5f-n/2f;val y=i/n+.5f-n/2f;val d=kotlin.math.sqrt(x*x+y*y)/(n/2f);((1f-d)*n/2f+.5f).coerceIn(0f,1f).let{(it*255).toInt().toByte()}}

    private fun plist(bytes:ByteArray):Any?=if(bytes.size>=8 && String(bytes,0,8,Charsets.US_ASCII)=="bplist00")BinaryPlist(bytes).root() else null
    private fun setName(bytes:ByteArray):String?{
        val root=plist(bytes)?:return Regex("<key>name</key>\\s*<string>([^<]{1,80})</string>").find(String(bytes,Charsets.UTF_8))?.groupValues?.get(1)
        return ((root as? Map<*,*>)?.get("name") as? String)?.take(60)
    }
    private fun setOrder(bytes:ByteArray):List<String>{
        val root=plist(bytes)
        if(root!=null)return ((root as? Map<*,*>)?.get("brushes") as? List<*>)?.filterIsInstance<String>().orEmpty()
        val text=String(bytes,Charsets.UTF_8);val array=Regex("<key>brushes</key>\\s*<array>(.*?)</array>",RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?:return emptyList()
        return Regex("<string>([^<]+)</string>").findAll(array).map{it.groupValues[1]}.toList()
    }

    /** Resolves an NSKeyedArchiver plist to the root object's fields. UIDs are followed, one level deep for values. */
    fun keyedArchive(bytes:ByteArray):Map<String,Any?>{
        val root=plist(bytes) as? Map<*,*> ?: error("Brush settings are not a property list")
        val objects=root["\$objects"] as? List<*> ?: error("Brush settings are not a keyed archive")
        val top=(root["\$top"] as? Map<*,*>)?.get("root") as? BinaryPlist.Uid ?: error("Brush settings have no root")
        val brush=objects.getOrNull(top.value) as? Map<*,*> ?: error("Brush settings root is missing")
        // Follows references a few levels down: strings, arrays (NS.objects) and plain objects such as pressure curves.
        fun resolve(v:Any?,depth:Int=0):Any?{
            val o=if(v is BinaryPlist.Uid)objects.getOrNull(v.value) else v
            if(o=="\$null")return null
            if(o is Map<*,*>){
                (o["NS.string"])?.let{return it as? String}
                if(depth>=3)return o
                (o["NS.objects"] as? List<*>)?.let{list->return list.take(256).map{resolve(it,depth+1)}}
                return o.entries.filter{it.key!="\$class"}.associate{(k,x)->k.toString() to resolve(x,depth+1)}
            }
            return o
        }
        return brush.entries.associate{(k,v)->k.toString() to resolve(v)}
    }
}

internal const val IMAGE_TIP_KIND=43

/** A reader for Apple's binary property list format (bplist00). */
internal class BinaryPlist(private val b:ByteArray){
    data class Uid(val value:Int)
    private val offsetSize:Int;private val refSize:Int;private val count:Int;private val top:Int;private val table:Int
    private var budget=100_000
    init{
        require(b.size>=40){"Property list is too short"}
        val t=b.size-32
        offsetSize=b[t+6].toInt() and 255;refSize=b[t+7].toInt() and 255
        count=long(t+8,8).toInt();top=long(t+16,8).toInt();table=long(t+24,8).toInt()
        require(offsetSize in 1..8 && refSize in 1..8 && count in 1..1_000_000 && top in 0 until count && table>=8 && table.toLong()+count.toLong()*offsetSize<=t){"Property list is damaged"}
    }
    private fun long(at:Int,n:Int):Long{require(at>=0 && at+n<=b.size);var v=0L;for(i in 0 until n)v=(v shl 8) or (b[at+i].toLong() and 255);return v}
    fun root():Any?=read(top,0)
    private fun offset(i:Int):Int{require(i in 0 until count){"Property list reference is out of range"};return long(table+i*offsetSize,offsetSize).toInt().also{require(it in 8 until b.size)}}
    private fun length(at:Int,low:Int):Pair<Int,Int>{
        if(low!=15)return low to at+1
        val marker=b[at+1].toInt() and 255;require(marker shr 4==1)
        val n=1 shl (marker and 15);return long(at+2,n).toInt().also{require(it in 0..b.size)} to at+2+n
    }
    private fun read(ref:Int,depth:Int):Any?{
        require(depth<24 && --budget>0){"Property list is too complex"}
        val at=offset(ref);val marker=b[at].toInt() and 255;val low=marker and 15
        return when(marker shr 4){
            0->when(low){8->false;9->true;else->null}
            1->{val n=1 shl low;val v=long(at+1,n);if(n==8)v else v.toInt().toLong()}
            2->if(low==2)java.lang.Float.intBitsToFloat(long(at+1,4).toInt()).toDouble() else java.lang.Double.longBitsToDouble(long(at+1,8))
            3->java.lang.Double.longBitsToDouble(long(at+1,8))
            4->{val(n,s)=length(at,low);require(s+n<=b.size);b.copyOfRange(s,s+n)}
            5->{val(n,s)=length(at,low);require(s+n<=b.size);String(b,s,n,Charsets.US_ASCII)}
            6->{val(n,s)=length(at,low);require(s+n*2<=b.size);String(b,s,n*2,Charsets.UTF_16BE)}
            8->Uid(long(at+1,low+1).toInt())
            10,12->{val(n,s)=length(at,low);require(s.toLong()+n.toLong()*refSize<=b.size);(0 until n).map{read(long(s+it*refSize,refSize).toInt(),depth+1)}}
            13->{val(n,s)=length(at,low);require(s.toLong()+2L*n*refSize<=b.size)
                (0 until n).associate{read(long(s+it*refSize,refSize).toInt(),depth+1).toString() to read(long(s+(n+it)*refSize,refSize).toInt(),depth+1)}}
            else->error("Unsupported property list value")
        }
    }
}
