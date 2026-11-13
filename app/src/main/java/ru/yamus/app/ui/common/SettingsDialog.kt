package ru.yamus.app.ui.common

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.data.DislikesStore
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.data.WaveHistoryStore
import ru.yamus.app.core.links.LinkSettings
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.CacheManager
import ru.yamus.app.ui.login.LoginActivity
import kotlinx.coroutines.launch

object SettingsDialog {

    fun show(activity: Activity) {
        val items = arrayOf(
            "${activity.getString(R.string.quality)}: ${qualityLabel(activity)}",
            "${activity.getString(R.string.theme)}: ${themeLabel(activity)}",
            "${activity.getString(R.string.style)}: ${styleLabel(activity)}",
            "${activity.getString(R.string.clear_cache)} (${CacheManager.formatSize(CacheManager.size(activity))})",
            activity.getString(R.string.links_title),
            activity.getString(R.string.open_link_from_clipboard),
            activity.getString(R.string.logout)
        )
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.settings)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showQuality(activity)
                    1 -> showTheme(activity)
                    2 -> showStyle(activity)
                    3 -> clearCache(activity)
                    4 -> LinkSettings.showMenu(activity)
                    5 -> LinkSettings.openFromClipboard(activity)
                    6 -> confirmLogout(activity)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun qualityLabel(activity: Activity): String = when (TokenStorage.quality) {
        TokenStorage.QUALITY_LOW -> activity.getString(R.string.quality_low)
        TokenStorage.QUALITY_MEDIUM -> activity.getString(R.string.quality_medium)
        else -> activity.getString(R.string.quality_max)
    }

    private fun themeLabel(activity: Activity): String = when (TokenStorage.theme) {
        TokenStorage.THEME_BLACK -> activity.getString(R.string.theme_black)
        TokenStorage.THEME_LIGHT -> activity.getString(R.string.theme_light)
        else -> activity.getString(R.string.theme_dark)
    }

    private fun styleLabel(activity: Activity): String = when (TokenStorage.style) {
        TokenStorage.STYLE_HOLO -> activity.getString(R.string.style_holo)
        TokenStorage.STYLE_TERMINAL -> activity.getString(R.string.style_terminal)
        else -> activity.getString(R.string.style_modern)
    }

    private fun showQuality(activity: Activity) {
        val values = arrayOf(
            TokenStorage.QUALITY_MAX,
            TokenStorage.QUALITY_MEDIUM,
            TokenStorage.QUALITY_LOW
        )
        val labels = arrayOf(
            activity.getString(R.string.quality_max),
            activity.getString(R.string.quality_medium),
            activity.getString(R.string.quality_low)
        )
        val checked = values.indexOf(TokenStorage.quality).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.quality)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                TokenStorage.quality = values[which]
                dialog.dismiss()
                PlaybackManager.toast("Качество применится к следующему треку")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun showTheme(activity: Activity) {
        val values = arrayOf(
            TokenStorage.THEME_BLACK,
            TokenStorage.THEME_DARK,
            TokenStorage.THEME_LIGHT
        )
        val labels = arrayOf(
            activity.getString(R.string.theme_black),
            activity.getString(R.string.theme_dark),
            activity.getString(R.string.theme_light)
        )
        val checked = values.indexOf(TokenStorage.theme).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                TokenStorage.theme = values[which]
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun showStyle(activity: Activity) {
        val values = arrayOf(
            TokenStorage.STYLE_MODERN,
            TokenStorage.STYLE_HOLO,
            TokenStorage.STYLE_TERMINAL
        )
        val labels = arrayOf(
            activity.getString(R.string.style_modern),
            activity.getString(R.string.style_holo),
            activity.getString(R.string.style_terminal)
        )
        val checked = values.indexOf(TokenStorage.style).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.style)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                TokenStorage.style = values[which]
                dialog.dismiss()
                activity.recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun clearCache(activity: Activity) {
        AppScope.launch {
            CacheManager.clear(activity)
            PlaybackManager.toast(activity.getString(R.string.cache_cleared))
        }
    }

    private fun confirmLogout(activity: Activity) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.logout)
            .setMessage("Выйти из аккаунта на этом устройстве?")
            .setPositiveButton(R.string.ok) { _, _ -> logout(activity) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun logout(activity: Activity) {
        PlaybackManager.stop()
        AccountStore.clear()
        LikesStore.clear()
        DislikesStore.clear()
        WaveHistoryStore.clear()
        AppScope.launch { CacheManager.clear(activity) }
        TokenStorage.clear()
        val intent = Intent(activity, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
