package art.inkstone

import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.jcodec.api.SequenceEncoder
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import java.io.File
import kotlin.math.*

internal object TimelapseVideo {
    const val FPS=24
    internal fun sourceIndex(frame:Int,sourceCount:Int,seconds:Int):Int {
        require(sourceCount>0 && seconds in listOf(30,60,120))
        val start=FPS/2;val end=seconds*FPS-FPS-1
        return (((frame-start).toDouble()/(end-start)).coerceIn(0.0,1.0)*(sourceCount-1)).roundToInt()
    }
    fun encode(files:List<File>,target:File,softwareOnly:Boolean=false,durationSeconds:Int=30,progress:(Int)->Unit={}):String {
        require(durationSeconds in listOf(30,60,120)){"Choose 30 seconds, 1 minute or 2 minutes"}
        require(files.isNotEmpty()){ "No recorded frames yet" }
        if(!softwareOnly){
            try{hardware(files,target,durationSeconds,progress);return "Android H.264"}catch(e:Exception){target.delete();if(e is java.util.concurrent.CancellationException || e is InterruptedException)throw e}
        }
        software(files,target,durationSeconds,progress);return "Software H.264"
    }
    private class Frames(val files:List<File>,val seconds:Int):AutoCloseable {
        private val first=BitmapFactory.decodeFile(files.first().path)?:error("Cannot read recorded frame")
        val width=(first.width/2*2).coerceAtLeast(16);val height=(first.height/2*2).coerceAtLeast(16)
        private val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        private val canvas=Canvas(bitmap);private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val pixels=IntArray(width*height)
        val count=seconds*FPS
        private var previous:File?=null
        var version=0;private set
        init{first.recycle()}
        fun load(i:Int):IntArray {
            check(!Thread.currentThread().isInterrupted){"Export cancelled"}
            val file=files[sourceIndex(i,files.size,seconds)]
            if(file!=previous){
                val image=BitmapFactory.decodeFile(file.path)?:error("Cannot read recorded frame")
                try{canvas.drawColor(Color.WHITE);val scale=min(width.toFloat()/image.width,height.toFloat()/image.height);val w=image.width*scale;val h=image.height*scale
                    canvas.drawBitmap(image,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint)
                    bitmap.getPixels(pixels,0,width,0,0,width,height);previous=file;version++
                }finally{image.recycle()}
            }
            return pixels
        }
        override fun close(){bitmap.recycle()}
    }
    private fun software(files:List<File>,target:File,seconds:Int,progress:(Int)->Unit){
        Frames(files,seconds).use{frames->
            val encoder=SequenceEncoder.createSequenceEncoder(target,FPS)
            var finished=false
            try{
                val picture=Picture.create(frames.width,frames.height,ColorSpace.RGB);val rgb=picture.getPlaneData(0)
                var version=-1
                for(i in 0 until frames.count){
                    val pixels=frames.load(i)
                    if(version!=frames.version){var at=0;for(c in pixels){rgb[at++]=((c ushr 16 and 255)-128).toByte();rgb[at++]=((c ushr 8 and 255)-128).toByte();rgb[at++]=((c and 255)-128).toByte()};version=frames.version}
                    encoder.encodeNativeFrame(picture);if(i%FPS==0 || i==frames.count-1)progress((i+1)*99/frames.count)
                }
                encoder.finish();finished=true;progress(100)
            }finally{if(!finished){runCatching{encoder.finish()};target.delete()}}
        }
    }
    internal fun yuv(pixels:IntArray,width:Int,height:Int,out:Array<ByteArray> = arrayOf(ByteArray(width*height),ByteArray(width*height/4),ByteArray(width*height/4))):Array<ByteArray>{
        for(y in 0 until height){for(x in 0 until width){
            val c=pixels[y*width+x];val r=c ushr 16 and 255;val g=c ushr 8 and 255;val b=c and 255
            out[0][y*width+x]=(((66*r+129*g+25*b+128) shr 8)+16).coerceIn(0,255).toByte()
            if(x%2==0 && y%2==0){var red=0;var green=0;var blue=0
                for(dy in 0..1)for(dx in 0..1){val p=pixels[(y+dy)*width+x+dx];red+=p ushr 16 and 255;green+=p ushr 8 and 255;blue+=p and 255}
                red/=4;green/=4;blue/=4;val i=y/2*(width/2)+x/2
                out[1][i]=(((-38*red-74*green+112*blue+128) shr 8)+128).coerceIn(0,255).toByte()
                out[2][i]=(((112*red-94*green-18*blue+128) shr 8)+128).coerceIn(0,255).toByte()
            }
        }}
        return out
    }
    private fun hardware(files:List<File>,target:File,seconds:Int,progress:(Int)->Unit){
        Frames(files,seconds).use{frames->
            val codec=MediaCodec.createEncoderByType("video/avc")
            var muxer:MediaMuxer?=null;var started=false;var track=-1;var codecStarted=false
            try{
                val supported=codec.codecInfo.getCapabilitiesForType("video/avc").colorFormats.toSet()
                val formatId=listOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar).firstOrNull{it in supported}?:error("No YUV encoder")
                val format=MediaFormat.createVideoFormat("video/avc",frames.width,frames.height).apply{
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,formatId);setInteger(MediaFormat.KEY_BIT_RATE,max(600_000,frames.width*frames.height*4));setInteger(MediaFormat.KEY_FRAME_RATE,FPS);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                }
                codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();codecStarted=true
                val movie=MediaMuxer(target.path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=movie
                val info=MediaCodec.BufferInfo();var done=false
                fun drain(wait:Boolean){
                    while(true){val index=codec.dequeueOutputBuffer(info,if(wait)10000L else 0L)
                        if(index==MediaCodec.INFO_TRY_AGAIN_LATER)return
                        if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){check(!started);track=movie.addTrack(codec.outputFormat);movie.start();started=true}
                        else if(index>=0){
                            val buffer=checkNotNull(codec.getOutputBuffer(index))
                            if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0)info.size=0
                            if(info.size>0){check(started);buffer.position(info.offset);buffer.limit(info.offset+info.size);movie.writeSampleData(track,buffer,info)}
                            done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;codec.releaseOutputBuffer(index,false)
                            if(done)return
                        }
                    }
                }
                val planes=arrayOf(ByteArray(frames.width*frames.height),ByteArray(frames.width*frames.height/4),ByteArray(frames.width*frames.height/4));var version=-1
                for(i in 0..frames.count){
                    var slot=-1;val deadline=System.nanoTime()+10_000_000_000L
                    while(slot<0){slot=codec.dequeueInputBuffer(10000);if(slot<0)drain(true);check(System.nanoTime()<deadline){"Video encoder timed out"}}
                    if(i==frames.count){codec.queueInputBuffer(slot,0,0,i*1_000_000L/FPS,MediaCodec.BUFFER_FLAG_END_OF_STREAM);break}
                    val pixels=frames.load(i);if(version!=frames.version){yuv(pixels,frames.width,frames.height,planes);version=frames.version}
                    val image=codec.getInputImage(slot)
                    if(image!=null){try{for(n in 0..2){val p=image.planes[n];val w=if(n==0)frames.width else frames.width/2;val h=if(n==0)frames.height else frames.height/2
                        for(y in 0 until h)for(x in 0 until w)p.buffer.put(y*p.rowStride+x*p.pixelStride,planes[n][y*w+x])
                    }}finally{image.close()}}
                    else{val input=checkNotNull(codec.getInputBuffer(slot));input.clear();input.put(planes[0])
                        when(formatId){MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar->{input.put(planes[1]);input.put(planes[2])}
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar->for(k in planes[1].indices){input.put(planes[1][k]);input.put(planes[2][k])}
                            else->error("Encoder does not expose YUV planes")}
                    }
                    codec.queueInputBuffer(slot,0,frames.width*frames.height*3/2,i*1_000_000L/FPS,0);drain(false)
                    if(i%FPS==0 || i==frames.count-1)progress((i+1)*99/frames.count)
                }
                val deadline=System.nanoTime()+30_000_000_000L
                while(!done){drain(true);check(System.nanoTime()<deadline){"Video encoder did not finish"}}
                check(started);movie.stop();started=false;progress(100)
            }finally{if(started)runCatching{muxer?.stop()};muxer?.release();if(codecStarted)runCatching{codec.stop()};codec.release()}
        }
    }
}
