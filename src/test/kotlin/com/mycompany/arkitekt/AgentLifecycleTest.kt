package com.mycompany.arkitekt

import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import net.imagej.ImageJ
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The agent's connection lifecycle, driven against a local [FakeGateway].
 *
 * These exist because the live gateway refuses our registration for a server-side reason we cannot
 * fix from here — without a fake, everything past REGISTER would be verified only by reading, and
 * that is precisely where the interesting failures live (a close we mis-handle can hang the agent
 * or start an eviction war, and neither shows up in a schema test).
 */
class AgentLifecycleTest {

    private val ij: ImageJ by lazy {
        System.setProperty("java.awt.headless", "true")
        ImageJ()
    }

    private var gateway: FakeGateway? = null

    @AfterTest
    fun tearDown() {
        gateway?.stop()
    }

    private fun app(tokens: TokenManager, alias: Alias): App {
        val mikro = Mikro(alias, tokens)
        return App(
                mikro,
                Datalayer(alias, mikro),
                Unlok(alias, tokens),
                Rekuest(alias, tokens),
                ij.ui(),
                ij.dataset(),
                ij.imageDisplay()
        )
    }

    private fun agentAgainst(
            gw: FakeGateway,
            registry: FunctionRegistry = FunctionRegistry(),
            staleWaitSeconds: Long = 35,
            budgetResetSeconds: Double = 30.0
    ): Agent {
        gw.start()
        gateway = gw
        val alias = localAlias(gw.port)
        val tokens = fixedTokenManager()
        return Agent(
                alias,
                tokens,
                registry,
                app(tokens, alias),
                staleTakeoverWaitSeconds = staleWaitSeconds,
                budgetResetAfterSeconds = budgetResetSeconds
        )
    }

    /** The INIT answering [register]: it says the REGISTER's nonce again. */
    private fun init(register: String?, positions: String = "{}"): String {
        val nonce = Regex("\"nonce\":\"([^\"]+)\"").find(register ?: "")?.groupValues?.get(1)
        return """{"type":"INIT","id":"i1","agent":"42","nonce":"$nonce","positions":$positions}"""
    }

    // ---- The bug that hangs the agent ----------------------------------------------------

    @Test
    fun `BOUNCE reconnects instead of hanging`() = runBlocking {
        // BOUNCE and KICK arrive as MESSAGES and are not followed by a server close, so the agent
        // is the one that closes. Reading the close reason before closing would wait forever on
        // exactly these two paths — and BOUNCE is a routine soft restart, not an edge case.
        val gw = FakeGateway { index, session ->
            with(session) {
                sendJson(init(readUntil { it.contains("REGISTER") }))
                if (index == 0) {
                    sendJson("""{"type":"BOUNCE","id":"b1","duration":0}""")
                    drain() // stay open: the agent must be the one to close
                } else {
                    sendJson("""{"type":"KICK","id":"k1","reason":"done"}""")
                    drain()
                }
            }
        }
        val agent = agentAgainst(gw)

        val result = withTimeout(30_000) { agent.provideForever() }

        assertTrue(result.isSuccess, "a KICK should end the loop cleanly")
        assertEquals(0, gw.connections.receive(), "first connection")
        assertEquals(1, gw.connections.receive(), "BOUNCE must have produced a reconnect")
    }

    // ---- Registration and session announcement -------------------------------------------

    @Test
    fun `an unacknowledged report is sent again after a reconnect, an acknowledged one is not`() = runBlocking {
        val gw = FakeGateway { index, session ->
            with(session) {
                val register = readUntil { it.contains("REGISTER") }
                if (index == 0) {
                    sendJson(init(register))
                    readUntil { it.contains("SESSION_INIT") }
                    // Never acknowledged: the connection goes first.
                    sendJson("""{"type":"BOUNCE","id":"b1","duration":0}""")
                } else if (index == 1) {
                    sendJson(init(register))
                    val again = readUntil { it.contains("SESSION_INIT") }!!
                    val session_id = Regex("\"journal_session\":\"([^\"]+)\"").find(again)!!.groupValues[1]
                    sendJson("""{"type":"JOURNAL_ACK","id":"j1","journal_session":"$session_id","through":1}""")
                    sendJson("""{"type":"BOUNCE","id":"b2","duration":0}""")
                } else {
                    sendJson(init(register))
                    sendJson("""{"type":"KICK","id":"k1"}""")
                }
                drain()
            }
        }
        val agent = agentAgainst(gw)
        withTimeout(30_000) { agent.provideForever() }

        val announcements = gw.received.filter { it.contains("\"SESSION_INIT\"") }
        // The session belongs to the process: recorded once (position 1), sent until acknowledged.
        assertEquals(2, announcements.size)
        assertTrue(announcements.all { it.contains("\"pos\":1,") })
        // What a REGISTER still owes, it names; the third owes nothing.
        val registers = gw.received.filter { it.contains("\"REGISTER\"") }
        assertTrue(registers[1].contains("\"sessions\":[\""))
        assertTrue(registers[2].contains("\"sessions\":[]"))
    }

