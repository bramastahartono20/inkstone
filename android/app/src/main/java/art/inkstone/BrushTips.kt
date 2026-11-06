package art.inkstone

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Grayscale shape and grain images for image brushes (kind 43). White paints, black is clear. */
internal class TipImages(val size:Int,val shape:ByteArray,val grainSize:Int,val grain:ByteArray){
    init{require(size in 1..MAX_SHAPE && shape.size==size*size);require(grainSize in 0..MAX_GRAIN && grain.size==grainSize*grainSize)}
    /** The shape as an alpha mask, for previews. */
    fun shapeBitmap():Bitmap=Bitmap.createBitmap(IntArray(size*size){(shape[it].toInt() and 255) shl 24},size,size,Bitmap.Config.ARGB_8888)
    companion object{
        const val MAX_SHAPE=256
        const val MAX_GRAIN=512
        /** Gray levels of [b], scaled so its longer side is at most [max] and made square. Transparent pixels count as black. */
        fun gray(b:Bitmap,max:Int):Pair<Int,ByteArray>{
            val n=maxOf(b.width,b.height).coerceAtMost(max).coerceAtLeast(1)
            val square=Bitmap.createScaledBitmap(b,n,n,true)
            val px=IntArray(n*n);square.getPixels(px,0,n,0,0,n,n);if(square!==b)square.recycle()
            return n to ByteArray(n*n){i->val c=px[i];val a=c ushr 24;val l=((c shr 16 and 255)*2126+(c shr 8 and 255)*7152+(c and 255)*722)/10000;(l*a/255).toByte()}
        }
    }
}

internal object BrushTips{
    private const val MAGIC=0x494b5450 // "IKTP"
    private var dir:File?=null
    private val cache=object:LinkedHashMap<String,TipImages>(8,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,TipImages>)=size>8}
    fun init(context:Context){if(dir==null)dir=File(context.filesDir,"brush-tips").apply{mkdirs()}}
    private fun file(id:String):File{require(id.matches(Regex("[A-Za-z0-9-]{1,64}")));return File(checkNotNull(dir){"Brush tips are not ready"},"$id.tip")}

    fun save(images:TipImages):String{
        val id=UUID.randomUUID().toString();val tmp=File(file(id).path+".part")
        DataOutputStream(tmp.outputStream().buffered()).use{o->o.writeInt(MAGIC);o.writeInt(1);o.writeInt(images.size);o.writeInt(images.grainSize);o.write(images.shape);o.write(images.grain)}
        check(tmp.renameTo(file(id)));synchronized(cache){cache[id]=images};return id
    }
    fun load(id:String):TipImages?{
        if(id.isEmpty())return null
        synchronized(cache){cache[id]?.let{return it}}
        return runCatching{DataInputStream(file(id).inputStream().buffered()).use{i->
            require(i.readInt()==MAGIC && i.readInt()==1);val n=i.readInt();val g=i.readInt()
            require(n in 1..TipImages.MAX_SHAPE && g in 0..TipImages.MAX_GRAIN)
            val shape=ByteArray(n*n).also{i.readFully(it)};val grain=ByteArray(g*g).also{i.readFully(it)};TipImages(n,shape,g,grain)
        }}.getOrNull()?.also{synchronized(cache){cache[id]=it}}
    }
    fun delete(id:String){if(id.isEmpty())return;synchronized(cache){cache.remove(id)};runCatching{file(id).delete()}}

}

