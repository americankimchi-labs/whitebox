package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Connecting the owner's phone, from the car's side. The parked car asks the mirror for a short code and shows a QR
 * with the code and a secret of its own (in the URL fragment, which browsers never send to a server). The phone
 * verifies its number with the mirror and returns its public key with an HMAC made with that secret. Only if the
 * MAC checks out does the car seal its root keys to the phone's key: a server that substituted its own key could
 * not make the MAC, so it can never obtain the keys to the records.
 *
 * Which keys: every epoch that belongs to the owner. The first owner gets everything recorded since installation;
 * the same owner on a new phone gets all of theirs again; a different owner (the car was sold) gets only what is
 * recorded from their request on: the car starts a new epoch the moment the request arrives, owned by nobody while
 * the current owner decides; approved (or not declined in time) it goes to the new owner, declined it goes back to
 * the owner. Nothing recorded during the wait is lost or read by the wrong person.
 */
class ClaimClient(private val channel: MirrorChannel, private val ring: KeyRing, dir: File) {

    sealed interface State {
        data object NotConnected : State
        data class Waiting(val code: String, val secret: String) : State
        data class Claimed(val maskedPhone: String) : State

        /** Another phone asked for this car; its owner is deciding. */
        data class TransferPending(val maskedPhone: String) : State
    }

    private val pendingFile = File(dir, "claim-pending")
    private val ownerFile = File(dir, "claim-owner")
    /** A takeover in progress: `transferId`, the requesting phone's key, its masked number. */
    private val transferFile = File(dir, "claim-transfer")
    private val _state = MutableStateFlow<State>(
        transfer()?.let { State.TransferPending(it.masked) } ?: owner()?.let { State.Claimed(it.masked) } ?: State.NotConnected,
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private class Owner(val id: String, val masked: String, val phoneKey: String)

    private fun owner(): Owner? = ownerFile.takeIf { it.exists() }?.readLines()?.takeIf { it.size >= 3 }?.let { Owner(it[0], it[1], it[2]) }

    private class Transfer(val id: String, val phoneKey: String, val masked: String) {
        val holder get() = "pending:$id"
    }

    private fun transfer(): Transfer? = transferFile.takeIf { it.exists() }?.readLines()?.takeIf { it.size >= 3 }?.let { Transfer(it[0], it[1], it[2]) }

    /** The owner tapped "connect a phone" on the parked car. */
    fun request(): Boolean {
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        pendingFile.writeText("|$secret") // the code arrives from the server
        return channel.send(MirrorProtocol.encode(Frame.ClaimRequest))
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        channel.incoming.collect { text ->
            when (val f = MirrorProtocol.decode(text)) {
                is Frame.ClaimCode -> pending()?.let { (_, secret) ->
                    pendingFile.writeText("${f.code}|$secret")
                    _state.value = State.Waiting(f.code, secret)
                }
                is Frame.Claimed -> onClaimed(f)
                is Frame.ClaimPending -> onClaimPending(f)
                is Frame.ClaimDeclined -> onDeclined(f.transferId)
                Frame.Rotate -> onRotate()
                else -> Unit
            }
        }
    }

    private fun pending(): Pair<String, String>? =
        pendingFile.takeIf { it.exists() }?.readText()?.split("|")?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    private fun macOk(phoneKey: String, mac: String): Boolean {
        val (code, secret) = pending() ?: return false
        return code.isNotEmpty() && MirrorProtocol.claimMac(Base64.getUrlDecoder().decode(secret), code, phoneKey) == mac
    }

    /** A different owner's phone asked for the car: from now on, records go into an epoch nobody holds yet. */
    @Synchronized
    private fun onClaimPending(f: Frame.ClaimPending) {
        val t = transfer()
        if (t?.id != f.transferId) {
            if (!macOk(f.phoneKey, f.mac)) return
            t?.let { ring.reassign(it.holder, owner()?.id.orEmpty()) } // an earlier request this one replaces
            transferFile.writeText("${f.transferId}\n${f.phoneKey}\n${f.maskedPhone}\n")
            pendingFile.delete() // the QR is used up; the takeover is decided by the server from here on
            ring.rotate("pending:${f.transferId}")
        }
        _state.value = State.TransferPending(f.maskedPhone)
        channel.send(MirrorProtocol.encode(Frame.TransferAck(f.transferId)))
    }

    /** The owner declined: what was recorded during the wait is theirs. */
    @Synchronized
    private fun onDeclined(transferId: String) {
        val t = transfer()
        val o = owner()
        if (t?.id == transferId && o != null) {
            ring.reassign(t.holder, o.id)
            transferFile.delete()
            wrapAll(o.id, o.phoneKey)
            _state.value = State.Claimed(o.masked)
        }
        channel.send(MirrorProtocol.encode(Frame.TransferAck(transferId)))
    }

    @Synchronized
    private fun onClaimed(f: Frame.Claimed) {
        val known = owner()
        val t = transfer()
        if (t != null && t.phoneKey == f.phoneKey && known?.id != f.ownerId) {
            // The takeover went through: the epoch started at the request is the new owner's (MAC checked then).
            ring.reassign(t.holder, f.ownerId)
            ownerFile.writeText("${f.ownerId}\n${f.maskedPhone}\n${f.phoneKey}\n")
            transferFile.delete()
        } else if (known?.id != f.ownerId || known.phoneKey != f.phoneKey) {
            // Already accepted this phone key (the server re-sends until it holds the wraps): answer again, idempotently.
            val (code, secret) = pending() ?: return
            if (code.isEmpty() || MirrorProtocol.claimMac(Base64.getUrlDecoder().decode(secret), code, f.phoneKey) != f.mac) return
            val currentOwner = ring.owners()[ring.current].orEmpty()
            when {
                currentOwner.isEmpty() -> ring.assignUnclaimed(f.ownerId)
                currentOwner != f.ownerId -> ring.rotate(f.ownerId) // a new owner: from now on only
            }
            ownerFile.writeText("${f.ownerId}\n${f.maskedPhone}\n${f.phoneKey}\n")
            pendingFile.delete()
        }
        wrapAll(f.ownerId, f.phoneKey)
        // The owner re-connecting their own phone does not end a takeover request that is still open.
        _state.value = transfer()?.let { State.TransferPending(it.masked) } ?: State.Claimed(f.maskedPhone)
    }

    /** An emergency contact was removed: a new epoch for the same owner, from now on. */
    @Synchronized
    private fun onRotate() {
        val o = owner() ?: return
        // During a takeover the car already records under a key nobody holds; whoever gets it, a removed contact won't.
        if (transfer() != null) return
        ring.rotate(o.id)
        wrapAll(o.id, o.phoneKey)
    }

    private fun wrapAll(ownerId: String, phoneKey: String) {
        val recipient = Base64.getDecoder().decode(phoneKey)
        for ((epoch, owner) in ring.owners()) {
            if (owner != ownerId) continue
            val sealed = SealedBox.seal(recipient, ring.key(epoch)!!, SealedBox.INFO_WRAP)
            channel.send(MirrorProtocol.encode(Frame.Wrap(ownerId, epoch, sealed)))
        }
    }
}
