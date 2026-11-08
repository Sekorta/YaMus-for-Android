package ru.yamus.app.ui.common

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.yamus.app.R
import ru.yamus.app.core.player.PlaybackService

/**
 * Разрешение на уведомления для системного плеера.
 *
 * Начиная с Android 13 карточка плеера на экране блокировки строится
 * по media-уведомлению foreground-сервиса. Если уведомления запрещены,
 * музыка играет, но в шторке и на экране блокировки плеера может не быть.
 * Поэтому разрешение просим ещё до первого воспроизведения, а если оно
 * отклонено навсегда — показываем дорогу в настройки.
 */
object PlaybackNotifications {

    private const val PREFS = "yamus_ui"
    private const val KEY_ASKED = "notifications_asked"

    /** Чтобы не показывать подсказку повторно в рамках одного процесса. */
    private var prompted = false

    /** Системный запрос уже показан — второй раз за процесс не показываем. */
    private var requestedThisProcess = false

    /** true, если уведомления (или канал плеера) выключены. */
    fun isBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return true
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return false
            val channel = manager.getNotificationChannel(PlaybackService.CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return true
            }
        }
        return false
    }

    /** Просит разрешение без диалогов, если система ещё может его показать. */
    fun requestIfPossible(activity: Activity) {
        if (requestedThisProcess) return
        if (!isBlocked(activity)) return
        if (!canRequestPermission(activity)) return
        requestedThisProcess = true
        try {
            markAsked(activity)
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Вызывается, когда начинается воспроизведение: либо просит разрешение,
     * либо объясняет, где его включить.
     */
    fun ensure(activity: Activity) {
        if (prompted) return
        if (!isBlocked(activity)) return
        prompted = true
        if (requestedThisProcess) return
        requestedThisProcess = true
        try {
            if (canRequestPermission(activity)) {
                markAsked(activity)
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE
                )
            } else {
                showSettingsDialog(activity)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Результат системного запроса. Если разрешение так и не дали и система
     * больше не может показать диалог, при следующем воспроизведении
     * предложим открыть настройки.
     */
    fun onPermissionResult(activity: Activity) {
        requestedThisProcess = false
        if (isBlocked(activity) && !canRequestPermission(activity)) {
            prompted = false
        }
    }

    fun openSettings(activity: Activity) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            } else {
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)
                )
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null)
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun canRequestPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!asked(activity)) return true
        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    private fun asked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)

    private fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }

    private fun showSettingsDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.notifications)
            .setMessage(R.string.notifications_blocked)
            .setPositiveButton(R.string.open_settings) { _, _ -> openSettings(activity) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    const val REQUEST_CODE = 300
}
