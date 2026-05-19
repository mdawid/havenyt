package pl.motioncam

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet

class SmbUploader(private val prefs: Preferences) {

    /**
     * Uploads [file] to the configured SMB share under [Preferences.smbPath].
     * Returns true on success. On success the local file is deleted.
     */
    fun upload(file: File): Boolean {
        require(file.exists()) { "File missing: ${file.absolutePath}" }
        require(prefs.smbHost.isNotBlank()) { "SMB host not configured" }
        require(prefs.smbShare.isNotBlank()) { "SMB share not configured" }

        val client = SMBClient()
        client.connect(prefs.smbHost).use { connection ->
            val auth = if (prefs.smbUser.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    prefs.smbUser,
                    prefs.smbPassword.toCharArray(),
                    null,
                )
            }
            val session = connection.authenticate(auth)
            (session.connectShare(prefs.smbShare) as DiskShare).use { share ->
                ensureDirectory(share, prefs.smbPath)
                val remote = joinPath(prefs.smbPath, file.name)
                share.openFile(
                    remote,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.noneOf(SMB2CreateOptions::class.java),
                ).use { remoteFile ->
                    file.inputStream().use { input ->
                        remoteFile.outputStream.use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                }
            }
        }
        return file.delete()
    }

    private fun ensureDirectory(share: DiskShare, path: String) {
        if (path.isBlank()) return
        val parts = path.trim('/', '\\').split('/', '\\').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current\\$part"
            if (!share.folderExists(current)) share.mkdir(current)
        }
    }

    private fun joinPath(dir: String, name: String): String {
        val clean = dir.trim('/', '\\').replace('/', '\\')
        return if (clean.isEmpty()) name else "$clean\\$name"
    }
}
