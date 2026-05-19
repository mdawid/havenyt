package pl.motioncam

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_FILE) ?: return@withContext Result.failure()
        val file = File(path)
        if (!file.exists()) {
            Log.w(TAG, "File missing, dropping job: $path")
            return@withContext Result.success()
        }
        val prefs = Preferences(applicationContext)
        if (prefs.smbHost.isBlank() || prefs.smbShare.isBlank()) {
            Log.w(TAG, "SMB not configured; keeping file for later")
            return@withContext Result.retry()
        }
        try {
            SmbUploader(prefs).upload(file)
            Log.i(TAG, "Uploaded ${file.name}")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Upload failed (will retry): ${t.message}")
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val KEY_FILE = "file"
        private const val MAX_ATTEMPTS = 8

        fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
