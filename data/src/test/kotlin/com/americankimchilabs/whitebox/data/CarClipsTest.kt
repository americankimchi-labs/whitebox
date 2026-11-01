package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.CameraClip
import com.americankimchilabs.whitebox.core.CameraClipSource
import com.americankimchilabs.whitebox.core.ClipUpload
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
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
}
