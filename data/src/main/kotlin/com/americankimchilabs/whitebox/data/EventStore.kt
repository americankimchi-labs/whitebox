package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.SealedChain

/**
 * Append-only local log: the original record (local is the source of truth, the cloud is a mirror).
 */
interface EventStore {
    /** Assigns the next seq, links it into the hash chain and returns only after the write is durable (fsync). */
    fun append(entry: ChainEntry): DriveEvent

    /** Inclusive range, in seq order. */
    fun read(fromSeq: Long, toSeq: Long): List<DriveEvent>

    /** Null while the log is empty. */
    fun head(): SealedChain?

    /** Recomputes every hash in the inclusive range and checks each link to its predecessor. */
    fun verify(fromSeq: Long, toSeq: Long): ChainCheck
}

sealed interface ChainCheck {
    /** [headHash] is the hash of the record at the end of the range. */
    class Intact(val headHash: ByteArray) : ChainCheck

    data class Broken(val seq: Long, val reason: String) : ChainCheck
}

/**
 * Device signing key. ECDSA P-256 / SHA256withECDSA, because both the JDK (tests) and
 * Android Keystore (:app) support it. The key is scoped to the owner's phone identity, never the VIN.
 */
interface Signer {
    /** X.509 (SubjectPublicKeyInfo) encoding. */
    val publicKey: ByteArray

    fun sign(data: ByteArray): ByteArray
}
