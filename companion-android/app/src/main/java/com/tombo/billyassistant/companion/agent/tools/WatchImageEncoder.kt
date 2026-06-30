package com.tombo.billyassistant.companion.agent.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect

internal fun Bitmap.toWatchImage(spec: WatchMediaSpec, minEdge: Int = 32): WatchImage {
    var best: WatchImage? = null
    WATCH_SCALE_STEPS.forEach { scaleStep ->
        val maxWidth = maxOf(minEdge, (spec.maxWidth * scaleStep).toInt())
        val maxHeight = maxOf(minEdge, (spec.maxHeight * scaleStep).toInt())
        val preview = resizeContainForWatch(maxWidth, maxHeight)
        try {
            val data = preview.toPebbleBitmapData(spec.pbiDepth)
            val watchImage = WatchImage(
                width = preview.width,
                height = preview.height,
                data = data,
            )
            best = watchImage
            if (data.size <= spec.maxBytes) {
                return watchImage
            }
        } finally {
            preview.recycle()
        }
    }
    return best ?: error("Bitmap could not be resized for the watch.")
}

private fun Bitmap.resizeContainForWatch(maxWidth: Int, maxHeight: Int): Bitmap {
    val scale = minOf(maxWidth.toFloat() / width.toFloat(), maxHeight.toFloat() / height.toFloat())
    val scaledWidth = maxOf(1, (width * scale).toInt())
    val scaledHeight = maxOf(1, (height * scale).toInt())
    val output = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.08f, 0f, 0f, 0f, -4f,
                    0f, 1.08f, 0f, 0f, -4f,
                    0f, 0f, 1.08f, 0f, -4f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    canvas.drawBitmap(this, null, Rect(0, 0, scaledWidth, scaledHeight), paint)
    return output
}

private fun Bitmap.toPebbleBitmapData(pbiDepth: Int): ByteArray {
    return if (pbiDepth == 4) {
        toPebblePbi4()
    } else {
        toPebblePbi2()
    }
}

private fun Bitmap.toPebblePbi2(): ByteArray {
    val rowSize = (width + 3) / 4
    val dataSize = rowSize * height
    val colors = ByteArray(width * height)
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    pixels.forEachIndexed { index, pixel ->
        colors[index] = pixel.toPebbleArgb8().toByte()
    }
    val palette = intArrayOf(0xff, 0xc0, 0xea, 0xd5)
    val output = ByteArray(12 + dataSize + palette.size)
    output.writeUint16Le(0, rowSize)
    output.writeUint16Le(2, (1 shl 12) or (3 shl 1))
    output.writeUint16Le(4, 0)
    output.writeUint16Le(6, 0)
    output.writeUint16Le(8, width)
    output.writeUint16Le(10, height)
    for (y in 0 until height) {
        for (x in 0 until width step 4) {
            var packed = 0
            for (i in 0 until 4) {
                val paletteIndex = if (x + i < width) {
                    colors[(y * width) + x + i].toUnsignedInt().nearestPaletteIndex(palette)
                } else {
                    0
                }
                packed = packed or ((paletteIndex and 0x03) shl (6 - (i * 2)))
            }
            output[12 + (y * rowSize) + (x / 4)] = packed.toByte()
        }
    }
    palette.forEachIndexed { index, value ->
        output[12 + dataSize + index] = value.toByte()
    }
    return output
}

private fun Bitmap.toPebblePbi4(): ByteArray {
    val rowSize = (width + 1) / 2
    val dataSize = rowSize * height
    val colors = ByteArray(width * height)
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    pixels.forEachIndexed { index, pixel ->
        colors[index] = pixel.toPebbleArgb8().toByte()
    }
    val palette = colors.toPebblePalette16()
    val output = ByteArray(12 + dataSize + palette.size)
    output.writeUint16Le(0, rowSize)
    output.writeUint16Le(2, (1 shl 12) or (4 shl 1))
    output.writeUint16Le(4, 0)
    output.writeUint16Le(6, 0)
    output.writeUint16Le(8, width)
    output.writeUint16Le(10, height)
    for (y in 0 until height) {
        for (x in 0 until width step 2) {
            val high = colors[(y * width) + x].toUnsignedInt().nearestPaletteIndex(palette)
            val low = if (x + 1 < width) {
                colors[(y * width) + x + 1].toUnsignedInt().nearestPaletteIndex(palette)
            } else {
                0
            }
            output[12 + (y * rowSize) + (x / 2)] = ((high shl 4) or low).toByte()
        }
    }
    palette.forEachIndexed { index, value ->
        output[12 + dataSize + index] = value.toByte()
    }
    return output
}

private fun ByteArray.toPebblePalette16(): IntArray {
    val counts = LinkedHashMap<Int, Int>()
    forEach { color ->
        val key = color.toUnsignedInt()
        counts[key] = (counts[key] ?: 0) + 1
    }
    val palette = mutableListOf(0xff, 0xc0, 0xea, 0xd5)
    counts.entries.sortedByDescending { it.value }.forEach { entry ->
        if (palette.size < 16 && !palette.contains(entry.key)) {
            palette += entry.key
        }
    }
    while (palette.size < 16) {
        palette += 0
    }
    return palette.toIntArray()
}

private fun Int.toPebbleArgb8(): Int {
    val alpha = Color.alpha(this)
    val red = Color.red(this)
    val green = Color.green(this)
    val blue = Color.blue(this)
    val pebbleAlpha = if (alpha < 128) 0 else 3
    return (pebbleAlpha shl 6) or ((red / 85) shl 4) or ((green / 85) shl 2) or (blue / 85)
}

private fun Int.nearestPaletteIndex(palette: IntArray): Int {
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    palette.forEachIndexed { index, paletteColor ->
        val distance = pebbleArgb8Distance(this, paletteColor)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
            if (distance == 0) {
                return bestIndex
            }
        }
    }
    return bestIndex
}

private fun pebbleArgb8Distance(a: Int, b: Int): Int {
    val redDistance = (((a shr 4) and 3) - ((b shr 4) and 3)) * 85
    val greenDistance = (((a shr 2) and 3) - ((b shr 2) and 3)) * 85
    val blueDistance = ((a and 3) - (b and 3)) * 85
    return redDistance * redDistance + greenDistance * greenDistance + blueDistance * blueDistance
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xff

private fun ByteArray.writeUint16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value shr 8) and 0xff).toByte()
}

private val WATCH_SCALE_STEPS = floatArrayOf(1f, 0.95f, 0.9f, 0.85f, 0.8f, 0.72f, 0.64f, 0.56f, 0.48f, 0.4f)
