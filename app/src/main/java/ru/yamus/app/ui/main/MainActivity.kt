package ru.yamus.app.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.Fragment
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.data.DislikesStore
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.links.LinkSettings
import ru.yamus.app.core.links.MusicLinks
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.common.DeepLinks
import ru.yamus.app.ui.common.PlaybackNotifications
import ru.yamus.app.ui.home.HomeFragment
import ru.yamus.app.ui.login.LoginActivity
import ru.yamus.app.ui.me.MeFragment
import ru.yamus.app.ui.search.SearchFragment

class MainActivity : BaseActivity() {

    companion object {
        private const val EXTRA_TAB = "tab"
        private const val EXTRA_LINK = "deep_link"

        /** Возвращает на главный экран с нужной вкладкой, закрывая вторичные экраны. */
        fun open(context: Context, tab: Int) {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }

        /** Открывает главный экран и передаёт ему ссылку для разбора. */
        fun openLink(context: Context, url: String) {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_LINK, url)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    private var currentTab = 0

    private val tags = arrayOf("home", "search", "me")

    /** Подсказку про ссылки показываем один раз за запуск экрана. */
    private var linkPromptShown = false

    /** Экран открыт по ссылке: подсказки не должны перекрывать переход. */
    private var openedFromLink = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TokenStorage.token.isNullOrEmpty()) {
            intent.getStringExtra(EXTRA_LINK)?.let { DeepLinks.savePending(this, it) }
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        currentTab = savedInstanceState?.getInt("tab", 0)
            ?: intent.getIntExtra(EXTRA_TAB, 0)
        if (currentTab !in tags.indices) currentTab = 0

        installPlayerSheet()
        installChrome(currentTab) { showTab(it) }
        showTab(currentTab)
        // После поворота экрана ссылка уже обработана, повторно не открываем.
        if (savedInstanceState == null) handleDeepLink(intent)
        PlaybackNotifications.requestIfPossible(this)

        // Лайки/дизлайки нужны плееру и спискам сразу, а не только после
        // открытия вкладки «Я».
        AppScope.launch {
            val uid = AccountStore.uid.takeIf { it > 0 }
                ?: AccountStore.load()?.uid
                ?: 0L
            if (uid > 0) {
                if (!LikesStore.loaded) LikesStore.load(uid)
                if (!DislikesStore.loaded) DislikesStore.load(uid)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra(EXTRA_TAB, currentTab)
        if (tab in tags.indices) showTab(tab)
        handleDeepLink(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || linkPromptShown) return
        linkPromptShown = true
        if (openedFromLink) return
        // Буфер читаем только когда окно получило фокус: Android 10+
        // не отдаёт его приложение в фоне.
        if (!LinkSettings.maybeShowSetup(this)) {
            LinkSettings.maybeOfferClipboard(this)
        }
    }

    private fun handleDeepLink(source: Intent?) {
        val url = source?.getStringExtra(EXTRA_LINK) ?: return
        openedFromLink = true
        // Убираем ссылку, чтобы поворот экрана не открывал её заново.
        source.removeExtra(EXTRA_LINK)
        val destination = MusicLinks.parse(Uri.parse(url))
        if (destination == null) {
            Toast.makeText(this, R.string.link_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        DeepLinks.open(this, destination)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", currentTab)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(index: Int) {
        currentTab = index
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        for (i in tags.indices) {
            val existing = manager.findFragmentByTag(tags[i])
            if (i == index) {
                // Остальные вкладки создаём только при первом открытии:
                // на старых телефонах одновременная инфляция трёх экранов
                // заметно тормозит запуск приложения.
                if (existing == null) {
                    transaction.add(R.id.fragmentContainer, createFragment(i), tags[i])
                } else {
                    transaction.show(existing)
                }
            } else if (existing != null) {
                transaction.hide(existing)
            }
        }
        transaction.commitAllowingStateLoss()
        chrome?.setActiveTab(index)
    }

    private fun createFragment(index: Int): Fragment = when (index) {
        0 -> HomeFragment()
        1 -> SearchFragment()
        else -> MeFragment()
    }

    override fun handleBack(): Boolean {
        if (super.handleBack()) return true
        if (currentTab != 0) {
            showTab(0)
            return true
        }
        return false
    }
}
