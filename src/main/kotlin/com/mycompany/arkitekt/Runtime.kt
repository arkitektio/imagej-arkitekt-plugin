package com.mycompany.arkitekt

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloRequest
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import com.mycompany.lok.graphql.MeQuery
import com.mycompany.mikro.graphql.CreateAnnotationCollectionMutation
import com.mycompany.mikro.graphql.GetAnnotationCollectionQuery
import com.mycompany.mikro.graphql.CreateAnnotationMutation
import com.mycompany.mikro.graphql.UpdateAnnotationMutation
import com.mycompany.mikro.graphql.CreateArrayDatasetMutation
import com.mycompany.mikro.graphql.GetArrayDatasetQuery
import com.mycompany.mikro.graphql.GetLensQuery
import com.mycompany.mikro.graphql.FinishZarrUploadMutation
import com.mycompany.mikro.graphql.RequestZarrAccessMutation
import com.mycompany.mikro.graphql.RequestZarrUploadMutation
import com.mycompany.mikro.graphql.type.AxisInput
import com.mycompany.mikro.graphql.type.AxisType
import com.mycompany.mikro.graphql.type.CoordinateInput
import com.mycompany.mikro.graphql.type.CreatableTransformKind
import com.mycompany.mikro.graphql.type.AnnotationKind
import com.mycompany.mikro.graphql.type.CreateAnnotationCollectionInput
import com.mycompany.mikro.graphql.type.CreateAnnotationInput
import com.mycompany.mikro.graphql.type.DerivationSourceKind
import com.mycompany.mikro.graphql.type.DerivedFromInput
import com.mycompany.mikro.graphql.type.TransformInput
import com.mycompany.mikro.graphql.type.UpdateAnnotationInput
import com.mycompany.mikro.graphql.type.CreateArrayDatasetInput
import com.mycompany.mikro.graphql.type.FinishZarrUploadInput
import com.mycompany.mikro.graphql.type.RequestZarrAccessInput
import com.mycompany.mikro.graphql.type.RequestZarrUploadInput
import com.mycompany.rekuest.graphql.type.*
import dev.zarr.zarrjava.store.S3Store
import dev.zarr.zarrjava.store.StoreHandle
import dev.zarr.zarrjava.v3.Array
import dev.zarr.zarrjava.v3.DataType
import ucar.ma2.DataType as UcarDataType
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.imagej.DatasetService
import net.imagej.ImgPlus
import net.imagej.axis.Axes
import net.imagej.axis.CalibratedAxis
import net.imagej.axis.DefaultLinearAxis
import net.imagej.display.ImageDisplay
import net.imagej.display.ImageDisplayService
import net.imagej.display.OverlayService
import net.imglib2.RandomAccess
import net.imglib2.type.numeric.RealType
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.scijava.ui.UIService
import java.util.*
import java.util.prefs.Preferences
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import dev.zarr.zarrjava.v3.Group;
import net.imagej.ImageJ
import net.imagej.Dataset
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.integer.ByteType
import net.imglib2.type.numeric.integer.IntType
import net.imglib2.type.numeric.integer.ShortType
import net.imglib2.type.numeric.integer.UnsignedByteType
import net.imglib2.type.numeric.integer.UnsignedIntType
import net.imglib2.type.numeric.integer.UnsignedShortType
import net.imglib2.type.numeric.real.FloatType
import ucar.ma2.*
import org.scijava.Context
import org.scijava.convert.ConvertService
import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import net.imglib2.Cursor
import com.apollographql.apollo.interceptor.ApolloInterceptor
import com.apollographql.apollo.interceptor.ApolloInterceptorChain
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.logging.Logger
import com.apollographql.apollo.api.Operation
import net.imglib2.type.numeric.real.DoubleType

/**
 * Runs an [App]: logs it in to a deployment, builds its [Services] and serves its actions as
 * an agent. The app says what it is; everything that touches a server happens here.
 */
