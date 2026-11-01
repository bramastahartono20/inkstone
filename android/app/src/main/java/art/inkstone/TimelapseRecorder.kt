package art.inkstone

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

internal class TimelapseRecorder(context:Context,private val updated:()->Unit={},private val error:(String)->Unit={},private val encode:(List<File>,File,Int,(Int)->Unit)->String={files,target,seconds,progress->TimelapseVideo.encode(files,target,durationSeconds=seconds,progress=progress)}){
    private val app=context.applicationContext
    private val root=File(app.filesDir,"timelapse")
    private val prefs=app.getSharedPreferences("timelapse",0)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private val pending=AtomicBoolean(false)
    private val lastCapture=mutableMapOf<String,Long>()
    private data class Recording(var count:Int,var next:Int,var bytes:Long,var digest:String)
    private val recordings=mutableMapOf<String,Recording>()
    private fun recording(id:String)=recordings.getOrPut(id){val files=frames(id);Recording(files.size,(files.lastOrNull()?.nameWithoutExtension?.toIntOrNull()?:-1)+1,files.sumOf{it.length()},runCatching{File(folder(id),"last.sha256").readText()}.getOrDefault(""))}
    private val counts=java.util.concurrent.ConcurrentHashMap<String,Int>()
    @Volatile private var closed=false
    @Volatile var exporting=false;private set
    @Volatile var progress=0;private set
    private fun folder(id:String):File{require(id.matches(Regex("[A-Za-z0-9-]{1,80}")));return File(root,id)}
    internal fun frames(id:String):List<File> = folder(id).listFiles()?.filter{it.name.matches(Regex("[0-9]{8}\\.jpg"))}?.sortedBy{it.name}?:emptyList()
    fun prepare(id:String){folder(id);if(!prefs.contains(id))prefs.edit().putBoolean(id,prefs.getBoolean("defaultEnabled",true)).apply()}
    fun duration(id:String)=prefs.getInt("duration:$id",30).takeIf{it in listOf(30,60,120)}?:30
    fun setDuration(id:String,seconds:Int){require(seconds in listOf(30,60,120));prefs.edit().putInt("duration:$id",seconds).apply()}
    fun enabled(id:String)=prefs.getBoolean(id,prefs.getBoolean("defaultEnabled",true))
    fun setEnabled(id:String,value:Boolean){prefs.edit().putBoolean(id,value).apply();lastCapture.remove(id);updated()}
    fun count(id:String)=counts.getOrPut(id){frames(id).size}
    fun shouldCapture(id:String)=!closed && enabled(id) && !pending.get() && SystemClock.uptimeMillis()-(lastCapture[id]?:Long.MIN_VALUE/2)>=83
    private fun copy(source:Bitmap):Bitmap{
        val scale=min(1f,960f/max(source.width,source.height))
        val w=max(16,(source.width*scale).roundToInt()/2*2);val h=max(16,(source.height*scale).roundToInt()/2*2)
        return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{image->val c=Canvas(image);c.drawColor(Color.WHITE);val fit=min(w.toFloat()/source.width,h.toFloat()/source.height);val cw=source.width*fit;val ch=source.height*fit;c.drawBitmap(source,null,RectF((w-cw)/2,(h-ch)/2,(w+cw)/2,(h+ch)/2),Paint(Paint.FILTER_BITMAP_FLAG))}
    }
    fun capture(id:String,source:Bitmap,force:Boolean=false){
        if(closed || !enabled(id) || source.isRecycled)return
        val now=SystemClock.uptimeMillis();if(!force && now-(lastCapture[id]?:Long.MIN_VALUE/2)<83)return
        if(!pending.compareAndSet(false,true))return
        val image=runCatching{copy(source)}.getOrElse{pending.set(false);return}
        lastCapture[id]=now
        worker.execute{try{append(id,image)}catch(e:Exception){prefs.edit().putBoolean(id,false).apply();main.post{if(!closed)error("Time-lapse paused: ${e.message}")}}finally{image.recycle();pending.set(false);main.post{if(!closed)updated()}}}
    }
    private fun append(id:String,image:Bitmap){
        val state=recording(id)
        val bytes=java.io.ByteArrayOutputStream().also{check(image.compress(Bitmap.CompressFormat.JPEG,90,it))}.toByteArray()
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val hash=File(folder(id),"last.sha256")
        if(state.count>0 && state.digest==digest)return
        check(state.count<3600 && state.bytes+bytes.size<=256L*1024*1024){"Recording storage is full. Export or clear this recording to continue."}
        val index=state.next
        val directory=folder(id).apply{check(mkdirs()||isDirectory)}
        val file=AtomicFile(File(directory,String.format(java.util.Locale.ROOT,"%08d.jpg",index)))
        val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream);state.count++;state.next++;state.bytes+=bytes.size;state.digest=digest;counts[id]=state.count;runCatching{hash.writeText(digest)}}catch(e:Exception){file.failWrite(stream);throw e}
    }
    fun clear(id:String,then:()->Unit){
        if(closed || exporting)return
        worker.execute{val result=runCatching{check(folder(id).deleteRecursively() || !folder(id).exists()){ "Could not clear recording" };counts[id]=0;recordings.remove(id)}
            main.post{if(!closed){result.onSuccess{lastCapture.remove(id);updated();then()}.onFailure{error(it.message?:"Could not clear recording")}}}}
    }
    fun export(id:String,current:Bitmap,durationSeconds:Int=30,then:(File?,String?)->Unit){
        require(durationSeconds in listOf(30,60,120))
        if(closed || exporting)return
        exporting=true;progress=0;updated()
        val image=runCatching{copy(current)}.getOrElse{exporting=false;then(null,it.message?:"Cannot prepare final frame");return}
        worker.execute{
            var target:File?=null
            val result=runCatching{
                val existing=frames(id)
                if(enabled(id) && existing.size<3600 && existing.sumOf{it.length()}<256L*1024*1024)append(id,image)
                val files=frames(id);require(files.isNotEmpty()){"No recorded frames yet. Enable recording and draw first."}
                target=File.createTempFile("inkstone-timelapse-",".mp4",app.cacheDir)
                encode(files,target!!,durationSeconds){amount->if(closed)throw java.util.concurrent.CancellationException("Export cancelled");progress=amount;main.post{if(!closed)updated()}}
                target!!
            }
            image.recycle()
            if(result.isFailure)target?.delete()
            main.post{exporting=false;if(closed)target?.delete()else{updated();then(result.getOrNull(),result.exceptionOrNull()?.message)}}
        }
    }
    fun close(){closed=true;worker.shutdown()}
    internal val idle get()=!pending.get() && !exporting
    internal val fullyClosed get()=worker.isTerminated
}
