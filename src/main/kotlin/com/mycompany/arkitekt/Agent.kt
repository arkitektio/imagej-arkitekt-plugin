package com.mycompany.arkitekt

import com.apollographql.apollo.api.Optional
import com.mycompany.rekuest.graphql.EnsureAgentMutation
import com.mycompany.rekuest.graphql.ImplementAgentMutation
import com.mycompany.rekuest.graphql.type.AgentInput
import com.mycompany.rekuest.graphql.type.ImplementAgentInput
import com.mycompany.rekuest.graphql.type.ImplementationInput
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves an [App]'s actions over the agent socket (`/agi`): registers them, runs what the server
 * assigns with the app's [Services], and reports back.
 */
public class Agent(
        private val alias: Alias,
        private val tokens: TokenManager,
        private val app: App,
        private val services: Services,
        /**
         * How long to let a live incumbent's lease go stale before trying again.
         *
         * The server frees the lease once the incumbent's `last_seen` ages past
         * `AGENT_STALE_AFTER` (3 x its 10s heartbeat), so waiting is what makes a 4004 recoverable
         * without force-kicking a healthy peer. A constructor parameter only so tests can drive
         * that path without actually waiting half a minute.
         */
        private val staleTakeoverWaitSeconds: Long = 35,
        /**
         * How long a connection must stand up to count as a recovery, refunding the retry budget
         * and re-arming the wait-out path above.
         */
        private val budgetResetAfterSeconds: Double = 30.0
) {

    // Register (or look up) the agent record. The agent is identified by the token + name;
    // there is no instanceId in the current protocol.
    suspend fun createAgent(name: String): Result<Unit> {
        return kotlin.runCatching {
            val request = EnsureAgentMutation(AgentInput(name = Optional.present(name)))
            services.rekuest.getClient().mutation(request).execute().data
        }
    }

    // Advertise all registered implementations to the server in a single implementAgent call.
    suspend fun registerFunctions() {
        val implementations =
                app.actions.values.map { action ->
                    ImplementationInput(
                            definition = action.definition,
                            `interface` = Optional.present(action.key)
                    )
                }

        val mutation =
                ImplementAgentMutation(
                        ImplementAgentInput(implementations = Optional.present(implementations))
                )

        val response = services.rekuest.getClient().mutation(mutation).execute()
        println("implementAgent -> ${response.data}")
    }

    // What belongs to the process, not to one socket: a task keeps running while the connection
    // is away, and what it reports waits in the journal for the next one.

    /** Everything we report, numbered and kept until acknowledged. Its session is the process's. */
    private val journal = Journal()

    /** The highest command position handled: a position at or below it is not handled again. */
    @Volatile private var commandsSeen = 0L

    /** Running tasks by id, so CANCEL/INTERRUPT can stop them. */
    private val running = ConcurrentHashMap<String, Job>()

    /** Where tasks run: outside any one connection. */
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The live connection's direct line, for a probe's reports (never numbered, never kept). */
    @Volatile private var direct: (suspend (AgentEvent) -> Unit)? = null

    private var sessionAnnounced = false

    /** A probe is ephemeral: its task id is `p-…` and its reports are sent at most once. */
    private fun isProbe(task: String) = task.startsWith("p-")

    /** Report on a task: numbered and kept, or for a probe sent now if there is a connection. */
    private suspend fun report(event: AgentEvent) {
        val task = event.reportedTask
        if (task != null && isProbe(task)) {
            runCatching { direct?.invoke(event) }
        } else {
            journal.record(event)
        }
    }

    /** Run an assigned task. It outlives the connection it came on. */
    private fun run(msg: AgentMessage.Assign) {
        if (running.containsKey(msg.task)) {
            println("Ignoring duplicate ASSIGN for task ${msg.task}")
            return
        }
        // A task runs once: not again while it runs, and not again after it ended.
        if (!isProbe(msg.task) && !journal.begin(msg.task)) {
            println("Ignoring duplicate ASSIGN for task ${msg.task}")
            return
        }
        val func = app.actions[msg.functionName]?.handler
        val job =
                tasks.launch(start = CoroutineStart.LAZY) {
                    try {
                        if (func == null) {
                            println("No implementation for interface '${msg.functionName}'")
                            report(AgentEvent.Critical(msg.task, "No implementation for '${msg.functionName}'"))
                            return@launch
                        }
                        report(AgentEvent.Started(msg.task))
                        val returns = func(services, msg.args)
                        report(AgentEvent.Yield(msg.task, returns))
                        report(AgentEvent.Completed(msg.task))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        report(AgentEvent.Critical(msg.task, e.message ?: e.toString()))
                    } finally {
                        running.remove(msg.task)
                    }
                }
        running[msg.task] = job
        job.start()
    }

    /** What one connection attempt ended up telling us. */
    private data class Attempt(
            /** The server's close code, or null for a transport-level drop that carried none. */
            val closeCode: Int?,
            /** Did INIT arrive? The only proof this connection was ever accepted. */
            val registered: Boolean,
            /** KICK: a clean, deliberate stop rather than a failure. */
            val kicked: Boolean,
            /** BOUNCE's hint for how long to wait before coming back. */
            val bounceDelaySeconds: Int?,
            /** The last PROTOCOL_ERROR or exception text — often the only human-readable detail. */
            val detail: String?,
            /** How long the connection stood up, in seconds. */
            val upSeconds: Double
    )

    // Backoff mirrors rekuest-next's ConnectionPolicy (agents/policy.py): 1s doubling to a 60s
    // cap, +/-10% jitter so a fleet of agents does not reconnect in lockstep after a restart.
    private val backoffInitialSeconds = 1.0
    private val backoffMaxSeconds = 60.0
    private val backoffJitter = 0.1
    private val maxRetries = 5

    private fun backoffDelayFor(attempt: Int): Long {
        val raw = minOf(backoffInitialSeconds * Math.pow(2.0, attempt.toDouble()), backoffMaxSeconds)
        val jittered = raw + raw * (Math.random() * 2 - 1) * backoffJitter
        return maxOf(0L, (jittered * 1000).toLong())
    }

    /**
     * Stay connected to the agent gateway, reconnecting when the socket drops.
     *
     * The access token expires now, and a socket carries the token it opened with — so an
     * expired-token close can only be recovered by reconnecting with a fresh one, never by
     * refreshing in place.
     *
     * What we do on a close is driven by the server's close code (see [classifyClose]), not by a
     * guess: some closes mean "come back", and at least one — being displaced by another
     * connection — means "do not", because reconnecting would displace the incumbent right back
     * and turn two instances into a mutual-eviction loop.
     */
    suspend fun provideForever(): Result<String> =
            try {
                stayConnected()
            } finally {
                // The agent is gone for good: so are its tasks.
                tasks.coroutineContext.cancelChildren()
            }

    private suspend fun stayConnected(): Result<String> {
        // The journal's session, the same for every connection of this process. The backend reads
        // session_id as "is this the same process?": the same one lets a reconnect keep its
        // in-flight tasks, while a fresh one tells the backend the process died.
        val sessionId = journal.session
        // The journal's first report (position 1), once per process.
        if (!sessionAnnounced) {
            sessionAnnounced = true
            journal.record(AgentEvent.SessionInit(sessionId = sessionId))
        }

        var retry = 0
        var waitedOutIncumbent = false

        while (true) {
            val attempt = connectOnce(sessionId)
            val detail = attempt.detail?.let { " ($it)" } ?: ""

            if (attempt.kicked) {
                println("Agent stopped: the server kicked us$detail")
                return Result.success("Agent stopped by the server.")
            }

            // A connection that stood up long enough counts as a recovery: it refunds the retry
            // budget and re-arms the wait-out-the-incumbent path, which otherwise would only ever
            // work once per process (a long-lived agent displaced hours later would give up
            // immediately instead of waiting for the new holder's lease to go stale).
            if (attempt.upSeconds >= budgetResetAfterSeconds) {
                retry = 0
                waitedOutIncumbent = false
            }

            val verdict = classifyClose(attempt.closeCode, attempt.registered)

            when (verdict.action) {
                CloseAction.STOP -> {
                    val why = "${verdict.why}$detail"
                    println("Agent stopped: $why")
                    return Result.failure(IllegalStateException("Agent stopped: $why"))
                }

                CloseAction.WAIT_AND_RETRY -> {
                    if (waitedOutIncumbent) {
                        val why = "${verdict.why}$detail — it is still there after waiting"
                        println("Agent stopped: $why")
                        return Result.failure(IllegalStateException("Agent stopped: $why"))
                    }
                    waitedOutIncumbent = true
                    println(
                            "${verdict.why}$detail; waiting ${staleTakeoverWaitSeconds}s for its " +
                                    "lease to go stale, then trying once more."
                    )
                    delay(staleTakeoverWaitSeconds * 1000)
                }

                CloseAction.RECONNECT -> {
                    if (retry >= maxRetries) {
                        val why = "gave up after $maxRetries reconnect attempts (${verdict.why})$detail"
                        println("Agent stopped: $why")
                        return Result.failure(IllegalStateException("Agent stopped: $why"))
                    }
                    // A BOUNCE is the server asking for a soft restart and may name its own delay.
                    val delayMs =
                            attempt.bounceDelaySeconds?.let { it * 1000L } ?: backoffDelayFor(retry)
                    retry += 1
                    println(
                            "Agent connection closed (${verdict.why})$detail; reconnecting in " +
                                    "${delayMs / 1000.0}s."
                    )
                    delay(delayMs)
                }
            }
        }
    }

    /** One connection attempt, from opening the socket to it closing. */
    private suspend fun connectOnce(sessionId: String): Attempt {
        return coroutineScope {
            // KICK means "do not come back"; a bounce or a plain close means reconnect.
            var kicked = false
            // INIT is the server's acceptance of our REGISTER, and the only proof this
            // connection ever worked.
            var registered = false
            var bounceDelaySeconds: Int? = null
            var detail: String? = null
            var closeCode: Int? = null
            // Set once the socket is actually up, so a slow TLS handshake cannot earn uptime
            // toward the retry-budget refund.
            var connectedAt: Long? = null

            val wsClient = HttpClient { install(WebSockets) }

            try {
                val session = wsClient.webSocketSession { url(alias.to_ws_path("agi")) }
                connectedAt = System.nanoTime()

                suspend fun send(event: AgentEvent) {
                    session.send(Frame.Text(agentJson.encodeToString(AgentEvent.serializer(), event)))
                }

                // New per attempt: an INIT that does not say it again is not ours.
                val nonce = newId()
                // Where the server stands in our journal, once INIT said so.
                val standing = CompletableDeferred<Long>()
                // Something was recorded, or the server asked for a position again.
                val wake = Channel<Unit>(Channel.CONFLATED)
                // JOURNAL_NACK: go back to just before this position (-1: nothing asked).
                val rewind = AtomicLong(-1)

                // Sender: REGISTER first; after INIT the journal, in order, from where the server
                // stands. The token is pulled at connect time so a reconnect after an expiry
                // carries the refreshed one.
                val senderJob = launch {
                    send(
                            AgentEvent.Register(
                                    token = tokens.accessToken(),
                                    force = false,
                                    sessionId = sessionId,
                                    nonce = nonce,
                                    commandsSeen = commandsSeen,
                                    sessions = if (journal.owes) listOf(journal.session) else emptyList()
                            )
                    )
                    var sent = standing.await()
                    while (true) {
                        val back = rewind.getAndSet(-1)
                        if (back >= 0 && back < sent) sent = back
                        val owed = journal.owedAfter(sent)
                        if (owed.isEmpty()) {
                            wake.receive()
                            continue
                        }
                        for ((pos, frame) in owed) {
                            session.send(Frame.Text(frame))
                            sent = pos
                        }
                    }
                }
                journal.onRecord = { wake.trySend(Unit) }
                direct = { send(it) }

                /** Handle one frame we could read. False when the connection is to end. */
                suspend fun handle(msg: AgentMessage): Boolean {
                    when (msg) {
                        // Answered on the session directly: the server pings every 10s and closes
                        // the socket if the answer takes more than 5s, so liveness must not queue
                        // behind a large YIELD.
                        is AgentMessage.Heartbeat -> send(AgentEvent.HeartbeatAnswer())

                        is AgentMessage.Init -> {
                            if (msg.nonce != nonce) {
                                println("Ignoring an INIT that names another connection")
                                return true
                            }
                            registered = true
                            println("Agent registered as ${msg.agent}")
                            // The server says how far it took our session: forget that much and
                            // send the rest. A session it does not name is one we owe nothing of
                            // (or one it has never seen, and then everything is still here).
                            val through = msg.positions[journal.session]
                            if (through != null) journal.ack(through)
                            standing.complete(through ?: journal.ackedThrough)
                        }
                        is AgentMessage.JournalAck ->
                                if (msg.journalSession == journal.session) journal.ack(msg.through)
                        is AgentMessage.JournalNack ->
                                if (msg.journalSession == journal.session) {
                                    // What we no longer hold is said to be gone; the rest follows.
                                    journal.missing(msg.expected)?.let {
                                        println("Reports ${it.first}..${it.last} are lost")
                                        send(AgentEvent.JournalSkip(journal.session, it.first, it.last))
                                    }
                                    rewind.set(msg.expected - 1)
                                    wake.trySend(Unit)
                                }
                        // Answered at once, from memory. UNKNOWN ends the task LOST, so a task
                        // whose end we reported says ENDED: that report may still be on its way.
                        is AgentMessage.Inquire ->
                                send(
                                        AgentEvent.InquiryAnswer(
                                                task = msg.task,
                                                status = if (running.containsKey(msg.task)) TaskStatus.RUNNING
                                                else journal.status(msg.task),
                                                request = msg.request
                                        )
                                )
                        is AgentMessage.Assign -> run(msg)
                        is AgentMessage.Cancel -> {
                            running.remove(msg.task)?.cancel()
                            report(AgentEvent.Cancelled(msg.task))
                        }
                        is AgentMessage.Interrupt -> {
                            running.remove(msg.task)?.cancel()
                            report(AgentEvent.Interrupted(msg.task))
                        }
                        // Plain functions can't truly suspend mid-run; acknowledge to stay
                        // protocol-compliant.
                        is AgentMessage.Pause -> report(AgentEvent.Paused(msg.task))
                        is AgentMessage.Resume -> report(AgentEvent.Resumed(msg.task))
                        is AgentMessage.Kick -> {
                            println("Kicked by server: ${msg.reason}")
                            kicked = true
                            return false
                        }
                        is AgentMessage.Bounce -> {
                            println("Server requested reconnect (bounce)")
                            bounceDelaySeconds = msg.duration
                            return false
                        }
                        // We sent something the backend could not process. Keep it as this
                        // connection's failure reason: a close usually follows, and this text
                        // is the only human-readable detail behind an otherwise opaque code.
                        is AgentMessage.ProtocolError -> {
                            detail = msg.error
                            println("Protocol error: ${msg.error}")
                        }
                    }
                    return true
                }

                // Receiver: dispatch inbound messages.
                val receiverJob = launch {
                    for (frame in session.incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue

                        // A command carries its position, whatever its type: read off the raw
                        // frame, so one we cannot decode is still acknowledged (and not sent
                        // again on every reconnect).
                        val pos =
                                runCatching {
                                            agentJson.parseToJsonElement(text).jsonObject["pos"]?.jsonPrimitive?.longOrNull
                                        }
                                        .getOrNull()
                        // Handled before (it was sent again because our ack never arrived).
                        if (pos != null && pos <= commandsSeen) {
                            send(AgentEvent.CommandAck(commandsSeen))
                            continue
                        }

                        val msg =
                                runCatching { agentJson.decodeFromString(AgentMessage.serializer(), text) }
                                        .getOrElse {
                                            println("Ignoring undecodable message: $it -- $text")
                                            null
                                        }
                        if (msg != null && !handle(msg)) break
                        if (pos != null) {
                            commandsSeen = pos
                            send(AgentEvent.CommandAck(pos))
                        }
                    }
                }

                try {
                    receiverJob.join()
                    senderJob.cancelAndJoin()
                } catch (e: Exception) {
                    println("Closed with exception $e")
                } finally {
                    // The tasks go on: what they report waits in the journal for the next
                    // connection. Only this connection's lines are taken down.
                    journal.onRecord = null
                    direct = null
                    // Close FIRST, then read the reason. `closeReason` only completes once the
                    // session is closed, and on the paths where *we* leave — KICK and BOUNCE both
                    // arrive as messages that the server does not follow with a close — awaiting
                    // it before closing would wait for something we are about to cause. The
                    // timeout covers the other direction: a half-open socket may never deliver a
                    // close frame at all, and that must not wedge the reconnect loop.
                    session.close()
                    closeCode = withTimeoutOrNull(2_000) { session.closeReason.await() }
                            ?.code
                            ?.toInt()
                    wsClient.close()
                }
            } catch (e: Exception) {
                // A PROTOCOL_ERROR already read off the wire is more specific than the exception
                // that follows it, so don't overwrite one with the other.
                if (detail == null) detail = e.message
                println("Connection closed: ${e.message}")
            }

            Attempt(
                    closeCode = closeCode,
                    registered = registered,
                    kicked = kicked,
                    bounceDelaySeconds = bounceDelaySeconds,
                    detail = detail,
                    upSeconds = connectedAt?.let { (System.nanoTime() - it) / 1_000_000_000.0 } ?: 0.0
            )
        }
    }
}
