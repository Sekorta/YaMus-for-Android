package ru.yamus.app.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import ru.yamus.app.BuildConfig
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.download.LibraryStorage
import ru.yamus.app.core.download.OfflineLibrary
import ru.yamus.app.core.links.LinkSettings
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.CacheManager
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.common.IconView
import ru.yamus.app.ui.common.PlaybackNotifications
import ru.yamus.app.ui.common.SessionActions
import ru.yamus.app.ui.common.applySectionHeaderStyle
import ru.yamus.app.ui.common.setSectionHeaderText
import ru.yamus.app.ui.common.styleTerminalDialog
import ru.yamus.app.ui.downloads.DownloadsActivity
import kotlinx.coroutines.launch

/**
 * Настройки отдельным экраном.
 *
 * Всё, что касается звука, оформления и офлайн-библиотеки, собрано в одном
 * месте: на «Я» остаётся только кнопка перехода сюда, поэтому меню больше
 * не разрастается.
 */
class SettingsActivity : BaseActivity() {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }

    private lateinit var qualityValue: TextView
    private lateinit var themeValue: TextView
    private lateinit var styleValue: TextView
    private lateinit var notificationsValue: TextView
    private lateinit var downloadedValue: TextView
    private lateinit var folderValue: TextView
    private lateinit var cacheValue: TextView
    private lateinit var versionText: TextView
    private lateinit var autoDownloadSwitch: SwitchCompat
    private lateinit var likedOnlySwitch: SwitchCompat
    private lateinit var wifiOnlySwitch: SwitchCompat
    private lateinit var autoClearSwitch: SwitchCompat
    private lateinit var likedOnlyRow: View
    private lateinit var wifiOnlyRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<IconView>(R.id.backButton).setOnClickListener { finish() }
        qualityValue = findViewById(R.id.qualityValue)
        themeValue = findViewById(R.id.themeValue)
        styleValue = findViewById(R.id.styleValue)
        notificationsValue = findViewById(R.id.notificationsValue)
        downloadedValue = findViewById(R.id.downloadedValue)
        folderValue = findViewById(R.id.folderValue)
        cacheValue = findViewById(R.id.cacheValue)
        versionText = findViewById(R.id.versionText)
        autoDownloadSwitch = findViewById(R.id.autoDownloadSwitch)
        likedOnlySwitch = findViewById(R.id.likedOnlySwitch)
        wifiOnlySwitch = findViewById(R.id.wifiOnlySwitch)
        autoClearSwitch = findViewById(R.id.autoClearSwitch)
        likedOnlyRow = findViewById(R.id.likedOnlyRow)
        wifiOnlyRow = findViewById(R.id.wifiOnlyRow)

        styleHeaders()
        bindRows()
        refreshValues()
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    private fun styleHeaders() {
        listOf(
            R.id.playbackHeader to R.string.settings_section_playback,
            R.id.downloadsHeader to R.string.settings_section_downloads,
            R.id.dataHeader to R.string.settings_section_data,
            R.id.aboutHeader to R.string.settings_section_about
        ).forEach { (id, text) ->
            findViewById<TextView>(id).apply {
                applySectionHeaderStyle()
                setSectionHeaderText(text)
            }
        }
    }

    private fun bindRows() {
        findViewById<View>(R.id.qualityRow).setOnClickListener { showQuality() }
        findViewById<View>(R.id.themeRow).setOnClickListener { showTheme() }
        findViewById<View>(R.id.styleRow).setOnClickListener { showStyle() }
        findViewById<View>(R.id.notificationsRow).setOnClickListener {
            PlaybackNotifications.openSettings(this)
        }
        findViewById<View>(R.id.downloadedRow).setOnClickListener {
            startActivity(DownloadsActivity.intent(this))
        }
        findViewById<View>(R.id.folderRow).setOnClickListener {
            if (!LibraryStorage.openFolder(this)) {
                PlaybackManager.toast(getString(R.string.folder_unavailable))
            }
        }
        findViewById<View>(R.id.clearCacheRow).setOnClickListener { clearCache() }
        findViewById<View>(R.id.linksRow).setOnClickListener {
            LinkSettings.showMenu(this)
        }
        findViewById<View>(R.id.clipboardRow).setOnClickListener {
            LinkSettings.openFromClipboard(this)
        }
        findViewById<View>(R.id.logoutRow).setOnClickListener {
            SessionActions.confirmLogout(this)
        }

        // Строка целиком переключает свитч: попасть по маленькому ползунку
        // на старом телефоне сложно.
        findViewById<View>(R.id.autoDownloadRow).setOnClickListener {
            autoDownloadSwitch.toggle()
        }
        findViewById<View>(R.id.likedOnlyRow).setOnClickListener {
            likedOnlySwitch.toggle()
        }
        findViewById<View>(R.id.wifiOnlyRow).setOnClickListener {
            wifiOnlySwitch.toggle()
        }
        findViewById<View>(R.id.autoClearRow).setOnClickListener {
            autoClearSwitch.toggle()
        }

        autoDownloadSwitch.setOnCheckedChangeListener { _, checked ->
            TokenStorage.autoDownload = checked
            updateWifiAvailability()
        }
        likedOnlySwitch.setOnCheckedChangeListener { _, checked ->
            TokenStorage.autoDownloadLikedOnly = checked
        }
        wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            TokenStorage.wifiOnlyDownloads = checked
        }
        autoClearSwitch.setOnCheckedChangeListener { _, checked ->
            TokenStorage.autoClearCache = checked
        }
    }

    private fun refreshValues() {
        qualityValue.text = qualityLabel()
        themeValue.text = themeLabel()
        styleValue.text = styleLabel()
        notificationsValue.text = getString(
            if (PlaybackNotifications.isBlocked(this)) R.string.notifications_disabled
            else R.string.notifications_enabled
        )
        downloadedValue.text = getString(
            R.string.tracks_count,
            OfflineLibrary.entries().size
        )
        folderValue.text = LibraryStorage.rootLabel()
        cacheValue.text = CacheManager.formatSize(CacheManager.size(this))
        versionText.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        autoDownloadSwitch.isChecked = TokenStorage.autoDownload
        likedOnlySwitch.isChecked = TokenStorage.autoDownloadLikedOnly
        wifiOnlySwitch.isChecked = TokenStorage.wifiOnlyDownloads
        autoClearSwitch.isChecked = TokenStorage.autoClearCache
        updateWifiAvailability()
    }

    private fun updateWifiAvailability() {
        val enabled = TokenStorage.autoDownload
        likedOnlyRow.alpha = if (enabled) 1f else 0.5f
        likedOnlySwitch.isEnabled = enabled
        wifiOnlyRow.alpha = if (enabled) 1f else 0.5f
        wifiOnlySwitch.isEnabled = enabled
    }

    private fun qualityLabel(): String = when (TokenStorage.quality) {
        TokenStorage.QUALITY_LOW -> getString(R.string.quality_low)
        TokenStorage.QUALITY_MEDIUM -> getString(R.string.quality_medium)
        else -> getString(R.string.quality_max)
    }

    private fun themeLabel(): String = when (TokenStorage.theme) {
        TokenStorage.THEME_BLACK -> getString(R.string.theme_black)
        TokenStorage.THEME_LIGHT -> getString(R.string.theme_light)
        else -> getString(R.string.theme_dark)
    }

    private fun styleLabel(): String = when (TokenStorage.style) {
        TokenStorage.STYLE_HOLO -> getString(R.string.style_holo)
        TokenStorage.STYLE_TERMINAL -> getString(R.string.style_terminal)
        else -> getString(R.string.style_modern)
    }

    private fun showQuality() {
        val values = arrayOf(
            TokenStorage.QUALITY_MAX,
            TokenStorage.QUALITY_MEDIUM,
            TokenStorage.QUALITY_LOW
        )
        val labels = arrayOf(
            getString(R.string.quality_max),
            getString(R.string.quality_medium),
            getString(R.string.quality_low)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.quality)
            .setSingleChoiceItems(labels, values.indexOf(TokenStorage.quality).coerceAtLeast(0)) { d, which ->
                TokenStorage.quality = values[which]
                d.dismiss()
                qualityValue.text = qualityLabel()
                PlaybackManager.toast("Качество применится к следующему треку")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun showTheme() {
        val values = arrayOf(
            TokenStorage.THEME_BLACK,
            TokenStorage.THEME_DARK,
            TokenStorage.THEME_LIGHT
        )
        val labels = arrayOf(
            getString(R.string.theme_black),
            getString(R.string.theme_dark),
            getString(R.string.theme_light)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, values.indexOf(TokenStorage.theme).coerceAtLeast(0)) { d, which ->
                TokenStorage.theme = values[which]
                d.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun showStyle() {
        val values = arrayOf(
            TokenStorage.STYLE_MODERN,
            TokenStorage.STYLE_HOLO,
            TokenStorage.STYLE_TERMINAL
        )
        val labels = arrayOf(
            getString(R.string.style_modern),
            getString(R.string.style_holo),
            getString(R.string.style_terminal)
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.style)
            .setSingleChoiceItems(labels, values.indexOf(TokenStorage.style).coerceAtLeast(0)) { d, which ->
                TokenStorage.style = values[which]
                d.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    private fun clearCache() {
        AppScope.launch {
            CacheManager.clear(this@SettingsActivity)
            cacheValue.text = CacheManager.formatSize(CacheManager.size(this@SettingsActivity))
            PlaybackManager.toast(getString(R.string.cache_cleared))
        }
    }
}
