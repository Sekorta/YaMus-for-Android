package ru.yamus.app.ui.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ru.yamus.app.R
import ru.yamus.app.core.download.OfflineLibrary

/**
 * Запрос доступа к общей памяти.
 *
 * Android 4–9 требует WRITE_EXTERNAL_STORAGE для записи в «YaMus Library»,
 * Android 10–12 — READ_EXTERNAL_STORAGE для чтения уже скачанного,
 * Android 13+ — READ_MEDIA_AUDIO.
 */
object StoragePermission {

    const val REQUEST_WRITE = 7201
    const val REQUEST_READ = 7202

    private var pending: (() -> Unit)? = null

    fun hasWrite(context: Context): Boolean = !OfflineLibrary.needsWritePermission(context)

    fun hasRead(context: Context): Boolean = !OfflineLibrary.needsReadPermission(context)

    /** Просит доступ на запись и выполняет [action] после разрешения. */
    fun ensureWrite(activity: Activity, action: () -> Unit) {
        if (hasWrite(activity)) {
            action()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            action()
            return
        }
        if (pending != null) return
        pending = action
        try {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE
            )
        } catch (_: Exception) {
            pending = null
            Toast.makeText(activity, R.string.storage_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    /** Просит доступ на чтение аудио (поиск уже скачанных треков). */
    fun ensureRead(activity: Activity, action: () -> Unit) {
        if (hasRead(activity)) {
            action()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            action()
            return
        }
        if (pending != null) return
        pending = action
        val permission =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        try {
            ActivityCompat.requestPermissions(activity, arrayOf(permission), REQUEST_READ)
        } catch (_: Exception) {
            pending = null
        }
    }

    /** Вызывается из Activity после ответа системы. */
    fun onRequestPermissionsResult(requestCode: Int, granted: Boolean) {
        if (requestCode != REQUEST_WRITE && requestCode != REQUEST_READ) return
        val action = pending
        pending = null
        if (granted) action?.invoke()
    }

    fun isGranted(context: Context, permissions: Array<out String>, results: IntArray): Boolean {
        if (results.isEmpty()) return false
        for (index in permissions.indices) {
            if (results.getOrNull(index) != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
