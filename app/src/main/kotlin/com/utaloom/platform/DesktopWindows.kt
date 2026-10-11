package com.utaloom.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Window requests can come from tray menus or native hotkey threads, without touching Compose state. */
object DesktopWindows {
    private val mini = MutableStateFlow(false)
    val miniVisible = mini.asStateFlow()
    fun showMini() { mini.value = true }
    fun closeMini() { mini.value = false }
}
