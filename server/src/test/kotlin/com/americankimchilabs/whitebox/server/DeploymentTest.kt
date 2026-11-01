package com.americankimchilabs.whitebox.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** What a deployment changes: keys from the secret store, the dashboard behind a password, and the store seam. */
class DeploymentTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keysFromTheSecretStoreSignAndOpenAsKeysFromDisk() {
        val secret = ServerKey.secret()
        val a = ServerKey.fromSecret(secret)
        val b = ServerKey.fromSecret(secret)
        assertArrayEquals(a.publicKey, b.publicKey)
        assertEquals("hello", b.decryptAtRest(a.encryptAtRest("hello", "test"), "test"))
        assertEquals(a.keyedHash("p", "010"), b.keyedHash("p", "010"))
    }

    @Test
    fun theDashboardAsksForItsPasswordAndThePhonePagesDoNot() = testApplication {
        val dir = tmp.newFolder()
        val service = MirrorService(FileStreamStore(dir), ServerKey(dir))
        application { whiteBox(service, tickMs = 60_000, dashboardPassword = "s3cret") }
        assertEquals(401, client.get("/").status.value)
        assertEquals(401, client.get("/dashboard/cards") { header("Authorization", "Basic " + Base64.getEncoder().encodeToString("x:wrong".toByteArray())) }.status.value)
        assertEquals(200, client.get("/") { header("Authorization", "Basic " + Base64.getEncoder().encodeToString("judge:s3cret".toByteArray())) }.status.value)
        assertEquals(200, client.get("/health").status.value)
    }

    @Test
    fun aLogReplacedWholeReadsBackAsTheNewOneAndOthersStay() {
        val d = FileDurable(tmp.newFolder())
        d.append("streams/car/live/records", listOf("1", "2"))
        d.append("streams/car/live/seals", "s")
        d.replace("streams/car/live/records", listOf("2"))
        d.append("streams/car/live/records", "3")
        assertEquals(listOf("2", "3"), d.lines("streams/car/live/records"))
        assertEquals(listOf("streams/car/live/records", "streams/car/live/seals"), d.logs("streams/"))
        assertTrue(d.exists("streams/car/live/seals"))
    }
}
