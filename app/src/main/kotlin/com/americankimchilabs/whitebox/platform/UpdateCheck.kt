package com.americankimchilabs.whitebox.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The app's own version check (the market does not update vehicle apps by itself): the mirror server publishes the
 * newest version on the market, and the car points the driver to the market when it is newer than this one. The app
 * never downloads or installs anything itself.
 */
object UpdateCheck {
    data class Latest(val code: Int, val name: String)

    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    /** The newest version, or null if the server does not say (none set, offline, no server). */
    suspend fun latest(webBase: String): Latest? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$webBase/v1/app/version").build()).execute().use { r ->
                val json = JSONObject(r.body?.string().orEmpty())
                if (!json.has("versionCode")) null else Latest(json.getInt("versionCode"), json.getString("versionName"))
            }
        }.onFailure { Log.i("WhiteBox/Update", "version check skipped: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** Opens this app's page in the vehicle app market; false if no market answers the link. */
    fun openMarket(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://detail?package=${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
