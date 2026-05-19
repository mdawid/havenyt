package pl.motioncam

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var smbHost: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit { putString(KEY_HOST, value) }

    var smbShare: String
        get() = prefs.getString(KEY_SHARE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SHARE, value) }

    var smbPath: String
        get() = prefs.getString(KEY_PATH, "motioncam") ?: "motioncam"
        set(value) = prefs.edit { putString(KEY_PATH, value) }

    var smbUser: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        set(value) = prefs.edit { putString(KEY_USER, value) }

    var smbPassword: String
        get() = prefs.getString(KEY_PASS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PASS, value) }

    var motionThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 25)
        set(value) = prefs.edit { putInt(KEY_THRESHOLD, value) }

    var motionMinCells: Int
        get() = prefs.getInt(KEY_MIN_CELLS, 40)
        set(value) = prefs.edit { putInt(KEY_MIN_CELLS, value) }

    var recordSeconds: Int
        get() = prefs.getInt(KEY_RECORD_SEC, 10)
        set(value) = prefs.edit { putInt(KEY_RECORD_SEC, value) }

    var cooldownSeconds: Int
        get() = prefs.getInt(KEY_COOLDOWN_SEC, 5)
        set(value) = prefs.edit { putInt(KEY_COOLDOWN_SEC, value) }

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_BOOT, false)
        set(value) = prefs.edit { putBoolean(KEY_BOOT, value) }

    companion object {
        private const val NAME = "motioncam_prefs"
        private const val KEY_HOST = "smb_host"
        private const val KEY_SHARE = "smb_share"
        private const val KEY_PATH = "smb_path"
        private const val KEY_USER = "smb_user"
        private const val KEY_PASS = "smb_pass"
        private const val KEY_THRESHOLD = "motion_threshold"
        private const val KEY_MIN_CELLS = "motion_min_cells"
        private const val KEY_RECORD_SEC = "record_seconds"
        private const val KEY_COOLDOWN_SEC = "cooldown_seconds"
        private const val KEY_BOOT = "start_on_boot"
    }
}
