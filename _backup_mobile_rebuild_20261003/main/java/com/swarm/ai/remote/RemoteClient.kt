package com.swarm.ai.remote

import android.content.Context
import com.swarm.ai.net.connectivity.RaceRouteConnector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import javax.inject.Singleton

// ------------------------------------------------------------------ route resolution (pluggable)

data class ConnectionInfo(val hosts: List<String>, val fingerprint: String)

/**
 * Finds a reachable [Route] to the PC. Implementations are tried in order by [CompositeRouteConnector]; add new
 * connection strategies by implementing this interface.
 */
interface RouteConnector {
    suspend fun resolve(info: ConnectionInfo, preferred: Route?): Route?
}

/**
 * Direct peer-to-peer probing: tries the last working route first, then the QR host list in priority order
 * (Home network -> Public IPv6 -> Mapped port -> Manual). Hosts of the same kind are probed in parallel,
 * kinds are tried one after another, each probe uses a short pinned-TLS connect timeout.
 */
class DirectRouteConnector(
    private val transport: RemoteTransport,
    private val probeTimeoutMs: Int = 2500
) : RouteConnector {
    override suspend fun resolve(info: ConnectionInfo, preferred: Route?): Route? {
        if (preferred != null && transport.ping(preferred, info.fingerprint, probeTimeoutMs)) return preferred
        val groups = RouteClassifier.classify(info.hosts).groupBy { it.kind } // LinkedHashMap keeps QR order
        for ((_, group) in groups) {
            val results = coroutineScope {
                group.map { r -> async { r to transport.ping(r, info.fingerprint, probeTimeoutMs) } }.awaitAll()
            }
            results.firstOrNull { it.second }?.let { return it.first }
        }
        return null
    }
}

class CompositeRouteConnector(private val connectors: List<RouteConnector>) : RouteConnector {
    override suspend fun resolve(info: ConnectionInfo, preferred: Route?): Route? {
        for (c in connectors) c.resolve(info, preferred)?.let { return it }
        return null
    }
}

// ------------------------------------------------------------------ client

