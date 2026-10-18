package sh.siava.pixelxpert.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.common.moduleinstall.ModuleAvailabilityResponse
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.OnSuccessListener
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

class MLKitSegmentor(private val mContext: Context) {
    val mSegmenter: SubjectSegmenter

    init {
        runCatching {
            MlKit.initialize(mContext)
        }
        mSegmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )
        downloadModelIfNeeded()
    }

    companion object {
        @JvmStatic
        fun extractSubject(context: Context, input: Bitmap): Bitmap? {
            return try {
                var resultBitmap: Bitmap? = null
                val resultWaiter = CountDownLatch(1)
                MLKitSegmentor(context).segmentSubject(input, object : SegmentResultListener {
                    override fun onSuccess(result: Bitmap) {
                        resultBitmap = result
                        resultWaiter.countDown()
                    }

                    override fun onFail() {
                        resultWaiter.countDown()
                    }
                })
                resultWaiter.await()
                resultBitmap
            } catch (ignored: Throwable) {
                null
            }
        }
    }

    fun downloadModelIfNeeded() {
        val moduleInstallClient: ModuleInstallClient = ModuleInstall.getClient(mContext)
        moduleInstallClient.areModulesAvailable(mSegmenter)
            .addOnSuccessListener { response ->
                if (!response.areModulesAvailable()) {
                    moduleInstallClient.installModules(
                        ModuleInstallRequest.newBuilder()
                            .addApi(mSegmenter)
                            .build()
                    )
                }
            }
    }

    fun checkModelAvailability(resultListener: OnSuccessListener<ModuleAvailabilityResponse>) {
        val moduleInstallClient: ModuleInstallClient = ModuleInstall.getClient(mContext)
        moduleInstallClient.areModulesAvailable(mSegmenter).addOnSuccessListener(resultListener)
    }

    fun segmentSubject(inputBitmap: Bitmap, listener: SegmentResultListener) {
        val transparentColor = Color.alpha(Color.TRANSPARENT)
        val resultBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)

        mSegmenter.process(InputImage.fromBitmap(inputBitmap, 0))
            .addOnSuccessListener { subjectSegmentationResult ->
                val mSubjectMask: FloatBuffer? = subjectSegmentationResult.foregroundConfidenceMask
                if (mSubjectMask != null) {
                    resultBitmap.setHasAlpha(true)
                    for (y in 0 until inputBitmap.height) {
                        for (x in 0 until inputBitmap.width) {
                            if (mSubjectMask.get() < 0.5f) {
                                resultBitmap.setPixel(x, y, transparentColor)
                            }
                        }
                    }
                }
                inputBitmap.recycle()
                listener.onSuccess(resultBitmap)
            }
            .addOnFailureListener {
                inputBitmap.recycle()
                listener.onFail()
            }
    }

    interface SegmentResultListener {
        fun onSuccess(result: Bitmap)
        fun onFail()
    }
}
