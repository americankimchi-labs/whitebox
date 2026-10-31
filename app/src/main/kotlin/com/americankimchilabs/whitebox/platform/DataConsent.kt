package com.americankimchilabs.whitebox.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The driver's one-time choice about sharing pseudonymous crash signal windows to improve detection. Asked once
 * (null until answered), remembered, changeable at any time; off unless the driver agreed.
 */
object DataConsent {
    private const val PREFS = "consent"
    private const val KEY = "improve_detection"
    private val state = MutableStateFlow<Boolean?>(null)
    @Volatile private var loaded = false

    fun state(context: Context): StateFlow<Boolean?> {
        load(context)
        return state.asStateFlow()
    }

    fun agreed(context: Context): Boolean = load(context) == true

    fun set(context: Context, agree: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, agree).apply()
        state.value = agree
    }

    private fun load(context: Context): Boolean? {
        if (!loaded) {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            state.value = if (p.contains(KEY)) p.getBoolean(KEY, false) else null
            loaded = true
        }
        return state.value
    }
}
