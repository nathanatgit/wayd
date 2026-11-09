package com.nathanhanapps.wayd.feature.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class RootState { UNKNOWN, AVAILABLE, UNAVAILABLE }

/**
 * Finds out whether this app may use root. Running `su` makes the root manager (KernelSU, Magisk) ask the user, so it only
 * happens when the user taps "Check root"; the last answer is remembered so Settings can show it without asking again.
 */
@Singleton
class RootProbe @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("privilege", Context.MODE_PRIVATE)

    fun last(): RootState = when (prefs.getInt("root", 0)) {
        1 -> RootState.AVAILABLE
        2 -> RootState.UNAVAILABLE
        else -> RootState.UNKNOWN
    }

    suspend fun check(): RootState = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            // Waiting for the user to answer the root manager's dialog can take a while.
            p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && out.contains("uid=0")
        }.getOrDefault(false)
        val state = if (ok) RootState.AVAILABLE else RootState.UNAVAILABLE
        prefs.edit().putInt("root", if (ok) 1 else 2).apply()
        state
    }
}
