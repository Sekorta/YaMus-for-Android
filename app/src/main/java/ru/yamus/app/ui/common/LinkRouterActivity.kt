package ru.yamus.app.ui.common

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.ui.login.LoginActivity
import ru.yamus.app.ui.main.MainActivity

/**
 * Прозрачная точка входа для ссылок на Яндекс Музыку.
 *
 * Сама ничего не показывает: передаёт ссылку в MainActivity, а если
 * пользователь ещё не вошёл — запоминает её и открывает экран входа.
 */
class LinkRouterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.dataString
        if (url.isNullOrBlank()) {
            MainActivity.open(this, 0)
            finish()
            return
        }

        if (TokenStorage.token.isNullOrEmpty()) {
            DeepLinks.savePending(this, url)
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
            )
        } else {
            MainActivity.openLink(this, url)
        }
        finish()
    }
}
