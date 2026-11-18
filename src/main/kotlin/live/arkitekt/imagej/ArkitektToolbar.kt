package live.arkitekt.imagej

import ij.IJ
import ij.gui.GenericDialog
import ij.gui.Toolbar
import ij.plugin.tool.MacroToolRunner
import net.imagej.DatasetService
import net.imagej.display.ImageDisplayService
import org.scijava.Context
import org.scijava.Priority
import org.scijava.plugin.Plugin
import org.scijava.service.AbstractService
import org.scijava.service.Service
import org.scijava.ui.UIService
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.MouseInfo
import java.awt.PopupMenu

// =========================================================================================
// Arkitekt in Fiji's toolbar.
//
// Fiji's toolbar is ImageJ 1's: a SciJava `Tool` (ArkitektTool) never shows there, only in the
// ImageJ2 UI. So the button is an ImageJ 1 tool, added to one of the toolbar's free slots once
// Fiji is up. It is an "Action Tool": a click runs it and leaves the current tool (rectangle,
// line, ...) selected, as Fiji's own action tools do. A click (or a right click) opens a menu:
// the connection and server, log in or out, the server to use, and the Arkitekt window.
//
// Its icon is the Arkitekt logo, in a colour that says how things stand: grey (not connected),
// amber (connecting), the logo's violet (connected), red (the last login failed).
// =========================================================================================

/** The toolbar button. Extends MacroToolRunner because only those are treated as action tools. */
class ArkitektToolbarTool(private val context: Context) : MacroToolRunner(null) {

    private var shown: PopupMenu? = null

    override fun getToolName(): String = NAME

    override fun getToolIcon(): String = icon(ArkitektState.state)

    /** A click (`NAME`) and a right click (`NAME Options`) both open the menu. */
    override fun runMacroTool(name: String?) {
        EventQueue.invokeLater { showMenu() }
    }

    override fun runMenuTool(name: String?, command: String?) {}

    override fun showOptionsDialog() {
        EventQueue.invokeLater { showMenu() }
    }

    private fun showMenu() {
        val toolbar = Toolbar.getInstance() ?: return
        val state = ArkitektState.state
        val menu = PopupMenu("Arkitekt")

        fun item(label: String, enabled: Boolean = true, action: (() -> Unit)? = null) =
                MenuItem(label).also { item ->
                    item.isEnabled = enabled && action != null
                    action?.let { run -> item.addActionListener { run() } }
                    menu.add(item)
                }

        item(
                when (state) {
                    is ConnState.Connected -> "Connected as ${state.username}"
                    ConnState.Connecting -> "Connecting…"
                    is ConnState.Error -> "Login failed: ${state.message.lineSequence().first().take(80)}"
                    ConnState.Disconnected -> "Not connected"
                },
                enabled = false
        )
        item("Server: ${ArkitektState.server.removePrefix("https://").removePrefix("http://")}", enabled = false)
        menu.addSeparator()
        when (state) {
            ConnState.Connecting -> item("Cancel login") { orchestrator()?.cancelLogin() }
            is ConnState.Connected -> {
                item("Reconnect") { orchestrator()?.let { login(it) } }
                item("Log out") { orchestrator()?.logout() }
            }
            is ConnState.Error -> {
                // A failed login may have left a stale session behind: start from scratch.
                item("Log in again") { orchestrator()?.let { it.logout(); login(it) } }
            }
            ConnState.Disconnected -> item("Log in") { orchestrator()?.let { login(it) } }
        }
        item("Change server…", enabled = state !is ConnState.Connecting) { changeServer() }
        menu.addSeparator()
        item("Open Arkitekt window…") { IJ.doCommand("Arkitekt") }

        // One menu on the toolbar at a time: the last one goes when the next opens.
        shown?.let { toolbar.remove(it) }
        shown = menu
        toolbar.add(menu)
        // Where the pointer is, which is on this button: the click that opened it.
        val pointer = MouseInfo.getPointerInfo()?.location
        val origin = runCatching { toolbar.locationOnScreen }.getOrNull()
        val x = if (pointer != null && origin != null) pointer.x - origin.x else 0
        val y = if (pointer != null && origin != null) pointer.y - origin.y else toolbar.height
        menu.show(toolbar, x, y)
    }

    private fun login(arkitekt: Arkitekt) {
        val server = ArkitektState.server
        IJ.showStatus(
                if (arkitekt.hasCachedConfig(server)) "Arkitekt: reconnecting to $server…"
                else "Arkitekt: connecting to $server — approve in your browser…"
        )
        arkitekt.login(
                server,
                onSuccess = { me -> IJ.showStatus("Arkitekt: connected as ${me.me.username}") },
                // The failure itself is reported where every login's is (ArkitektTool's state
                // listener, which Fiji creates even though its toolbar never shows it).
                onError = { e -> IJ.showStatus("Arkitekt: login failed \u2014 ${e.message ?: e}") }
        )
    }

    private fun changeServer() {
        val dialog = GenericDialog("Arkitekt server")
        dialog.addStringField("Server:", ArkitektState.server, 36)
        dialog.addMessage("The deployment to log in to, e.g. https://go.arkitekt.live")
        dialog.showDialog()
        if (dialog.wasCanceled()) return
        val server = dialog.nextString.trim()
        if (server.isEmpty() || server == ArkitektState.server) return
        ArkitektState.server = server
        // A login to the old server is not one to the new: drop it.
        if (ArkitektState.state is ConnState.Connected) orchestrator()?.logout()
        IJ.showStatus("Arkitekt: server is now $server")
    }

    private fun orchestrator(): Arkitekt? {
        ArkitektState.arkitektOrNull?.let { return it }
        val ui = context.getService(UIService::class.java) ?: return null
        val datasets = context.getService(DatasetService::class.java) ?: return null
        val displays = context.getService(ImageDisplayService::class.java) ?: return null
        return ArkitektState.getOrCreate(ui, datasets, displays)
    }

    companion object {
        /** "Action Tool" makes a click run it rather than select it; the status bar shows "Arkitekt Tool". */
        const val NAME = "Arkitekt Action Tool"

        // The logo, 16x16, in ImageJ's icon language: a filled circle, a white "A" over it.
        private const val GLYPH = "o11eeCfffP5c84bc0L69a9"

        /** The icon for a state: the logo in the state's colour. */
        fun icon(state: ConnState): String =
                when (state) {
                    is ConnState.Connected -> "C55e"
                    ConnState.Connecting -> "Cf90"
                    is ConnState.Error -> "Cd22"
                    ConnState.Disconnected -> "C999"
                } + GLYPH
    }
}

/** Puts [ArkitektToolbarTool] in Fiji's toolbar, and keeps its icon in step with the connection. */
object ArkitektToolbar {
    @Volatile
    private var listening = false

