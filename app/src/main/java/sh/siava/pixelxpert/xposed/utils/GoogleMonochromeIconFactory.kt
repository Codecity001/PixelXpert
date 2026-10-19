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

/**
 * Utility class to generate a monochrome version of a given drawable: the icon
 * is flattened onto a bitmap (white behind a plain drawable, black behind an
 * [AdaptiveIconDrawable]), pushed through a saturation matrix so the result is
 * grayscale with the RGB average in the alpha channel, then contrast-stretched
 * between the darkest and lightest alpha values. The average colour of the top
 * and bottom edge decides whether black-on-white must be flipped to
 * white-on-black, and the finished silhouette lives in an alpha bitmap that
 * [draw] blits through a white filtering paint - so the factory itself is the
 * drawable the themed-icons hook hands around.
 */
// The BitmapDrawable() super constructor and getOpacity() are deprecated, and
// AdaptiveIconDrawable.getExtraInsetFraction() too.
@Suppress("DEPRECATION")
class GoogleMonochromeIconFactory(icon: Drawable?, iconBitmapSize: Int) : BitmapDrawable() {
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
		val viewPortScale = 1 / (1 + 2 * extraFactor)
		mBitmapSize = Math.round(iconBitmapSize * 2 * viewPortScale)
		mPixels = ByteArray(mBitmapSize * mBitmapSize)
		mEdgePixelLength = mBitmapSize * (mBitmapSize - iconBitmapSize) / 2

		mFlatBitmap = Bitmap.createBitmap(mBitmapSize, mBitmapSize, Bitmap.Config.ARGB_8888)
		mFlatCanvas = Canvas(mFlatBitmap)

		mAlphaBitmap = Bitmap.createBitmap(mBitmapSize, mBitmapSize, Bitmap.Config.ALPHA_8)
		mAlphaCanvas = Canvas(mAlphaBitmap)

		mDrawPaint = Paint(FILTER_BITMAP_FLAG)
		mDrawPaint.color = Color.WHITE
		mSrcRect = Rect(0, 0, mBitmapSize, mBitmapSize)

		mCopyPaint = Paint(FILTER_BITMAP_FLAG)
		mCopyPaint.blendMode = BlendMode.SRC

		// Crate a color matrix which converts the icon to grayscale and then uses the average
		// of RGB components as the alpha component.
		val satMatrix = ColorMatrix()
		satMatrix.setSaturation(0f)
		val vals = satMatrix.array
		// spelled out rather than chained right-to-left; same result
		vals[17] = .3333f
		vals[16] = .3333f
		vals[15] = .3333f
		vals[19] = 0f
		vals[18] = 0f
		mCopyPaint.colorFilter = ColorMatrixColorFilter(vals)

		convertMono(icon)
	}

	private fun drawDrawable(drawable: Drawable?) {
		if (drawable != null) {
			drawable.setBounds(0, 0, mBitmapSize, mBitmapSize)
			drawable.draw(mFlatCanvas)
		}
	}

	@WorkerThread
	fun convertMono(icon: Drawable?) {
		try {
			if (icon is AdaptiveIconDrawable) {
				val aid = icon
				mFlatCanvas.drawColor(Color.BLACK)
				drawDrawable(aid.background)
				drawDrawable(aid.foreground)
				generateMono()
				return
			}
		} catch (ignored: Throwable) {
		}

		try {
			mFlatCanvas.drawColor(Color.WHITE)
			drawDrawable(icon)
			generateMono()
		} catch (ignored: Throwable) { // WTF?!
		}
	}

	@WorkerThread
	private fun generateMono() {
		mAlphaCanvas.drawBitmap(mFlatBitmap, 0f, 0f, mCopyPaint)

		val buffer = ByteBuffer.wrap(mPixels)
		buffer.rewind()
		mAlphaBitmap.copyPixelsToBuffer(buffer)

		var min = 0xFF
		var max = 0
		for (b in mPixels) {
			min = Math.min(min, b.toInt() and 0xFF)
			max = Math.max(max, b.toInt() and 0xFF)
		}

		if (min < max) { //noinspection ExtractMethodRecommender
			val range = (max - min).toFloat()

			// In order to check if the colors should be flipped, we just take the average color
			// of top and bottom edge which should correspond to be background color. If the edge
			// colors have more opacity, we flip the colors;
			var sum = 0
			for (i in 0 until mEdgePixelLength) {
				sum += (mPixels[i].toInt() and 0xFF)
				sum += (mPixels[mPixels.size - 1 - i].toInt() and 0xFF)
			}
			val edgeAverage = sum / (mEdgePixelLength * 2f)
			val edgeMapped = (edgeAverage - min) / range
			val flipColor = edgeMapped > .5f

			for (i in mPixels.indices) {
				val p = mPixels[i].toInt() and 0xFF
				val p2 = Math.round((p - min) * 0xFF / range)
				mPixels[i] = if (flipColor) (255 - p2).toByte() else p2.toByte()
			}
			buffer.rewind()
			mAlphaBitmap.copyPixelsFromBuffer(buffer)
			setBitmap(mAlphaBitmap)
		}
	}

	override fun draw(canvas: Canvas) {
		canvas.drawBitmap(mAlphaBitmap, mSrcRect, getBounds(), mDrawPaint)
	}

	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	override fun setAlpha(i: Int) {
		mDrawPaint.alpha = i
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		mDrawPaint.colorFilter = colorFilter
	}
}