class RemoteClient(
    private val store: PairingStore,
    private val transport: RemoteTransport,
    private val connector: RouteConnector,
    private val deviceName: () -> String = { android.os.Build.MODEL ?: "Android device" }
) {
    private val _pairing = MutableStateFlow(store.load())
    val pairing: StateFlow<PairingRecord?> = _pairing.asStateFlow()

    private val _route = MutableStateFlow<Route?>(null)
    /** The route currently in use (null when unknown / being re-resolved). */
    val activeRoute: StateFlow<Route?> = _route.asStateFlow()

    private val resolveLock = Mutex()

    val isPaired: Boolean get() = _pairing.value != null

    // ---- pairing

    /** Parses the QR text, finds a route, registers this phone (device key + secret) and stores the result. */
    suspend fun pair(qrText: String): PairingRecord {
        val qr = QrPayloadParser.parse(qrText)
        val info = ConnectionInfo(qr.hosts, qr.fingerprint)
        val route = connector.resolve(info, null) ?: throw RemoteException.NoRoute()

        val deviceId = UUID.randomUUID().toString()
        val key = DeviceKey(deviceId)
        try {
            val pub = key.ensurePublicKeyB64()
            val proof = Codec.b64Url(
                key.signer().sign(RequestSigner.pairProofPayload(qr.token, deviceId, pub).toByteArray(Charsets.UTF_8))
            )
            val body = JSONObject()
                .put("token", qr.token).put("deviceName", deviceName()).put("deviceId", deviceId)
                .put("publicKey", pub).put("proof", proof).toString()
            val res = transport.postUnauthenticated(route, qr.fingerprint, "/v1/pair", body)
            if (res.code == 429) throw RemoteException.RateLimited(res.retryAfterSec ?: 30)
            if (!res.isSuccess) throw RemoteException.Http(res.code, res.json().optString("error", ""))
            val j = res.json()
            val secret = j.optString("deviceSecret", "")
            if (secret.isEmpty()) throw RemoteException.Http(res.code, "bad_request")
            val record = PairingRecord(
                deviceId = deviceId,
                deviceSecret = secret,
                fingerprint = qr.fingerprint,
                hosts = qr.hosts,
                pcName = j.optString("pcName", qr.pcName).ifEmpty { qr.pcName },
                lastRoute = route.endpoint.raw
            )
            store.save(record)
            _pairing.value = record
            _route.value = route
            return record
        } catch (e: Exception) {
            key.delete()
            throw e
        }
    }

    fun unpair() {
        _pairing.value?.let { DeviceKey(it.deviceId).delete() }
        store.clear()
        _pairing.value = null
        _route.value = null
    }

    // ---- routing

    fun invalidateRoute() {
        _route.value = null
    }

    suspend fun ensureRoute(force: Boolean = false): Route {
        val rec = _pairing.value ?: throw RemoteException.Unauthorized()
        if (!force) _route.value?.let { return it }
        return resolveLock.withLock {
            if (!force) _route.value?.let { return it }
            val preferred = rec.lastRoute?.let { last -> RouteClassifier.classify(listOf(last)).firstOrNull() }
            val route = connector.resolve(ConnectionInfo(rec.hosts, rec.fingerprint), preferred)
                ?: throw RemoteException.NoRoute()
            _route.value = route
            if (route.endpoint.raw != rec.lastRoute) {
                val updated = rec.copy(lastRoute = route.endpoint.raw)
                store.save(updated)
                _pairing.value = updated
            }
            route
        }
    }

    // ---- authenticated calls

    private fun signerFor(rec: PairingRecord) =
        RequestSigner(rec.deviceId, rec.deviceSecret, DeviceKey(rec.deviceId).signer())

    private suspend fun authed(method: String, path: String, body: String? = null): JSONObject {
        var lastError: RemoteException = RemoteException.NoRoute()
        for (attempt in 0..1) {
            val rec = _pairing.value ?: throw RemoteException.Unauthorized()
            val route = ensureRoute(force = attempt > 0)
            val res = try {
                transport.request(route, rec.fingerprint, method, path, body?.toByteArray(Charsets.UTF_8), signerFor(rec))
            } catch (e: RemoteException.Network) {
                invalidateRoute()
                lastError = e
                continue
            }
            when {
                res.code == 401 -> {
                    unpair()
                    throw RemoteException.Unauthorized()
                }
                res.code == 429 -> throw RemoteException.RateLimited(res.retryAfterSec ?: 5)
                !res.isSuccess -> throw RemoteException.Http(res.code, res.json().optString("error", ""))
                else -> return res.json()
            }
        }
        throw lastError
    }

    suspend fun listAgents(): List<RemoteAgent> = RemoteJson.parseAgents(authed("GET", "/v1/agents"))

    suspend fun status(): JSONObject = authed("GET", "/v1/status")

    suspend fun startAgent(id: String): RemoteAgent? = agentCommand(id, "start", null)

    suspend fun stopAgent(id: String): RemoteAgent? = agentCommand(id, "stop", null)

    suspend fun sendTask(id: String, text: String): RemoteAgent? =
        agentCommand(id, "task", JSONObject().put("text", text).toString())

    private suspend fun agentCommand(id: String, action: String, body: String?): RemoteAgent? {
        val j = authed("POST", "/v1/agents/${OkHttpTransport.encodeSegment(id)}/$action", body ?: "")
        return j.optJSONObject("agent")?.let { RemoteJson.parseAgent(it) }
    }

    /** Live event stream (WebSocket `/v1/ws`). Completes when the socket closes or fails. */
    fun events(): Flow<WsEvent> = flow {
        val rec = _pairing.value ?: throw RemoteException.Unauthorized()
        val route = ensureRoute()
        emitAll(transport.events(route, rec.fingerprint, signerFor(rec)))
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {
    @Provides
    @Singleton
    fun provideRemoteClient(@ApplicationContext context: Context): RemoteClient {
        val transport = OkHttpTransport()
        // Race (LAN -> IPv6 -> mapped/manual, staggered, pinned-TLS ping) first; sequential probing as safety net.
        val connector = CompositeRouteConnector(
            listOf(
                RaceRouteConnector(onResult = { android.util.Log.d("Connectivity", it.summary()) }),
                DirectRouteConnector(transport)
            )
        )
        return RemoteClient(PairingStore(context), transport, connector)
    }
}