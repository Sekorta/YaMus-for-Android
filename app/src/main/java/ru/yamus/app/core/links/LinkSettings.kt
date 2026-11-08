package ru.yamus.app.core.links

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.ui.common.DeepLinks
import ru.yamus.app.ui.login.LoginActivity

/**
 * Настройка открытия ссылок и запасные способы открыть ссылку.
 *
 * Начиная с Android 12 приложение не может само открывать ссылки чужого
 * сайта: владелец домена должен подтвердить подпись приложения в
 * assetlinks.json. Яндекс этого не делает, поэтому домены приходится
 * включать вручную. Чтобы ссылку всё же можно было открыть без похода
 * по настройкам, поддерживаются «Поделиться» и буфер обмена.
 */
object LinkSettings {

    private const val PREFS = "yamus_links"
    private const val KEY_EXPLAINED = "explained"
    private const val KEY_LAST_URL = "last_clipboard_url"
    private const val KEY_LAST_TIME = "last_clipboard_time"

    /** Повторно не предлагаем ту же ссылку из буфера сутки. */
    private const val OFFER_TTL_MS = 24L * 60L * 60L * 1000L

    /** Проверяем буфер один раз за запуск, чтобы не читать его постоянно. */
    private var clipboardChecked = false

    /** true, если системные настройки разрешают YaMus открывать домены. */
    fun areDomainsEnabled(context: Context): Boolean {
        // До Android 12 домены не проверяются: достаточно один раз выбрать
        // приложение в системном списке и нажать «Всегда».
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return DomainsApi31.areEnabled(context)
    }

    /**
     * Показывает объяснение и ссылку в настройки, но только один раз.
     * Возвращает true, если диалог был показан.
     */
    fun maybeShowSetup(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (areDomainsEnabled(activity)) return false
        val prefs = prefs(activity)
        if (prefs.getBoolean(KEY_EXPLAINED, false)) return false
        prefs.edit().putBoolean(KEY_EXPLAINED, true).apply()
        showDialog(activity)
        return true
    }

    /** Диалог о ссылках: текущее состояние и быстрые действия. */
    fun showDialog(activity: Activity) {
        if (activity.isFinishing) return
        val enabled = areDomainsEnabled(activity)
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.links_title)
            .setMessage(
                if (enabled) R.string.links_enabled_message else R.string.links_setup_message
            )
            .setNeutralButton(R.string.open_link_from_clipboard) { _, _ ->
                openFromClipboard(activity)
            }
            .setNegativeButton(R.string.close, null)
        if (enabled) {
            builder.setPositiveButton(R.string.ok, null)
        } else {
            builder.setPositiveButton(R.string.links_setup) { _, _ -> setup(activity) }
        }
        try {
            builder.show()
        } catch (_: Exception) {
        }
    }

    /** Предлагает ссылку, если она уже лежит в буфере обмена. */
    fun maybeOfferClipboard(activity: Activity) {
        if (clipboardChecked) return
        clipboardChecked = true
        val url = clipboardLink(activity) ?: return

        val prefs = prefs(activity)
        val sameUrl = url == prefs.getString(KEY_LAST_URL, null)
        val recently = System.currentTimeMillis() - prefs.getLong(KEY_LAST_TIME, 0L) < OFFER_TTL_MS
        if (sameUrl && recently) return
        prefs.edit()
            .putString(KEY_LAST_URL, url)
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .apply()

        if (activity.isFinishing) return
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.link_in_clipboard)
                .setMessage(R.string.link_in_clipboard_message)
                .setPositiveButton(R.string.open) { _, _ -> openLink(activity, url) }
                .setNegativeButton(R.string.later, null)
                .show()
        } catch (_: Exception) {
        }
    }

    /** Открывает ссылку из буфера по явному действию пользователя. */
    fun openFromClipboard(activity: Activity) {
        val url = clipboardLink(activity)
        if (url == null) {
            Toast.makeText(activity, R.string.link_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        openLink(activity, url)
    }

    /** Открывает ссылку, при необходимости сначала просит войти. */
    fun openLink(activity: Activity, url: String) {
        val destination = MusicLinks.parse(url)
        if (destination == null) {
            Toast.makeText(activity, R.string.link_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (TokenStorage.token.isNullOrEmpty()) {
            DeepLinks.savePending(activity, url)
            try {
                activity.startActivity(Intent(activity, LoginActivity::class.java))
            } catch (_: Exception) {
            }
            return
        }
        DeepLinks.open(activity, destination)
    }

    /** Первая поддерживаемая ссылка в буфере обмена или null. */
    fun clipboardLink(context: Context): String? {
        return try {
            val manager =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return null
            if (!manager.hasPrimaryClip()) return null
            val clip = manager.primaryClip ?: return null
            for (index in 0 until clip.itemCount) {
                val text = clip.getItemAt(index).coerceToText(context)?.toString()
                MusicLinks.findUrl(text)?.let { return it }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun setup(activity: Activity) {
        if (openSystemSettings(activity)) return
        Toast.makeText(activity, R.string.link_settings_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun openSystemSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS)
                        .setData(Uri.parse("package:${activity.packageName}"))
                )
                return true
            } catch (_: Exception) {
            }
        }
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Работа с проверкой доменов вынесена в отдельный класс: на Android 4.x
     * сами классы DomainVerification* отсутствуют, и обращаться к ним из
     * общего кода нельзя.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private object DomainsApi31 {

        fun areEnabled(context: Context): Boolean {
            return try {
                val manager = context.getSystemService(
                    Context.DOMAIN_VERIFICATION_SERVICE
                ) as? android.content.pm.verify.domain.DomainVerificationManager
                    ?: return false
                val state = manager.getDomainVerificationUserState(context.packageName)
                    ?: return false
                state.hostToStateMap.values.any {
                    it == android.content.pm.verify.domain.DomainVerificationUserState
                        .DOMAIN_STATE_SELECTED ||
                        it == android.content.pm.verify.domain.DomainVerificationUserState
                            .DOMAIN_STATE_VERIFIED
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