/** An image tip with its stroke settings, as one stroke uses it. */
internal data class TipSettings(val tip:String,val spacing:Float,val follow:Float,val rotation:Float,val jitter:Float,val grainScale:Float,
    val sizeJitter:Float=0f,val opacityJitter:Float=0f,val scatter:Float=0f,val rolling:Boolean=false,val speedOpacity:Float=0f,val dynamics:TipDynamics=TipDynamics()){
    /** Identifies the tip and its settings, so the engine is sent the pixels only when they change. */
    val key get()="$tip:$spacing:$follow:$rotation:$jitter:$grainScale:$sizeJitter:$opacityJitter:$scatter:$rolling:$speedOpacity:$dynamics"
    fun command(withPixels:Boolean):String?{
        val o=JSONObject().put("op","brushImage").put("key",key)
        if(withPixels){
            val t=BrushTips.load(tip)?:return null
            o.put("size",t.size).put("shape",Base64.encodeToString(t.shape,Base64.NO_WRAP))
            if(t.grainSize>0)o.put("grainSize",t.grainSize).put("grain",Base64.encodeToString(t.grain,Base64.NO_WRAP))
            o.put("spacing",spacing.toDouble()).put("follow",follow.toDouble()).put("rotation",rotation.toDouble()).put("jitter",jitter.toDouble()).put("grainScale",grainScale.toDouble())
                .put("sizeJitter",sizeJitter.toDouble()).put("opacityJitter",opacityJitter.toDouble()).put("scatter",scatter.toDouble()).put("rolling",rolling).put("speedOpacity",speedOpacity.toDouble())
            val d=dynamics.json();for(k in d.keys())o.put(k,d.get(k))
        }
        return o.toString()
    }
}

/** Taper, tilt, speed and blend settings of an image tip. Lengths are fractions of 8 × the brush size. */
data class TipDynamics(val taperStart:Float=0f,val taperEnd:Float=0f,val taperSize:Float=0f,val taperOpacity:Float=0f,
    val tiltSize:Float=0f,val tiltOpacity:Float=0f,val tiltAngle:Float=0f,val speedSize:Float=0f,val blend:Int=0,
    /** Pressure curves as x0, y0, x1, y1, …; empty is linear. */
    val sizeCurve:List<Float> = emptyList(),val opacityCurve:List<Float> = emptyList()){
    fun json():JSONObject=JSONObject().put("taperStart",taperStart.toDouble()).put("taperEnd",taperEnd.toDouble()).put("taperSize",taperSize.toDouble()).put("taperOpacity",taperOpacity.toDouble())
        .put("tiltSize",tiltSize.toDouble()).put("tiltOpacity",tiltOpacity.toDouble()).put("tiltAngle",tiltAngle.toDouble()).put("speedSize",speedSize.toDouble()).put("blend",blend)
        .put("sizeCurve",curveJson(sizeCurve)).put("opacityCurve",curveJson(opacityCurve))
    private fun curveJson(c:List<Float>)=org.json.JSONArray((0 until c.size/2).map{org.json.JSONArray().put(c[it*2].toDouble()).put(c[it*2+1].toDouble())})
    companion object{
        val BLEND_NAMES=listOf("Normal","Multiply","Screen","Add")
        private fun curve(a:org.json.JSONArray?):List<Float> = if(a==null)emptyList() else (0 until a.length().coerceAtMost(32)).flatMap{i->a.optJSONArray(i)?.let{p->listOf(p.optDouble(0,0.0).toFloat().coerceIn(0f,1f),p.optDouble(1,0.0).toFloat().coerceIn(0f,1f))}?:emptyList()}
        fun from(j:JSONObject?):TipDynamics{
            if(j==null)return TipDynamics()
            fun n(k:String,min:Float,max:Float)=j.optDouble(k,0.0).toFloat().takeIf{it.isFinite()}?.coerceIn(min,max)?:0f
            return TipDynamics(n("taperStart",0f,1f),n("taperEnd",0f,1f),n("taperSize",0f,1f),n("taperOpacity",0f,1f),n("tiltSize",0f,1f),n("tiltOpacity",0f,1f),n("tiltAngle",0f,.9f),n("speedSize",-1f,1f),j.optInt("blend",0).coerceIn(0,3),curve(j.optJSONArray("sizeCurve")),curve(j.optJSONArray("opacityCurve")))
        }
    }
}
