package sh.siava.pixelxpert.utils

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture

class TimeSyncWorker(
    private val mContext: Context,
    workerParams: WorkerParameters
) : ListenableWorker(mContext, workerParams) {

    override fun startWork(): ListenableFuture<Result> {
        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(if (NTPTimeSyncer(mContext).syncTimeNow()) Result.success() else Result.retry())
            completer
        }
    }
}
