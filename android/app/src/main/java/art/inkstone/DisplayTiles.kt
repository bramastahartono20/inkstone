package art.inkstone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * The on-screen copy of the artwork, split into small bitmaps.
 *
 * Hardware rendering re-uploads a whole bitmap to the GPU whenever any of its pixels change, so
 * one 3000 × 2000 bitmap costs a 24 MB upload per stroke frame. Tiles limit that to the tiles a
 * patch touches. Each tile keeps a 1 px apron of its neighbours' pixels, so bilinear filtering
 * at tile edges samples real artwork and no seams appear when zoomed or rotated.
 */
internal class DisplayTiles(val width: Int, val height: Int) {
    private val columns = (width + SIZE - 1) / SIZE
    private val rows = (height + SIZE - 1) / SIZE
    private val tiles = Array(columns * rows) { i ->
        val cw = min(SIZE, width - i % columns * SIZE)
        val ch = min(SIZE, height - i / columns * SIZE)
        Bitmap.createBitmap(cw + 2 * APRON, ch + 2 * APRON, Bitmap.Config.ARGB_8888)
    }
    private val src = Rect()
    private val dst = RectF()

    /** Copies a patch (row stride [w]) placed at document position [x], [y] into every tile it reaches. */
    fun update(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val x1 = x + w; val y1 = y + h
        // A tile's apron reaches one pixel into its neighbours, so widen the search by the apron.
        val c0 = max(0, (x - APRON) / SIZE); val c1 = min(columns - 1, (x1 - 1 + APRON) / SIZE)
        val r0 = max(0, (y - APRON) / SIZE); val r1 = min(rows - 1, (y1 - 1 + APRON) / SIZE)
        for (r in r0..r1) for (c in c0..c1) {
            val tile = tiles[r * columns + c]
            val originX = c * SIZE - APRON; val originY = r * SIZE - APRON
            val ix0 = max(x, originX); val iy0 = max(y, originY)
            val ix1 = min(x1, originX + tile.width); val iy1 = min(y1, originY + tile.height)
            if (ix0 >= ix1 || iy0 >= iy1) continue
            tile.setPixels(pixels, (iy0 - y) * w + (ix0 - x), w, ix0 - originX, iy0 - originY, ix1 - ix0, iy1 - iy0)
        }
    }

    /** Draws the visible tiles; [visible] is in document coordinates, or null to draw everything. */
    fun draw(canvas: Canvas, paint: Paint, visible: RectF?) {
        for (r in 0 until rows) for (c in 0 until columns) {
            val tile = tiles[r * columns + c]
            val left = (c * SIZE).toFloat(); val top = (r * SIZE).toFloat()
            val cw = tile.width - 2 * APRON; val ch = tile.height - 2 * APRON
            dst.set(left, top, left + cw, top + ch)
            if (visible != null && !RectF.intersects(visible, dst)) continue
            src.set(APRON, APRON, APRON + cw, APRON + ch)
            canvas.drawBitmap(tile, src, dst, paint)
        }
    }

    private companion object {
        const val SIZE = 256
        const val APRON = 1
    }
}
