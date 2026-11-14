package com.americankimchilabs.whitebox.platform

import com.americankimchilabs.whitebox.core.ClipUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Uploads a clip the car attached by itself (built-in camera) to `<base>/v1/car/clips`, signed with the device key.
 * The recording service uses it as soon as the platform adapter has a camera source; until a head unit gives apps
 * camera access there is none, so nothing is uploaded.
 */
class HttpClipUpload(private val base: String, private val deviceId: String, private val stream: String) : ClipUpload {
    private val client = OkHttpClient.Builder().writeTimeout(5, TimeUnit.MINUTES).build()

    override suspend fun upload(capsuleId: String, clipId: String, offsetMs: Double, name: String, encrypted: File, signature: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val url = "$base/v1/car/clips".toHttpUrl().newBuilder()
                .addQueryParameter("device", deviceId).addQueryParameter("stream", stream).addQueryParameter("id", capsuleId)
                .addQueryParameter("clip", clipId).addQueryParameter("offsetMs", offsetMs.toString()).addQueryParameter("name", name)
                .addQueryParameter("sig", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
                .build()
            val request = okhttp3.Request.Builder().url(url).post(encrypted.asRequestBody("application/octet-stream".toMediaType())).build()
            runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }
}
