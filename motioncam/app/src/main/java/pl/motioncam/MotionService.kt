package pl.motioncam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MotionService : LifecycleService() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val videoExecutor = ContextCompat.getMainExecutor(this)

    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private val recording = AtomicBoolean(false)
    private var cooldownJob: Job? = null
    private var detector: MotionDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val prefs by lazy { Preferences(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasPermissions()) {
            Log.w(TAG, "Missing CAMERA/RECORD_AUDIO permission")
            stopSelf()
            return START_NOT_STICKY
        }
        startCamera()
        return START_STICKY
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val det = MotionDetector(
                threshold = prefs.motionThreshold,
                minCells = prefs.motionMinCells,
                onMotion = ::onMotionDetected,
            )
            detector = det
            analysis.setAnalyzer(analysisExecutor, det)

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture,
                    analysis,
                )
                Log.i(TAG, "Camera bound, watching for motion")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to bind camera", t)
                stopSelf()
            }
        }, videoExecutor)
    }

    private fun onMotionDetected() {
        if (!recording.compareAndSet(false, true)) return
        val capture = videoCapture ?: run {
            recording.set(false)
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            recording.set(false)
            return
        }
        detector?.armed = false

        val file = File(filesDir, "pending").also { it.mkdirs() }
            .let { File(it, "motion_${timestamp()}.mp4") }
        val output = FileOutputOptions.Builder(file).build()

        Log.i(TAG, "Motion detected → recording ${file.name}")
        @Suppress("MissingPermission")
        val rec = capture.output
            .prepareRecording(this, output)
            .withAudioEnabled()
            .start(videoExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        currentRecording = null
                        if (event.hasError()) {
                            Log.e(TAG, "Recording error ${event.error}", event.cause)
                            file.delete()
                        } else {
                            queueUpload(file)
                        }
                        startCooldown()
                    }
                    else -> Unit
                }
            }
        currentRecording = rec

        lifecycleScope.launch {
            delay(prefs.recordSeconds * 1000L)
            currentRecording?.stop()
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = lifecycleScope.launch {
            delay(prefs.cooldownSeconds * 1000L)
            detector?.reset()
            detector?.armed = true
            recording.set(false)
            Log.i(TAG, "Re-armed")
        }
    }

    private fun queueUpload(file: File) {
        val data = Data.Builder()
            .putString(UploadWorker.KEY_FILE, file.absolutePath)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(data)
            .setConstraints(UploadWorker.constraints())
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30_000L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(this)
            .enqueueUniqueWork("upload_${file.name}", androidx.work.ExistingWorkPolicy.KEEP, request)
    }

    private fun startInForeground() {
        val stopIntent = Intent(this, MotionService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "motioncam:service").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun hasPermissions(): Boolean {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        return needed.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        currentRecording?.stop()
        currentRecording = null
        cooldownJob?.cancel()
        analysisExecutor.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        runCatching { ProcessCameraProvider.getInstance(this).get().unbindAll() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        private const val TAG = "MotionService"
        private const val CHANNEL_ID = "motioncam_service"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "pl.motioncam.STOP"

        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, MotionService::class.java)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, MotionService::class.java).setAction(ACTION_STOP))
        }
    }
}
