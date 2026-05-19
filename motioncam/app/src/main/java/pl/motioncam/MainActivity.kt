package pl.motioncam

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Data
import pl.motioncam.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Preferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startService()
        else Toast.makeText(this, R.string.permissions_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Preferences(this)
        bindPrefs()

        binding.btnStart.setOnClickListener {
            savePrefs()
            ensurePermissionsAndStart()
        }
        binding.btnStop.setOnClickListener { MotionService.stop(this) }
        binding.btnTestUpload.setOnClickListener {
            savePrefs()
            testUpload()
        }
        binding.btnSave.setOnClickListener {
            savePrefs()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindPrefs() = with(binding) {
        editHost.setText(prefs.smbHost)
        editShare.setText(prefs.smbShare)
        editPath.setText(prefs.smbPath)
        editUser.setText(prefs.smbUser)
        editPass.setText(prefs.smbPassword)
        editThreshold.setText(prefs.motionThreshold.toString())
        editMinCells.setText(prefs.motionMinCells.toString())
        editRecord.setText(prefs.recordSeconds.toString())
        editCooldown.setText(prefs.cooldownSeconds.toString())
        checkBoot.isChecked = prefs.startOnBoot
    }

    private fun savePrefs() = with(binding) {
        prefs.smbHost = editHost.text.toString().trim()
        prefs.smbShare = editShare.text.toString().trim()
        prefs.smbPath = editPath.text.toString().trim()
        prefs.smbUser = editUser.text.toString().trim()
        prefs.smbPassword = editPass.text.toString()
        prefs.motionThreshold = editThreshold.text.toString().toIntOrNull() ?: 25
        prefs.motionMinCells = editMinCells.text.toString().toIntOrNull() ?: 40
        prefs.recordSeconds = editRecord.text.toString().toIntOrNull() ?: 10
        prefs.cooldownSeconds = editCooldown.text.toString().toIntOrNull() ?: 5
        prefs.startOnBoot = checkBoot.isChecked
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startService() {
        MotionService.start(this)
        Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
    }

    private fun testUpload() {
        // Write a tiny file and enqueue it through the same worker.
        val test = File(filesDir, "pending").apply { mkdirs() }
            .let { File(it, "motioncam_test_${System.currentTimeMillis()}.txt") }
        test.writeText("motioncam test ${java.util.Date()}\n")
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(Data.Builder().putString(UploadWorker.KEY_FILE, test.absolutePath).build())
            .setConstraints(UploadWorker.constraints())
            .build()
        WorkManager.getInstance(this).enqueue(req)
        Toast.makeText(this, R.string.test_enqueued, Toast.LENGTH_SHORT).show()
    }

    @Suppress("unused")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == MotionService::class.java.name }
    }
}
