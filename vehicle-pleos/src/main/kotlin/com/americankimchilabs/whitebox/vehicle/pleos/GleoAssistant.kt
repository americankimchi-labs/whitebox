package com.americankimchilabs.whitebox.vehicle.pleos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.americankimchilabs.whitebox.core.AssistantCommands
import com.americankimchilabs.whitebox.core.BookmarkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "WhiteBox/Gleo"

/** The function our assets/schema.json declares. */
private const val BOOKMARK_MOMENT = "BOOKMARK_MOMENT"
private const val APP_ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

/** The assistant cancels a call after 7 s; answer well before, so it can still speak our result. */
private const val ANSWER_WITHIN_MS = 4_000L

/**
 * The car assistant (Gleo) through the Gleo Interface: "글레오, 화이트박스에서 방금 상황 저장해 줘" arrives as our
 * BOOKMARK_MOMENT function. Gleo needs a network (offline it detects its wake word but does nothing), so the recording
 * service runs its own on-device listener while the car is offline.
 *
 * The integration activates once Pleos approves the schema; on the emulator an unapproved schema already works.
 */
object GleoAssistant : AssistantCommands {
    private val requests = MutableSharedFlow<BookmarkRequest>(extraBufferCapacity = 4)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun bookmarks(): Flow<BookmarkRequest> = requests.asSharedFlow()

    internal fun onAction(context: Context, action: String, done: () -> Unit) {
        val function = action.substringAfterLast('.')
        if (function != BOOKMARK_MOMENT) {
            Log.w(TAG, "unknown function $action")
            respond(context, action, saved = false)
            done()
            return
        }
        scope.launch {
            val saved = withTimeoutOrNull(ANSWER_WITHIN_MS) {
                suspendCoroutine { cont ->
                    val request = BookmarkRequest { ok -> runCatching { cont.resume(ok) } }
                    // Nobody collecting means the recorder is not running: nothing could be saved.
                    if (requests.subscriptionCount.value == 0 || !requests.tryEmit(request)) cont.resume(false)
                }
            } ?: false
            respond(context, action, saved)
            done()
        }
    }

    /**
     * The documented answer broadcast. `message` is spoken by the assistant (English per the interface spec; it
     * localises): kept as short as possible.
     */
    private fun respond(context: Context, functionName: String, saved: Boolean) {
        val response = JSONObject()
            .put("functionName", functionName)
            .put("status", if (saved) "success" else "fail")
            .put("message", if (saved) "Saved." else "White Box is not recording right now.")
            .put("mode", "voice")
        context.sendBroadcast(
            Intent(APP_ACTION_RESPONSE)
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString()),
        )
        Log.i(TAG, "answered $functionName saved=$saved")
    }
}

/**
 * Receives the assistant's function calls. Protected by the assistant's own signature permission, so no other app
 * can fake a bookmark. The intent filter lists both the bare function name (as in the interface guide) and the
 * package-qualified one (what the car assistant actually sends).
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        GleoAssistant.onAction(context.applicationContext, action) { pending.finish() }
    }
}
