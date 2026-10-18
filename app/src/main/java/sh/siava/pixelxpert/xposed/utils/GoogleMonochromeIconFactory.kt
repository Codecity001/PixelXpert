/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sh.siava.pixelxpert.xposed.utils

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Paint.FILTER_BITMAP_FLAG
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.WorkerThread
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Utility class to generate monochrome icons version for a given drawable.
 */
class GoogleMonochromeIconFactory(icon: Drawable, iconBitmapSize: Int) : BitmapDrawable() {

    private val mFlatBitmap: Bitmap
    private val mFlatCanvas: Canvas
    private val mCopyPaint: Paint

    private val mAlphaBitmap: Bitmap
    private val mAlphaCanvas: Canvas
    private val mPixels: ByteArray

    private val mBitmapSize: Int
    private val mEdgePixelLength: Int

    private val mDrawPaint: Paint
    private val mSrcRect: Rect

    init {
        val extraFactor = AdaptiveIconDrawable.getExtraInsetFraction()
        val viewPortScale = 1f / (1f + 2f * extraFactor)
        mBitmapSize = (iconBitmapSize * 2 * viewPortScale).roundToInt()
        mPixels = ByteArray(mBitmapSize * mBitmapSize)
        mEdgePixelLength = mBitmapSize * (mBitmapSize - iconBitmapSize) / 2

        mFlatBitmap = Bitmap.createBitmap(mBitmapSize, mBitmapSize, Config.ARGB_8888)
        mFlatCanvas = Canvas(mFlatBitmap)

        mAlphaBitmap = Bitmap.createBitmap(mBitmapSize, mBitmapSize, Config.ALPHA_8)
        mAlphaCanvas = Canvas(mAlphaBitmap)

        mDrawPaint = Paint(FILTER_BITMAP_FLAG).apply {
            color = Color.WHITE
        }
        mSrcRect = Rect(0, 0, mBitmapSize, mBitmapSize)

        mCopyPaint = Paint(FILTER_BITMAP_FLAG).apply {
            blendMode = BlendMode.SRC
            val satMatrix = ColorMatrix().apply { setSaturation(0f) }
            val vals = satMatrix.array
            vals[15] = 0.3333f
            vals[16] = 0.3333f
            vals[17] = 0.3333f
            vals[18] = 0f
            vals[19] = 0f
            colorFilter = ColorMatrixColorFilter(vals)
        }

        convertMono(icon)
    }

    private fun drawDrawable(drawable: Drawable?) {
        if (drawable != null) {
            drawable.setBounds(0, 0, mBitmapSize, mBitmapSize)
            drawable.draw(mFlatCanvas)
        }
    }

    /**
     * Creates a monochrome version of the provided drawable
     */
    @WorkerThread
    fun convertMono(icon: Drawable) {
        try {
            if (icon is AdaptiveIconDrawable) {
                mFlatCanvas.drawColor(Color.BLACK)
                drawDrawable(icon.background)
                drawDrawable(icon.foreground)
                generateMono()
                return
            }
        } catch (ignored: Throwable) {}

        try {
            mFlatCanvas.drawColor(Color.WHITE)
            drawDrawable(icon)
            generateMono()
        } catch (ignored: Throwable) {}
    }

    @WorkerThread
    private fun generateMono() {
        mAlphaCanvas.drawBitmap(mFlatBitmap, 0f, 0f, mCopyPaint)

        // Scale the end points:
        val buffer = ByteBuffer.wrap(mPixels)
        buffer.rewind()
        mAlphaBitmap.copyPixelsToBuffer(buffer)

        var minVal = 0xFF
        var maxVal = 0
        for (b in mPixels) {
            val unsignedByte = b.toInt() and 0xFF
            minVal = min(minVal, unsignedByte)
            maxVal = max(maxVal, unsignedByte)
        }

        if (minVal < maxVal) {
            // rescale pixels to increase contrast
            val range = (maxVal - minVal).toFloat()

            // In order to check if the colors should be flipped, we just take the average color
            // of top and bottom edge which should correspond to be background color. If the edge
            // colors have more opacity, we flip the colors;
            var sum = 0
            for (i in 0 until mEdgePixelLength) {
                sum += (mPixels[i].toInt() and 0xFF)
                sum += (mPixels[mPixels.size - 1 - i].toInt() and 0xFF)
            }
            val edgeAverage = sum / (mEdgePixelLength * 2f)
            val edgeMapped = (edgeAverage - minVal) / range
            val flipColor = edgeMapped > 0.5f

            for (i in mPixels.indices) {
                val p = mPixels[i].toInt() and 0xFF
                val p2 = ((p - minVal) * 0xFF / range).roundToInt()
                mPixels[i] = if (flipColor) (255 - p2).toByte() else p2.toByte()
            }
            buffer.rewind()
            mAlphaBitmap.copyPixelsFromBuffer(buffer)
            @Suppress("DEPRECATION")
            setBitmap(mAlphaBitmap)
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(mAlphaBitmap, mSrcRect, bounds, mDrawPaint)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat"))
    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }

    override fun setAlpha(i: Int) {
        mDrawPaint.alpha = i
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        mDrawPaint.colorFilter = colorFilter
    }
}
