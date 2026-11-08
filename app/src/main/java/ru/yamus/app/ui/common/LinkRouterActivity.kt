package ru.yamus.app.ui.common

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.links.MusicLinks
import ru.yamus.app.ui.login.LoginActivity
import ru.yamus.app.ui.main.MainActivity

/**
 * Прозрачная точка входа для ссылок на Яндекс Музыку.
 *
 * Сама ничего не показывает: передаёт ссылку в MainActivity, а если
 * пользователь ещё не вошёл — запоминает её и открывает экран входа.
 *
 * Кроме обычного перехода по ссылке (VIEW) принимает текст из меню
 * «Поделиться» (SEND): так ссылку можно открыть в YaMus в обход
 * системного выбора приложения по умолчанию.
 */
class LinkRouterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val url = sharedUrl() ?: intent?.dataString
        if (url.isNullOrBlank()) {
            if (action == Intent.ACTION_SEND) {
                Toast.makeText(this, R.string.link_not_found, Toast.LENGTH_SHORT).show()
            } else {
                MainActivity.open(this, 0)
            }
            finish()
            return
        }

        if (MusicLinks.parse(url) == null) {
            Toast.makeText(this, R.string.link_unsupported, Toast.LENGTH_SHORT).show()
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

    /** Текст, которым поделились из другого приложения. */
    private fun sharedUrl(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return MusicLinks.findUrl(text)
    }
}
