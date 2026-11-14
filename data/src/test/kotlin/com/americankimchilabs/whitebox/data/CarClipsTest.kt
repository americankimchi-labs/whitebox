package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.CameraClip
import com.americankimchilabs.whitebox.core.CameraClipSource
import com.americankimchilabs.whitebox.core.ClipUpload
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A built-in camera's clip, attached by the car: encrypted as the phone would, signed by the car, only on the plan. */
class CarClipsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theCarsOwnClipIsEncryptedWithTheCapsulesKeyAndSigned() = runTest {
        val ring = MemoryKeyRing()
        var wall = 1_700_000_000_000L
        val store = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { wall })
        repeat(20) { wall += 1_000; store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f + it), Timestamp(wall * 1_000, wall)))) }
        val signer = JdkSigner()
        val seal = CapsuleSeal.create(IncidentCapsule("C1", CapsuleTrigger.HARD_BRAKE, 5, 15), store.hashAt(15), true, wall, signer)
        val video = tmp.newFile("cam.mp4").apply { writeBytes(ByteArray(ClipFormat.PIECE + 777) { (it % 249).toByte() }) }
        class Sent(val clipId: String, val offsetMs: Double, val bytes: ByteArray, val signature: ByteArray)
        var sent: Sent? = null
        var allowed = false
        val clips = CarClips(
            store,
            object : CameraClipSource { override suspend fun clip(fromWall: Long, toWall: Long) = CameraClip(video, fromWall - 2_000, "front.mp4") },
            object : ClipUpload {
                override suspend fun upload(capsuleId: String, clipId: String, offsetMs: Double, name: String, encrypted: File, signature: ByteArray): Boolean {
                    sent = Sent(clipId, offsetMs, encrypted.readBytes(), signature)
                    return true
                }
            },
            signer, { e, m -> ring.key(e)?.let { RecordCipher.minuteKey(it, m) } }, tmp.newFolder(), { allowed },
        )
        assertFalse(clips.onSeal(seal, wall)) // not on this plan: nothing taken from the camera
        allowed = true
        assertTrue(clips.onSeal(seal, wall))

        val s = sent!!
        val clipId = s.clipId
        val sealed = s.bytes
        // Opens with the key from the capsule's first minute, exactly as the phone derives it.
        val first = store.records(5, 15).mapNotNull { MirrorProtocol.Record.parse(it)?.entry }.flatMap { it.split("|") }.first { it.startsWith(RecordCipher.PREFIX_V3) }
        val key = ClipFormat.key(RecordCipher.minuteKey(ring.key(RecordCipher.epochOf(first))!!, RecordCipher.minuteOf(first)!!), clipId)
        val plain = generateSequence(0) { it + ClipFormat.PIECE + 28 }.takeWhile { it < sealed.size }.map { at ->
            val piece = sealed.copyOfRange(at, minOf(sealed.size, at + ClipFormat.PIECE + 28))
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, piece, 0, 12)) }.doFinal(piece, 12, piece.size - 12)
        }.reduce { a, b -> a + b }
        assertArrayEquals(video.readBytes(), plain)
        // Signed by the car over the capsule, the clip and its exact bytes: the cloud can check which car sent it.
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(signer.publicKey)))
            update(ClipFormat.signedText("C1", clipId, s.offsetMs, MessageDigest.getInstance("SHA-256").digest(sealed).toHex()).toByteArray())
            verify(s.signature)
        })
    }

    @Test
    fun attachMeasuresTheClipFromTheDetectionAndTriesAgainUntilTheCloudTakesIt() = runTest {
        val ring = MemoryKeyRing()
        var wall = 1_700_000_000_000L
        val store = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { wall })
        fun at() = Timestamp(wall * 1_000, wall)
        repeat(10) { wall += 1_000; store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(20f - it), at()))) } // seq 0..9
        val causeWall = wall // seq 9 is what the detection points at
        wall += 5
        store.append(ChainEntry.Escalation(EscalationEvent.LevelChanged(EscalationLevel.ALERT, 9), at())) // seq 10
        store.append(ChainEntry.Escalation(EscalationEvent.Triggered(CapsuleTrigger.HARD_BRAKE, 9, 21f), at())) // seq 11
        repeat(5) { wall += 1_000; store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(0f), at()))) } // seq 12..16
        val signer = JdkSigner()
        val capsule = IncidentCapsule("C9", CapsuleTrigger.HARD_BRAKE, 0, 16)
        val video = tmp.newFile("cam2.mp4").apply { writeBytes(ByteArray(4_000) { it.toByte() }) }
        val offsets = mutableListOf<Double>()
        var accept = false
        var asked = 0
        val clips = CarClips(
            store,
            object : CameraClipSource {
                override suspend fun clip(fromWall: Long, toWall: Long): CameraClip { asked++; return CameraClip(video, causeWall - 3_000, "front.mp4") }
            },
            object : ClipUpload {
                override suspend fun upload(capsuleId: String, clipId: String, offsetMs: Double, name: String, encrypted: File, signature: ByteArray): Boolean {
                    offsets += offsetMs
                    return accept.also { accept = true } // the cloud does not have the seal yet the first time
                }
            },
            signer, { e, m -> ring.key(e)?.let { RecordCipher.minuteKey(it, m) } }, tmp.newFolder(), { true },
        )
        assertEquals(causeWall, causeWall(capsule, store.read(0, 16)))
        // A provisional seal is not the end of the record: the camera is not asked.
        assertFalse(clips.attach(CapsuleSeal.create(capsule, store.hashAt(16), false, wall, signer)))
        assertEquals(0, asked)
        assertTrue(clips.attach(CapsuleSeal.create(capsule, store.hashAt(16), true, wall, signer), tries = 3, retryMs = 10))
        // Its first frame is 3 s before the detection, as a viewer will place it; refused once, taken the second time.
        assertEquals(listOf(-3_000.0, -3_000.0), offsets)
    }

    @Test
    fun theCauseIsTheTriggerThatNamesTheCapsuleElseTheFirstAlertElseItsLastRecord() = runTest {
        var wall = 1_700_000_000_000L
        val store = FileEventStore(tmp.newFolder())
        fun at() = Timestamp(wall * 1_000, wall)
        val walls = ArrayList<Long>()
        repeat(6) { wall += 1_000; walls += wall; store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f), at()))) } // seq 0..5
        store.append(ChainEntry.Escalation(EscalationEvent.LevelChanged(EscalationLevel.ALERT, 2), at())) // seq 6
        assertEquals(walls[2], causeWall(IncidentCapsule("S", CapsuleTrigger.HARD_BRAKE, 0, 6), store.read(0, 6))) // no trigger: the alert
        store.append(ChainEntry.Escalation(EscalationEvent.Triggered(CapsuleTrigger.HARD_BRAKE, 3, 20f), at())) // seq 7
        store.append(ChainEntry.Escalation(EscalationEvent.Triggered(CapsuleTrigger.DELTA_V, 5, 18f), at())) // seq 8: a crash takes it over
        val events = store.read(0, 8)
        assertEquals(walls[5], causeWall(IncidentCapsule("C3", CapsuleTrigger.DELTA_V, 0, 8), events))
        assertEquals(walls[3], causeWall(IncidentCapsule("C3", CapsuleTrigger.HARD_BRAKE, 0, 8), events))
        assertEquals(walls[1], causeWall(IncidentCapsule("X", CapsuleTrigger.MANUAL, 0, 1), store.read(0, 1))) // nothing marked: its last record
    }
}
