package art.inkstone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class Artwork(val id: String, val name: String, val width: Int, val height: Int, val modified: Long, val mode:String="raster",val tags:List<String> = emptyList(),val stackId:String?=null,val created:Long=modified)
data class ArtworkStack(val id:String,val name:String,val created:Long)

class DocumentStore(context: Context) {
    private val galleryPrefs=context.getSharedPreferences("gallery-library",0)
    private val root = File(context.filesDir, "artworks").apply { mkdirs() }
    fun newId() = UUID.randomUUID().toString()
    private fun file(id: String, suffix: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        return File(root, "$id.$suffix")
    }
    @Synchronized fun list(): List<Artwork> = root.listFiles()?.filter { it.extension == "json" && it.name!="library.json" }?.mapNotNull {
        runCatching {
            val j = JSONObject(it.readText())
            Artwork(it.nameWithoutExtension, j.getString("name"), j.getInt("width"), j.getInt("height"), j.getLong("modified"),j.optString("mode","raster"),j.optJSONArray("tags")?.let{a->(0 until a.length()).map{a.getString(it)}}?:emptyList(),if(j.isNull("stackId"))null else j.getString("stackId"),j.optLong("created",j.getLong("modified")))
        }.getOrNull()
    }?.sortedByDescending { it.modified } ?: emptyList()
    @Synchronized fun save(id: String, name: String, bytes: ByteArray, image: Bitmap, documentWidth:Int=image.width, documentHeight:Int=image.height,mode:String="raster") {
        atomic(file(id, "inkstone")) { output -> encode(output, bytes) }
        val scale=minOf(1f,480f/maxOf(image.width,image.height))
        val thumbnail=if(scale<1f)Bitmap.createScaledBitmap(image,(image.width*scale).toInt().coerceAtLeast(1),(image.height*scale).toInt().coerceAtLeast(1),true) else image
        try { atomic(file(id, "png")) { check(thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { if(thumbnail!==image)thumbnail.recycle() }
        val metadata = runCatching{JSONObject(file(id,"json").readText())}.getOrDefault(JSONObject()).apply{
            if(!has("name")){val stack=galleryPrefs.getString("inside",null);if(stack!=null && stacks().any{it.id==stack})put("stackId",stack)}
            if(!has("created"))put("created",optLong("modified",System.currentTimeMillis()))
        }.put("name", name).put("width", documentWidth).put("height", documentHeight)
            .put("mode",mode).put("modified", System.currentTimeMillis()).toString().toByteArray()
        atomic(file(id, "json")) { it.write(metadata) }
    }
    private fun atomic(file: File, write: (java.io.OutputStream) -> Unit) {
        val target = AtomicFile(file); val stream = target.startWrite()
        try {
            // Keep AtomicFile's stream open until its finishWrite fsync.
            val shield = object : java.io.FilterOutputStream(stream) {
                override fun write(bytes:ByteArray,offset:Int,length:Int){out.write(bytes,offset,length)}
                override fun close() { flush() }
            }
            write(shield); target.finishWrite(stream)
        } catch (e: Exception) { target.failWrite(stream); throw e }
    }
    fun read(id: String): ByteArray = file(id, "inkstone").inputStream().use(::decode)
    fun thumbnail(id: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file(id, "png").path, bounds)
        val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 480).coerceAtLeast(1)
        return BitmapFactory.decodeFile(file(id, "png").path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    @Synchronized fun delete(id: String) { listOf("inkstone", "png", "json").forEach { file(id, it).delete() } }
    @Synchronized fun rename(id: String, name: String) {
        val f = file(id, "json"); val j = JSONObject(f.readText()).put("name", name.take(100))
        atomic(f) { it.write(j.toString().toByteArray()) }
    }
    @Synchronized fun duplicate(id: String) {
        val next = newId()
        file(id, "inkstone").copyTo(file(next, "inkstone"))
        file(id, "png").copyTo(file(next, "png"))
        val metadata = JSONObject(file(id, "json").readText())
            .put("name", JSONObject(file(id, "json").readText()).getString("name") + " copy")
            .put("created",System.currentTimeMillis()).put("modified", System.currentTimeMillis()).toString().toByteArray()
        atomic(file(next, "json")) { it.write(metadata) }
    }
    private fun library():JSONObject=runCatching{JSONObject(File(root,"library.json").readText())}.getOrDefault(JSONObject())
    @Synchronized fun stacks():List<ArtworkStack>{val values=library().optJSONArray("stacks")?:JSONArray();return (0 until values.length()).map{values.getJSONObject(it)}.map{ArtworkStack(it.getString("id"),it.getString("name"),it.getLong("created"))}}
    private fun writeStacks(stacks:List<ArtworkStack>){val data=library().put("stacks",JSONArray(stacks.map{JSONObject().put("id",it.id).put("name",it.name).put("created",it.created)}));atomic(File(root,"library.json")){it.write(data.toString().toByteArray())}}
    @Synchronized fun createStack(name:String):String{val id=newId();writeStacks(stacks()+ArtworkStack(id,name.trim().take(100).ifEmpty{"New stack"},System.currentTimeMillis()));return id}
    @Synchronized fun renameStack(id:String,name:String){writeStacks(stacks().map{if(it.id==id)it.copy(name=name.trim().take(100).ifEmpty{"Stack"})else it})}
    @Synchronized fun unstack(id:String){assignStack(list().filter{it.stackId==id}.map{it.id},null);writeStacks(stacks().filter{it.id!=id})}
    @Synchronized fun assignStack(ids:List<String>,stackId:String?){
        require(stackId==null || stacks().any{it.id==stackId}){"Stack not found"}
        for(id in ids){val f=file(id,"json");val data=JSONObject(f.readText()).put("stackId",stackId?:JSONObject.NULL);atomic(f){it.write(data.toString().toByteArray())}}
    }
    @Synchronized fun setTags(ids:List<String>,tags:List<String>){
        val clean=tags.map{it.trim().take(32)}.filter{it.isNotEmpty()}.distinctBy{it.lowercase(java.util.Locale.ROOT)}.take(20)
        for(id in ids){val f=file(id,"json");val data=JSONObject(f.readText()).put("tags",JSONArray(clean));atomic(f){it.write(data.toString().toByteArray())}}
    }
    companion object {
        private val BINARY = "INKBIN1\n".toByteArray(Charsets.US_ASCII)
        fun isBinary(bytes: ByteArray) = bytes.size >= BINARY.size && (BINARY.indices).all { bytes[it] == BINARY[it] }
        /** Binary documents are already tile-compressed; only legacy/vector JSON is gzipped. */
        fun encode(output: java.io.OutputStream, bytes: ByteArray) {
            if (isBinary(bytes)) output.write(bytes) else GZIPOutputStream(output).use { it.write(bytes) }
        }
        /** Layer/document metadata as JSON for any stored format. */
        fun header(bytes: ByteArray): JSONObject {
            if (isBinary(bytes)) {
                val at = BINARY.size; val n = java.nio.ByteBuffer.wrap(bytes, at, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                return JSONObject(String(bytes, at + 4, n, Charsets.UTF_8))
            }
            val plain = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).use { it.readBytes() } else bytes
            return if (plain.size > 2 && plain[0] == 0x1f.toByte() && plain[1] == 0x8b.toByte()) header(plain) else JSONObject(String(plain))
        }
        fun decode(input: InputStream): ByteArray {
            val buffered = if (input.markSupported()) input else java.io.BufferedInputStream(input)
            buffered.mark(2); val b0 = buffered.read(); val b1 = buffered.read(); buffered.reset()
            if (b0 != 0x1f || b1 != 0x8b) return readLimited(buffered)
            return readLimited(GZIPInputStream(buffered))
        }
        private fun readLimited(source: InputStream): ByteArray = source.use { gzip ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(32768); var total = 0
            while (true) {
                val n = gzip.read(chunk); if (n < 0) break
                total += n; require(total <= 128 * 1024 * 1024) { "Document exceeds the 128 MB import limit" }
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        }
    }
}