    @Test
    fun `REGISTER carries the same session id across reconnects`() = runBlocking {
        val gw = FakeGateway { index, session ->
            with(session) {
                sendJson(init(readUntil { it.contains("REGISTER") }))
                sendJson(
                        if (index == 0) """{"type":"BOUNCE","id":"b1","duration":0}"""
                        else """{"type":"KICK","id":"k1"}"""
                )
                drain()
            }
        }
        val agent = agentAgainst(gw)
        withTimeout(30_000) { agent.provideForever() }

        val sessionIds = gw.received
                .filter { it.contains("\"REGISTER\"") }
                .mapNotNull { Regex("\"session_id\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        assertEquals(2, sessionIds.size)
        // A changed session id tells the backend the process died, and it responds by
        // fail-and-cascading the work we were still doing.
        assertEquals(sessionIds[0], sessionIds[1])
    }

    // ---- Inquiries ---------------------------------------------------------------------------

    @Test
    fun `an inquiry about a task we never had is answered UNKNOWN`() = runBlocking {
        val gw = FakeGateway { _, session ->
            with(session) {
                sendJson(init(readUntil { it.contains("REGISTER") }))
                sendJson("""{"type":"INQUIRE","id":"q1","task":"t-9","request":"r-1"}""")
                readUntil { it.contains("INQUIRY_ANSWER") }
                sendJson("""{"type":"KICK","id":"k1"}""")
                drain()
            }
        }
        val agent = agentAgainst(gw)
        withTimeout(30_000) { agent.provideForever() }

        val answer = gw.received.single { it.contains("\"INQUIRY_ANSWER\"") }
        assertTrue(answer.contains("\"status\":\"UNKNOWN\""))
        assertTrue(answer.contains("\"request\":\"r-1\""))
    }

    // ---- Heartbeat ---------------------------------------------------------------------------

    @Test
    fun `a heartbeat is answered`() = runBlocking {
        val gw = FakeGateway { _, session ->
            with(session) {
                sendJson(init(readUntil { it.contains("REGISTER") }))
                sendJson("""{"type":"HEARTBEAT","id":"h1"}""")
                readUntil { it.contains("HEARTBEAT_ANSWER") }
                sendJson("""{"type":"KICK","id":"k1"}""")
                drain()
            }
        }
        val agent = agentAgainst(gw)
        withTimeout(30_000) { agent.provideForever() }

        assertTrue(gw.receivedTypes().contains("HEARTBEAT_ANSWER"))
    }

    // ---- Assign ------------------------------------------------------------------------------

    @Test
    fun `a redelivered ASSIGN runs the handler once`() = runBlocking {
        // Backend delivery is at-least-once, so the same task can arrive twice. The server dedups
        // the resulting report but not the side effect — running an upload twice is a real bug.
        val runs = AtomicInteger(0)
        val registry = FunctionRegistry()
        val handler: suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?> = { _, _ ->
            runs.incrementAndGet()
            kotlinx.coroutines.delay(300)
            emptyMap()
        }
        registry.register_function("echo", testDefinition("echo"), handler)

        val assign = """{"type":"ASSIGN","id":"a1","interface":"echo","task":"t-1","args":{},
            "user":"1","org":"1","action":"a","implementation":"i","pos":1}"""
        val gw = FakeGateway { _, session ->
            with(session) {
                sendJson(init(readUntil { it.contains("REGISTER") }))
                sendJson(assign)
                sendJson(assign) // the redelivery
                readUntil { it.contains("\"COMPLETED\"") }
                sendJson("""{"type":"KICK","id":"k1"}""")
                drain()
            }
        }
        val agent = agentAgainst(gw, registry)
        withTimeout(30_000) { agent.provideForever() }

        assertEquals(1, runs.get(), "the duplicate ASSIGN must not re-run the handler")
        val types = gw.receivedTypes()
        assertEquals(1, types.count { it == "STARTED" })
        assertEquals(1, types.count { it == "COMPLETED" })
        // Both deliveries are acknowledged: the server forgets the command either way.
        assertEquals(2, gw.received.count { it.contains("\"COMMAND_ACK\"") && it.contains("\"through\":1") })
    }

    // ---- Close-code policy, end to end -------------------------------------------------------

    @Test
    fun `being displaced stops the agent instead of reconnecting`() = runBlocking {
        // The eviction-war guard: if we came back here we would displace the incumbent, which
        // would come back and displace us, forever.
        val gw = FakeGateway { _, session ->
            with(session) {
                sendJson(init(readUntil { it.contains("REGISTER") }))
                close(CloseReason(AgentCloseCodes.AGENT_REPLACED.toShort(), "replaced"))
            }
        }
        val agent = agentAgainst(gw)

        val result = withTimeout(30_000) { agent.provideForever() }

        assertTrue(result.isFailure)
        assertEquals(0, gw.connections.receive())
        assertTrue(gw.connections.tryReceive().isFailure, "must not have reconnected")
    }

    @Test
    fun `a heartbeat-timeout close reconnects`() = runBlocking {
        val gw = FakeGateway { index, session ->
            with(session) {
                sendJson(init(readUntil { it.contains("REGISTER") }))
                if (index == 0) {
                    close(CloseReason(AgentCloseCodes.HEARTBEAT_NOT_RESPONDED.toShort(), "slow"))
                } else {
                    sendJson("""{"type":"KICK","id":"k1"}""")
                    drain()
                }
            }
        }
        val agent = agentAgainst(gw)
        withTimeout(60_000) { agent.provideForever() }

        assertEquals(0, gw.connections.receive())
        assertEquals(1, gw.connections.receive(), "3001 is transient and must reconnect")
    }

    @Test
    fun `a live incumbent is waited out, then retried`() = runBlocking {
        // 4004 means another connection holds the agent *right now*. The server frees the lease
        // once that one goes stale, so waiting is the fix — force-kicking would evict a healthy
        // peer. (The real wait is 35s; shortened here so the path can actually be exercised.)
        val gw = FakeGateway { index, session ->
            with(session) {
                val register = readUntil { it.contains("REGISTER") }
                if (index == 0) {
                    close(CloseReason(AgentCloseCodes.AGENT_ALREADY_CONNECTED.toShort(), "busy"))
                } else {
                    sendJson(init(register))
                    sendJson("""{"type":"KICK","id":"k1"}""")
                    drain()
                }
            }
        }
        val agent = agentAgainst(gw, staleWaitSeconds = 0)

        val result = withTimeout(30_000) { agent.provideForever() }

        assertTrue(result.isSuccess, "the retry after the wait should have got in")
        assertEquals(0, gw.connections.receive())
        assertEquals(1, gw.connections.receive(), "4004 must be retried once, not treated as fatal")
    }

    @Test
    fun `an incumbent that is still there after the wait stops the agent`() = runBlocking {
        // Waiting twice would be a slow-motion retry loop against a peer that is simply alive.
        val gw = FakeGateway { _, session ->
            with(session) {
                readUntil { it.contains("REGISTER") }
                close(CloseReason(AgentCloseCodes.AGENT_ALREADY_CONNECTED.toShort(), "busy"))
            }
        }
        val agent = agentAgainst(gw, staleWaitSeconds = 0)

        val result = withTimeout(30_000) { agent.provideForever() }

        assertTrue(result.isFailure)
        assertEquals(0, gw.connections.receive())
        assertEquals(1, gw.connections.receive())
        assertTrue(gw.connections.tryReceive().isFailure, "exactly one retry, then stop")
    }

    @Test
    fun `a connection that stood up re-arms the wait-out path`() = runBlocking {
        // The reset is what stops "wait it out" from being a once-per-process trick: a long-lived
        // agent displaced hours later must still be willing to wait rather than giving up at once.
        val gw = FakeGateway { index, session ->
            with(session) {
                val register = readUntil { it.contains("REGISTER") }
                when (index) {
                    // Busy, wait, retry...
                    0 -> close(CloseReason(AgentCloseCodes.AGENT_ALREADY_CONNECTED.toShort(), "busy"))
                    // ...we get in, and this connection counts as a recovery...
                    1 -> {
                        sendJson(init(register))
                        close(CloseReason(AgentCloseCodes.HEARTBEAT_NOT_RESPONDED.toShort(), "slow"))
                    }
                    // ...so a later 4004 is waited out again rather than being fatal.
                    2 -> close(CloseReason(AgentCloseCodes.AGENT_ALREADY_CONNECTED.toShort(), "busy"))
                    else -> {
                        sendJson(init(register))
                        sendJson("""{"type":"KICK","id":"k1"}""")
                        drain()
                    }
                }
            }
        }
        // budgetResetSeconds = 0 so connection 1 counts as a recovery the instant it drops.
        val agent = agentAgainst(gw, staleWaitSeconds = 0, budgetResetSeconds = 0.0)

        val result = withTimeout(30_000) { agent.provideForever() }

        assertTrue(result.isSuccess, "the second 4004 must be waited out, not treated as fatal")
        assertEquals(3, gw.connections.receive() + gw.connections.receive() + gw.connections.receive())
    }
}
