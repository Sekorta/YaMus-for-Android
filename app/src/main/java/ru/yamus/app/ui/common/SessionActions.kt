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
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.CacheManager
import ru.yamus.app.ui.login.LoginActivity
import kotlinx.coroutines.launch

/**
 * Выход из аккаунта. Удаляет только приватные данные и временный кэш:
 * «YaMus Library» в общей памяти остаётся на месте и доступна с экрана входа.
 */
object SessionActions {

    fun confirmLogout(activity: Activity) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.logout)
            .setMessage("Выйти из аккаунта на этом устройстве? Скачанные треки останутся в «YaMus Library».")
            .setPositiveButton(R.string.ok) { _, _ -> logout(activity) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        styleTerminalDialog(dialog)
    }

    fun logout(activity: Activity) {
        PlaybackManager.stop()
        AccountStore.clear()
        LikesStore.clear()
        DislikesStore.clear()
        WaveHistoryStore.clear()
        if (TokenStorage.autoClearCache) {
            AppScope.launch { CacheManager.clear(activity) }
        }
        TokenStorage.clear()
        val intent = Intent(activity, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        activity.startActivity(intent)
        activity.finish()
    }
}
