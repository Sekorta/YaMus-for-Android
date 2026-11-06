package ru.yamus.app.ui.common

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import ru.yamus.app.ui.player.PlayerSheet

/**
 * Базовая Activity: выставляет выбранную пользователем тему ДО super.onCreate,
 * иначе AppCompat успевает создать окно с темой по умолчанию.
 *
 * Также держит общие элементы интерфейса: выезжающий плеер и мини-плеер
 * с нижней панелью навигации.
 */
open class BaseActivity : AppCompatActivity() {

    protected var playerSheet: PlayerSheet? = null
    protected var chrome: ChromeController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // apply() не только выставляет тему, но и сбрасывает кэш цветов:
        // после смены темы старые значения иначе остаются в подписях вкладок,
        // иконках и кнопках до перезапуска процесса.
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        applyEdgeToEdge()

        // Начиная с Android 13+, а с targetSdk 36/37 — всегда, системная
        // кнопка «назад» идёт через OnBackPressedDispatcher и больше не
        // вызывает Activity.onBackPressed(). Поэтому обрабатываем назад
        // отдельным колбэком, а не переопределением onBackPressed().
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (handleBack()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    /**
     * Android 15+ принудительно включает edge-to-edge. Компенсируем системные
     * отступы, чтобы тулбары и нижняя панель не заезжали под статус-бар.
     */
    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val content = findViewById<View>(android.R.id.content) ?: return
            ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(content)
        } catch (_: Exception) {
        }
    }

    protected fun installPlayerSheet() {
        // Плеер создаётся лениво, при первом открытии: инфляция его разметки
        // заметно тормозит запуск экранов на старых телефонах.
    }

    private fun ensurePlayerSheet(): PlayerSheet {
        playerSheet?.let { return it }
        val created = PlayerSheet(this)
        playerSheet = created
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            created.attach()
        }
        return created
    }

    protected fun installChrome(activeTab: Int, onTabSelected: (Int) -> Unit) {
        if (chrome == null) {
            chrome = ChromeController(this, activeTab, onTabSelected) { ensurePlayerSheet().open() }
        }
    }

    override fun onStart() {
        super.onStart()
        playerSheet?.attach()
        chrome?.start()
    }

    override fun onStop() {
        chrome?.stop()
        playerSheet?.detach()
        super.onStop()
    }

    override fun onDestroy() {
        chrome?.stop()
        playerSheet?.destroy()
        chrome = null
        playerSheet = null
        super.onDestroy()
    }

    /** Возвращает true, если экран сам обработал системное «назад». */
    protected open fun handleBack(): Boolean {
        return playerSheet?.handleBack() == true
    }
}
