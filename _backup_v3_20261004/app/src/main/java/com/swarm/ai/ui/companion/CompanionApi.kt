package com.swarm.ai.ui.companion

import com.swarm.ai.remote.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Application commands over the existing paired connection. Pairing, routing and storage are unchanged. */
class CompanionApi @Inject constructor(private val connection: RemoteClient) {
    private var cached: Pair<String, OkHttpClient>? = null

    suspend fun invoke(channel: String, vararg args: Any): Any? {
        val record = connection.pairing.value ?: throw RemoteException.Unauthorized()
        val route = connection.ensureRoute()
        val client = cached?.takeIf { it.first == record.fingerprint }?.second ?: run {
            val trust = FingerprintTrustManager(record.fingerprint)
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), java.security.SecureRandom()) }
            OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).hostnameVerifier { _, _ -> true }
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build().also { cached = record.fingerprint to it }
        }
        val bytes = JSONObject().put("channel", channel).put("args", JSONArray(args.toList())).toString().toByteArray()
        val request = Request.Builder().url(route.endpoint.baseUrl + "/v1/invoke")
            .post(bytes.toRequestBody("application/json".toMediaType()))
        RequestSigner(record.deviceId, record.deviceSecret, DeviceKey(record.deviceId).signer())
            .headers("POST", "/v1/invoke", bytes).forEach { (key, value) -> request.header(key, value) }
        // Mutations are never automatically retried: a lost response may still have committed.
        val result = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(RemoteException.Network(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = it.body.string()
                        if (!continuation.isActive) return
                        if (it.isSuccessful) continuation.resume(body)
                        else continuation.resumeWithException(when (it.code) {
                            401 -> RemoteException.Unauthorized()
                            404 -> IllegalStateException("Restart the updated SWARM desktop app to enable Build and Chat.")
                            else -> IllegalStateException(runCatching { JSONObject(body).optString("message").ifBlank { JSONObject(body).optString("error") } }.getOrDefault("PC request failed (${it.code})"))
                        })
                    }
                }
            })
        }
        return JSONObject(result).opt("data")?.takeUnless { it == JSONObject.NULL }
    }
}
