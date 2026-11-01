package art.inkstone

import java.nio.ByteBuffer

object NativeBridge {
    init { System.loadLibrary("inkstone") }
    external fun create(width: Int, height: Int): Long
    external fun destroy(handle: Long)
    external fun command(handle: Long, json: String): String
    external fun stroke(handle: Long, phase: Int, samples: FloatArray, kind: Int, color: Int,
        size: Float, opacity: Float, flow: Float, stabilization: Float, pressureSize: Float, pressureOpacity: Float, fadeLength:Float=0f, sampleStride:Int=4)
    external fun render(handle: Long, pixels: ByteBuffer, background: Boolean): Boolean
    external fun renderPatch(handle: Long, force: Boolean): IntArray?
    /** Fills [out] with the interior patch (sized by [patchCapacity]); returns [x,y,w,h]+exterior tiles, or [-1,needed] if [out] is too small. */
    external fun renderPatchInto(handle: Long, force: Boolean, out: IntArray): IntArray?
    /** Pixel count the next [renderPatchInto] needs for its interior patch. */
    external fun patchCapacity(handle: Long, force: Boolean): Int
    external fun preview(handle: Long): IntArray?
    external fun writePsd(handle: Long, path: String): String
    external fun save(handle: Long): ByteArray?
    external fun load(handle: Long, bytes: ByteArray): String
    external fun importPsd(handle:Long,path:String):String
    external fun importPixels(handle: Long, width: Int, height: Int, pixels: IntArray): String
    external fun transformSnapshot(handle:Long):IntArray?
    external fun thumbnails(handle: Long, size: Int): IntArray?
}