    /**
     * Add the button to the toolbar, unless it is there. A no-op without ImageJ 1's toolbar (a
     * pure ImageJ2 UI, headless). Safe from any thread.
     */
    fun install(context: Context) {
        if (GraphicsEnvironment.isHeadless()) return
        try {
            EventQueue.invokeLater { installNow(context) }
        } catch (_: NoClassDefFoundError) {
            // No ImageJ 1 on the classpath.
        }
    }

    private fun installNow(context: Context) {
        try {
            val toolbar = Toolbar.getInstance() ?: return
            if (toolbar.getToolId(ArkitektToolbarTool.NAME) < 0) {
                Toolbar.addPlugInTool(ArkitektToolbarTool(context))
            }
            if (!listening) {
                listening = true
                ArkitektState.addListener { state ->
                    EventQueue.invokeLater {
                        runCatching { Toolbar.setIcon(ArkitektToolbarTool.NAME, ArkitektToolbarTool.icon(state)) }
                    }
                }
            }
        } catch (_: NoClassDefFoundError) {
            // No ImageJ 1 on the classpath.
        }
    }
}

/**
 * Adds the toolbar button when Fiji starts. A service, because a service is made with the
 * context, before ImageJ 1's window exists: it waits (on its own thread, writing nothing, since
 * ImageJ's console is still being built) until the toolbar is there, then adds the button.
 */
@Plugin(type = Service::class, priority = Priority.LOW)
class ArkitektToolbarService : AbstractService() {
    override fun initialize() {
        if (GraphicsEnvironment.isHeadless()) return
        val context = context()
        val waiter = Thread({
            try {
                // For Fiji's first two minutes, every second: once the toolbar is up, add the
                // button, and add it again whenever it is gone. Installing a toolset (Fiji's
                // StartupMacros at start, among them) empties every custom slot, and that may
                // well come after the toolbar first appears.
                for (attempt in 0 until 120) {
                    if (IJ.getInstance() != null && Toolbar.getInstance() != null) {
                        ArkitektToolbar.install(context)
                    }
                    Thread.sleep(1000)
                }
            } catch (_: NoClassDefFoundError) {
                // No ImageJ 1: nothing to add to.
            } catch (_: InterruptedException) {
            }
        }, "arkitekt-toolbar")
        waiter.isDaemon = true
        waiter.start()
    }
}
