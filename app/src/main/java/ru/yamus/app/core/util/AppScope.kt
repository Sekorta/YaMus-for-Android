package ru.yamus.app.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Общий scope для фоновых операций, не привязанных к экрану (лайки, диалоги, волна). */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main)
