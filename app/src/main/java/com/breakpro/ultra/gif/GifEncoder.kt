package com.breakpro.ultra.gif

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Minimal animated GIF89a writer. Fixed 216-colour (6x6x6) palette, no dithering,
 * infinite loop. Logic validated against Pillow's decoder (round-trip test).
 * Usage: start() -> addFrame(bitmap) for each frame -> finish().
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {

    fun start() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width); writeShort(height)
        out.write(0xF7); out.write(0); out.write(0)           // global table, 256 entries
        for (i in 0 until 256) {                               // palette
            if (i < 216) {
                out.write((i / 36) * 51); out.write(((i / 6) % 6) * 51); out.write((i % 6) * 51)
            } else { out.write(0); out.write(0); out.write(0) }
        }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))      // loop forever
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    /** [bmp] must be exactly width x height. delayMs is rounded to 1/100 s. */
    fun addFrame(bmp: Bitmap, delayMs: Int) {
        require(bmp.width == width && bmp.height == height) { "frame size mismatch" }
        val px = IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        val idx = IntArray(px.size) { i ->
            val c = px[i]
            val r = (((c shr 16) and 0xFF) + 25) / 51
            val g = (((c shr 8) and 0xFF) + 25) / 51
            val b = ((c and 0xFF) + 25) / 51
            r * 36 + g * 6 + b
        }
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
        writeShort((delayMs / 10).coerceAtLeast(2))
        out.write(0); out.write(0)
        out.write(0x2C); writeShort(0); writeShort(0); writeShort(width); writeShort(height); out.write(0)
        writeLzw(idx)
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun writeShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun writeLzw(pixels: IntArray) {
        val data = java.io.ByteArrayOutputStream()
        var cur = 0
        var nb = 0
        fun put(code: Int, size: Int) {
            cur = cur or (code shl nb); nb += size
            while (nb >= 8) { data.write(cur and 0xFF); cur = cur ushr 8; nb -= 8 }
        }
        val clear = 256
        val eoi = 257
        var size = 9
        var next = 258
        val dict = HashMap<Int, Int>()
        put(clear, size)
        var prefix = pixels[0]
        for (i in 1 until pixels.size) {
            val c = pixels[i]
            val key = (prefix shl 8) or c
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            put(prefix, size)
            dict[key] = next; next++
            if (next - 1 == (1 shl size) && size < 12) size++
            if (next == 4096) { put(clear, size); dict.clear(); next = 258; size = 9 }
            prefix = c
        }
        put(prefix, size)
        next++
        if (next - 1 == (1 shl size) && size < 12) size++
        put(eoi, size)
        if (nb > 0) data.write(cur and 0xFF)

        val bytes = data.toByteArray()
        out.write(8)                                           // min code size
        var p = 0
        while (p < bytes.size) {
            val n = minOf(255, bytes.size - p)
            out.write(n); out.write(bytes, p, n); p += n
        }
        out.write(0)
    }
}