class Runtime(
        private val app: App,
        private val uiService: UIService,
        private val datasetService: DatasetService,
        private val imageDisplayService: ImageDisplayService
) {
    private val client = OkHttpClient()

    // The background coroutine running the agent's provide loop (WebSocket to /agi).
    // Tracked so logout() can tear the connection down.
    private var provideJob: Job? = null

    // The background coroutine running an in-flight login (device-code challenge poll).
    // Tracked so it can be cancelled (cancelLogin()/logout()) mid-flight.
    private var loginJob: Job? = null

    // The app's manifest: what the deployment is shown at login. The lok/management endpoint
    // arrives as ActiveFakts.self.alias, so it is not a requirement of any app.
    private val manifest =
            Manifest(
                    identifier = app.identifier,
                    version = app.version,
                    scopes = app.scopes.toList(),
                    description = app.description,
                    node_id = NodeId.getOrSet(),
                    requirements = app.requirements.toList()
            )

    // The services that must be granted and reachable for the app to function.
    private val requiredKeys = app.requirements.filter { !it.optional }.map { it.key }

    private val cache = FaktsCache(File(System.getProperty("user.home"), ".arkitekt/fakts_cache.json"))

    // The "v2:" prefix binds the key to the protocol generation, so a protocol-1 record can
    // never be mistaken for a protocol-2 one even if the manifest and url are unchanged.
    private fun cacheHash(url: String): String {
        val raw = "v2:" + faktsJson.encodeToString(manifest) + "|" + url
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * True if a login for [url] can proceed silently, without the device-code browser flow.
     *
     * Under protocol 2 that is not "do I have a config" but "do I hold a refresh token": the
     * config alone is inert, because there are no client credentials left to mint a token from.
     */
    fun hasCachedConfig(url: String): Boolean =
            cache.load(cacheHash(url))?.token?.refresh_token != null

    // The protocol driver lives in Fakts.kt; this class only sequences it and owns the cache.
    private val fakts = FaktsClient(client)

    // Open the approval page. `verification_uri_complete` already has the user code substituted;
    // `verification_uri` still carries the literal `{code}`, so it is only a last-resort fallback
    // the human has to complete by hand.
    private fun openApprovalPage(authorization: DeviceAuthorization) {
        val deviceUrl = authorization.verification_uri_complete
                ?: authorization.verification_uri?.replace(
                        "{code}",
                        authorization.user_code.orEmpty()
                )
                ?: throw DemandError("Device authorization carried no verification URI to approve at.")

        val osName = System.getProperty("os.name").lowercase()
        try {
            when {
                osName.contains("win") ->
                        ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", deviceUrl).start()
                osName.contains("mac") -> ProcessBuilder("open", deviceUrl).start()
                osName.contains("nix") || osName.contains("nux") ->
                        ProcessBuilder("xdg-open", deviceUrl).start()
                else -> println("Please open this URL to approve the app: $deviceUrl")
            }
        } catch (e: Exception) {
            println("Could not open a browser automatically. Approve the app at: $deviceUrl")
        }
        println("Waiting for approval at: $deviceUrl")
    }

    // The full interactive grant: discover -> device authorize -> human approves -> poll.
    // There is no separate claim step any more; the token response carries the config with it.
    suspend fun negotiate(url: String): FaktsSession {
        val endpoint = fakts.discover(url)
        val authorization = fakts.deviceAuthorize(endpoint, manifest)

        openApprovalPage(authorization)

        val grant =
                fakts.pollToken(
                        // The device-auth response re-states the token endpoint; prefer it.
                        tokenEndpoint = authorization.token_endpoint ?: endpoint.token_endpoint,
                        deviceCode = authorization.device_code,
                        clientId = authorization.client_id,
                        interval = authorization.interval,
                        expiresIn = authorization.expires_in
                )

        return FaktsSession(cacheHash(url), endpoint, grant.fakts, grant.token)
    }

    // Cache-first session loading. Returns (session, fromCache); fromCache enables self-healing.
    suspend fun getSession(url: String, forceRefresh: Boolean = false): Pair<FaktsSession, Boolean> {
        if (!forceRefresh) {
            cache.load(cacheHash(url))?.let { return it to true }
        }
        val session = negotiate(url)
        cache.save(session)
        return session to false
    }


    fun login(
            url: String,
            onSuccess: (MeQuery.Data) -> Unit,
            onError: (Throwable) -> Unit = {}
    ) {
        // Cancel any in-flight attempt before starting a new one (e.g. rapid Re-Login).
        loginJob?.cancel()
        ArkitektState.setState(ConnState.Connecting)
        loginJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = alogin(url)
                ArkitektState.setState(ConnState.Connected(result.me.username))
                withContext(Dispatchers.Main) { onSuccess(result) }
            } catch (e: CancellationException) {
                // Cancelled by the user (clicked the button mid-challenge); state is set to
                // Disconnected by cancelLogin(), so don't surface this as an error.
                throw e
            } catch (e: Exception) {
                println("Failed to login: ${e}")
                ArkitektState.setState(ConnState.Error(e.message ?: e.toString()))
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    /**
     * Cancel an in-flight login (the device-code challenge poll). Safe to call when nothing is
     * running. Resets the shared state to Disconnected.
     */
    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        ArkitektState.setState(ConnState.Disconnected)
    }


    suspend fun alogin(url: String): MeQuery.Data {

        // Resolve a granted session. A refresh rejected by the server means the cached refresh
        // chain is dead (revoked, superseded, or simply too old), so wipe the cache and run the
        // device flow from scratch — but only once, to avoid an approval loop.
        var forceRefresh = false
        var session: FaktsSession
        var instanceMap: Map<String, Alias>
        var tokens: TokenManager

        while (true) {
            // Cache-first: load the granted session, negotiating only on a cache miss (or when a
            // prior refresh failure forced a re-negotiation).
            val loaded = getSession(url, forceRefresh = forceRefresh)
            session = loaded.first
            var fromCache = loaded.second

            // Every required service must have been granted AND resolve to a reachable alias.
            var (failure, resolved) = compose(session)
            instanceMap = resolved

            // A cached session can go stale in two ways — a requirement stops being granted, or
            // its aliases stop answering — and both are fixed the same way: re-negotiate exactly
            // once and re-run the whole check before giving up.
            if (fromCache && failure != null) {
                println("Cached session no longer composes ($failure); re-negotiating once.")
                cache.clear()
                session = getSession(url, forceRefresh = true).first
                fromCache = false
                val recomposed = compose(session)
                failure = recomposed.first
                instanceMap = recomposed.second
            }

            failure?.let { throw CompositionError(it) }

            tokens = buildTokenManager(url, session)

            // A cached access token is very likely expired; prove the credentials still work
            // before wiring four clients to them.
            try {
                tokens.accessToken()
                break
            } catch (e: TokenError) {
                if (fromCache && !forceRefresh) {
                    println(
                            "The cached refresh token was rejected (${e.statusCode ?: "transport"}); " +
                                    "clearing the cache and restarting the device flow from scratch."
                    )
                    cache.clear()
                    forceRefresh = true
                    continue
                }
                throw e
            }
        }

        // NB: `fakts` on this class is the protocol client; the envelope is session.fakts.
        val envelope = session.fakts
        var rekuest = Rekuest(instanceMap["rekuest"]!!, tokens)
        var unlok = Unlok(envelope.self.alias, tokens) // lok is the deployment (self.alias)
        var mikro = Mikro(instanceMap["mikro"]!!, tokens)
        var datalayer = Datalayer(instanceMap["datalayer"]!!, mikro)

        val services =
            Services(
                mikro,
                datalayer,
                unlok,
                rekuest,
                uiService,
                datasetService,
                imageDisplayService
            )

        // Tell the deployment which aliases actually answered. Best-effort; never throws.
        val aliasReports = envelope.instances.keys.associateWith { key ->
            val alias = instanceMap[key]
            if (alias != null) AliasReport(valid = true, alias_id = alias.id)
            else AliasReport(valid = false, reason = "No working alias found for service: $key")
        }
        fakts.report(
                session.endpoint,
                tokens.accessToken(),
                aliasReports,
                functional = aliasReports.values.all { it.valid }
        )

        val agent = Agent(instanceMap["rekuest"]!!, tokens, app, services)

        agent.createAgent(app.identifier)
        agent.registerFunctions()

        provideJob =
                CoroutineScope(Dispatchers.Default).launch {
                    agent.provideForever()
                }

        return unlok.getUser()
    }

    /**
     * Check every required service is granted and resolve it to a reachable alias.
     *
     * Returns (failure, instanceMap), where a null failure means composed. It reports rather than
     * throws so the caller can distinguish "this cached session went stale" — worth exactly one
     * re-negotiation — from "the deployment genuinely will not serve us".
     */
    private suspend fun compose(session: FaktsSession): Pair<String?, Map<String, Alias>> {
        val ungranted = requiredKeys.filter { key ->
            val status = GrantStatus.from(session.fakts.statuses[key])
            session.fakts.instances[key] == null ||
                    status == GrantStatus.DENIED ||
                    status == GrantStatus.UNAVAILABLE
        }
        if (ungranted.isNotEmpty()) {
            val detail = ungranted.joinToString {
                "$it=${GrantStatus.from(session.fakts.statuses[it])}"
            }
            return "Required service(s) not granted: $detail" to emptyMap()
        }

        val instanceMap = buildInstanceMap(session.fakts)
        val unreachable = requiredKeys.filter { instanceMap[it] == null }
        val failure =
                if (unreachable.isEmpty()) null
                else "No reachable alias for required service(s): ${unreachable.joinToString()}"
        return failure to instanceMap
    }

    /**
     * The single token owner for this login. Every rotation is persisted back to the cache
     * before the new access token is handed out: the refresh token rotates on use, so a lost
     * one costs the user a browser round-trip on the next start.
     */
    private fun buildTokenManager(url: String, session: FaktsSession): TokenManager {
        val hash = cacheHash(url)
        return TokenManager(fakts, session.endpoint, session.token, session.fakts) {
            endpoint, activeFakts, token ->
            cache.save(FaktsSession(hash, endpoint, activeFakts, token))
        }
    }

    fun logout() {
        // Tear down the live agent connection (WebSocket provide loop) and any in-flight login.
        provideJob?.cancel()
        provideJob = null
        loginJob?.cancel()
        loginJob = null
        // Clear the cached configuration so the next login re-negotiates from scratch.
        cache.clear()
        // Remove the legacy token key written by older versions of this plugin.
        val prefs = Preferences.userNodeForPackage(Runtime::class.java)
        prefs.remove("token")
        ArkitektState.setState(ConnState.Disconnected)
    }
}
