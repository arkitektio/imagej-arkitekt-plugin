package com.mycompany.arkitekt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import live.arkitekt.Runtime
import live.arkitekt.lok.graphql.MeQuery
import net.imagej.DatasetService
import net.imagej.display.ImageDisplayService
import org.scijava.ui.UIService
import java.util.prefs.Preferences

/**
 * The plugin's one connection to a deployment, as the toolbar button and the dialog drive it.
 *
 * The work is arkitekt-kotlin's: [imagejApp] declares the app and a [Runtime] logs it in and
 * serves it. This class only keeps the two background jobs (a login in flight, the serving
 * agent) and tells [ArkitektState] how far things are.
 */
class Arkitekt(
        uiService: UIService,
        datasetService: DatasetService,
        imageDisplayService: ImageDisplayService
) {
    private val runtime = Runtime(imagejApp(uiService, datasetService, imageDisplayService))

    // The background coroutine serving the app (the agent's WebSocket to /agi).
    private var provideJob: Job? = null

    // The background coroutine running an in-flight login (device-code challenge poll).
    private var loginJob: Job? = null

    /** True if a login for [url] can proceed silently, without the browser. */
    fun hasCachedConfig(url: String): Boolean = runtime.hasCachedLogin(url)

    /** Log in and start serving. Throws what the login throws. */
    suspend fun alogin(url: String): MeQuery.Data {
        val connection = runtime.connect(url)
        provideJob = CoroutineScope(Dispatchers.Default).launch { connection.serve() }
        return connection.context.unlok.getUser()
    }

    fun login(
            url: String,
            onSuccess: (MeQuery.Data) -> Unit,
            onError: (Throwable) -> Unit = {}
    ) {
        loginJob?.cancel()
        ArkitektState.setState(ConnState.Connecting)
        loginJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                val result = alogin(url)
                ArkitektState.setState(ConnState.Connected(result.me.username))
                withContext(Dispatchers.Main) { onSuccess(result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Failed to login: ${e}")
                ArkitektState.setState(ConnState.Error(e.message ?: e.toString()))
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        ArkitektState.setState(ConnState.Disconnected)
    }

    fun logout() {
        // Tear down the serving agent and any in-flight login.
        provideJob?.cancel()
        provideJob = null
        loginJob?.cancel()
        loginJob = null
        // Forget the kept login so the next one re-negotiates from scratch.
        runtime.forget()
        // Remove the legacy token key written by older versions of this plugin.
        Preferences.userNodeForPackage(Arkitekt::class.java).remove("token")
        ArkitektState.setState(ConnState.Disconnected)
    }
}
