package com.nathanhanapps.wayd.capture

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Master switches of the rule modules (D-071). Off means the module's rules are not installed in the engine at all, so a
 * disabled module costs nothing. DNS gates the block lists; Request gates rewrite rules only: per-app connection blocks always
 * apply, so a module switch can never silently unblock an app. Both default on, which keeps existing setups unchanged.
 */
data class ModuleState(val dns: Boolean = true, val request: Boolean = true)

@Singleton
class RuleModules @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("rule_modules", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ModuleState(prefs.getBoolean(DNS, true), prefs.getBoolean(REQUEST, true)))
    val state = _state.asStateFlow()

    internal fun set(dns: Boolean = _state.value.dns, request: Boolean = _state.value.request) {
        prefs.edit().putBoolean(DNS, dns).putBoolean(REQUEST, request).apply()
        _state.value = ModuleState(dns, request)
    }

    private companion object {
        const val DNS = "dns"
        const val REQUEST = "request"
    }
}
