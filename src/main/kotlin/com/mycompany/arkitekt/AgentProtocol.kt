package com.mycompany.arkitekt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID

// The rekuest agent wire protocol (see rekuest-next `rekuest_next/messages.py`).
//
// Both directions are discriminated unions on a `type` string. Every message carries an `id`
// (a uuid4). We rely on kotlinx's class discriminator (`type`) rather than declaring a `type`
// property on each subclass, so the discriminator is written/read automatically.
//
// Each task is keyed by `task` (a UUID string). This is agent protocol 2 (the contract is the
// server's `takt/docs/journal.md`): both directions are numbered and acknowledged.
//
// - What we report (SESSION_INIT, STARTED, YIELD, COMPLETED, …) is numbered by the [Journal]
//   (`pos`, `journal_session`, `agent_ts`, `task_step`, stamped onto the frame there) and kept
//   until a JOURNAL_ACK covers it.
// - What the server must not lose (ASSIGN, CANCEL, …) carries its own `pos`: we handle a position
//   once and answer COMMAND_ACK. `pos` is read off the raw frame (Agent.kt), so the classes below
//   do not declare it and a command of a type we do not know is still acknowledged.
//
// TRAP: `FromAgentMessageType` on the server also lists APP_CANCELLED, but it has no message class
// and is absent from the union — sending it fails discrimination and closes the socket. Report
// app-side cancellation as CANCELLED. Do not "complete" the enum here.

internal fun newId(): String = UUID.randomUUID().toString()

// Lenient, default-encoding JSON with `type` as the polymorphic discriminator. `ignoreUnknownKeys`
// keeps us forward-compatible with extra fields the server may add to a known message.
val agentJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** The spec release this agent was written against. The server refuses a REGISTER without one. */
const val AGENT_SPEC_VERSION = "10.1.0"

/** The feature that says "I speak protocol 2". The server refuses an agent that does not. */
const val FEATURE_PROTOCOL_2 = "protocol_2"

// ---- Inbound: rekuest backend -> agent --------------------------------------------------

@Serializable
sealed class AgentMessage {

    /**
     * The server's acknowledgement of our REGISTER — the only proof this connection worked.
     *
     * `nonce` says again the one our REGISTER carried: an INIT naming another connection (one
     * gone since) is ignored. `positions` is how far the server has taken each journal session
     * our REGISTER listed: we drop what is at or below and send the rest.
     */
    @Serializable
    @SerialName("INIT")
    data class Init(
            val agent: String,
            val nonce: String? = null,
            val positions: Map<String, Long> = emptyMap(),
            val id: String = newId()
    ) : AgentMessage()

    /**
     * Start a task. It is sent again until our COMMAND_ACK covers its position, so the same
     * `task` can arrive twice and the handler must only run once.
     */
    @Serializable
    @SerialName("ASSIGN")
    data class Assign(
            @SerialName("interface") val functionName: String,
            val task: String,
            val args: Map<String, JsonElement?> = emptyMap(),
            val reference: String? = null,
            /**
             * An opaque, signed provenance token attesting who caused this task. The agent is
             * meant to forward it untouched to downstream services (never validate it); null when
             * the implementation sets `needsToken = false`. We parse but do not yet forward it —
             * threading it into the mikro calls is handler work, tracked separately.
             */
            val token: String? = null,
            /** A probe (`p-…` id): ephemeral, no server-side history, no replay or recovery. */
            val probe: Boolean = false,
            /** The user-triggered task at the head of the cascade; null if this is the root. */
            val root: String? = null,
            /** The direct parent; null if this is the root. */
            val parent: String? = null,
            val user: String? = null,
            val org: String? = null,
            val action: String? = null,
            val implementation: String? = null,
            val id: String = newId()
    ) : AgentMessage()

    @Serializable
    @SerialName("CANCEL")
    data class Cancel(val task: String, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("INTERRUPT")
    data class Interrupt(val task: String, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("PAUSE")
    data class Pause(val task: String, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("RESUME")
    data class Resume(val task: String, val step: Boolean = false, val id: String = newId()) : AgentMessage()

    @Serializable @SerialName("HEARTBEAT") data class Heartbeat(val id: String = newId()) : AgentMessage()

    /** The server has taken every report of `journal_session` through `through` (cumulative). */
    @Serializable
    @SerialName("JOURNAL_ACK")
    data class JournalAck(
            @SerialName("journal_session") val journalSession: String,
            val through: Long,
            val id: String = newId()
    ) : AgentMessage()

    /** A gap: the server did not take what we sent and wants `expected` next, then on in order. */
    @Serializable
    @SerialName("JOURNAL_NACK")
    data class JournalNack(
            @SerialName("journal_session") val journalSession: String,
            val expected: Long,
            val id: String = newId()
    ) : AgentMessage()

    /** "What became of this task?" Answered at once, from memory, with INQUIRY_ANSWER. */
    @Serializable
    @SerialName("INQUIRE")
    data class Inquire(val task: String, val request: String? = null, val id: String = newId()) :
            AgentMessage()

    @Serializable
    @SerialName("KICK")
    data class Kick(val reason: String? = null, val id: String = newId()) : AgentMessage()

    /** A soft restart: disconnect and reconnect. `duration` hints how long to wait first. */
    @Serializable
    @SerialName("BOUNCE")
    data class Bounce(val duration: Int? = null, val id: String = newId()) : AgentMessage()

    @Serializable
    @SerialName("PROTOCOL_ERROR")
    data class ProtocolError(val error: String, val id: String = newId()) : AgentMessage()
}

// ---- Outbound: agent -> rekuest backend -------------------------------------------------

@Serializable
sealed class AgentEvent {

    /**
     * First message after connect. `force` kicks any existing connection for this agent.
     * `sessionId` is a per-process uuid (the [Journal]'s session): the same one on a reconnect
     * says the process survived. `nonce` is new per connection attempt and comes back in INIT.
     * `commandsSeen` is the highest command position this process handled (the server sends from
     * the one after), and `sessions` the journal sessions we still hold unacknowledged reports of.
     */
    @Serializable
    @SerialName("REGISTER")
    data class Register(
            val token: String,
            val force: Boolean = false,
            @SerialName("session_id") val sessionId: String? = null,
            val nonce: String = newId(),
            @SerialName("commands_seen") val commandsSeen: Long = 0,
            val sessions: List<String> = emptyList(),
            val features: List<String> = listOf(FEATURE_PROTOCOL_2),
            @SerialName("spec_version") val specVersion: String = AGENT_SPEC_VERSION,
            val id: String = newId()
    ) : AgentEvent()
    // NOTE: no `mode`. The server dropped the field (rekuest, 2026-08-13); everyone on /agi is an
    // agent. And no declaration: the implementations are registered over GraphQL (Agent.kt).

    /** We handled every command through `through` (cumulative): the server may forget them. */
    @Serializable
    @SerialName("COMMAND_ACK")
    data class CommandAck(val through: Long, val id: String = newId()) : AgentEvent()

    /** The reports `first..last` of `session` are gone here: the server goes on after `last`. */
    @Serializable
    @SerialName("JOURNAL_SKIP")
    data class JournalSkip(val session: String, val first: Long, val last: Long, val id: String = newId()) :
            AgentEvent()

    /** The answer to INQUIRE: a [TaskStatus], with the `request` the inquiry named. */
    @Serializable
    @SerialName("INQUIRY_ANSWER")
    data class InquiryAnswer(
            val task: String,
            val status: String,
            val request: String? = null,
            val id: String = newId()
    ) : AgentEvent()

    /**
     * Announces this run to the backend, which opens a `Session` row from it.
     *
     * Recorded once per process, as the journal's first report (position 1): the session belongs
     * to the process, not the socket. Like any report it goes out after INIT and is sent again
     * until acknowledged.
     *
     * `states` is the initial state snapshots by name. We register no states, so it is always
     * empty — but the field is required, so it must still be written.
     */
    @Serializable
    @SerialName("SESSION_INIT")
    data class SessionInit(
            @SerialName("session_id") val sessionId: String,
            val states: Map<String, JsonElement> = emptyMap(),
            val id: String = newId()
    ) : AgentEvent()

    @Serializable @SerialName("HEARTBEAT_ANSWER") data class HeartbeatAnswer(val id: String = newId()) : AgentEvent()

    /**
     * The actor accepted the task and began executing it.
     *
     * A deliberate divergence from rekuest-next, which never emits STARTED and uses
     * `Progress(progress = 0, message = "Queued for running")` as its de-facto start marker. The
     * server accepts both, and STARTED is the only thing that produces a `StartedEvent` for the
     * caller — so ours is strictly more informative. Not an oversight.
     */
    @Serializable
    @SerialName("STARTED")
    data class Started(val task: String, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("YIELD")
    data class Yield(
            val task: String,
            val returns: Map<String, JsonElement?>? = null,
            val id: String = newId()
    ) : AgentEvent()

    /** The task finished successfully (terminal). */
    @Serializable
    @SerialName("COMPLETED")
    data class Completed(val task: String, val id: String = newId()) : AgentEvent()

    /** Recoverable error for a task. */
    @Serializable
    @SerialName("FAILED")
    data class Failed(val task: String, val error: String, val id: String = newId()) :
            AgentEvent()

    /** Unrecoverable error for a task. */
    @Serializable
    @SerialName("CRITICAL")
    data class Critical(val task: String, val error: String, val id: String = newId()) :
            AgentEvent()

    @Serializable
    @SerialName("PROGRESS")
    data class Progress(
            val task: String,
            val progress: Int? = null,
            val message: String? = null,
            val id: String = newId()
    ) : AgentEvent()

    @Serializable
    @SerialName("LOG")
    data class Log(
            val task: String,
            val message: String,
            val level: String = "INFO",
            val id: String = newId()
    ) : AgentEvent()

    @Serializable
    @SerialName("CANCELLED")
    data class Cancelled(val task: String, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("INTERRUPTED")
    data class Interrupted(val task: String, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("PAUSED")
    data class Paused(val task: String, val id: String = newId()) : AgentEvent()

    @Serializable
    @SerialName("RESUMED")
    data class Resumed(val task: String, val id: String = newId()) : AgentEvent()
}

/** The task a report is about, for the reports the journal gates and numbers per task. */
internal val AgentEvent.reportedTask: String?
    get() = when (this) {
        is AgentEvent.Started -> task
        is AgentEvent.Yield -> task
        is AgentEvent.Completed -> task
        is AgentEvent.Failed -> task
        is AgentEvent.Critical -> task
        is AgentEvent.Progress -> task
        is AgentEvent.Log -> task
        is AgentEvent.Cancelled -> task
        is AgentEvent.Interrupted -> task
        is AgentEvent.Paused -> task
        is AgentEvent.Resumed -> task
        else -> null
    }

/** Whether a report ends its task: nothing is recorded for the task after it. */
internal val AgentEvent.isTerminal: Boolean
    get() = this is AgentEvent.Completed || this is AgentEvent.Failed || this is AgentEvent.Critical ||
            this is AgentEvent.Cancelled || this is AgentEvent.Interrupted

// ---- Close codes -------------------------------------------------------------------------

/**
 * The websocket close codes the agent gateway uses (`facade/codes.py`).
 *
 * These come from the SERVER, not from rekuest-next: that client's table
 * (`agents/transport/websocket.py`) predates 4004/4005 and still maps 3001 to "kicked", which
 * would make a mere heartbeat timeout stop the agent for good.
 */
object AgentCloseCodes {
    const val HEARTBEAT_NOT_RESPONDED = 3001
    const val MESSAGE_IS_NOT_VALID_JSON = 3002
    const val MESSAGE_DOES_NOT_MATCH_SCHEMA = 3003
    // Defined server-side but never emitted; a pre-registration frame closes 3003 instead.
    const val MESSAGE_RECEIVED_BEFORE_REGISTRATION = 3004
    const val AGENT_IS_BLOCKED = 4003
    const val AGENT_ALREADY_CONNECTED = 4004
    const val AGENT_REPLACED = 4005
}

enum class CloseAction {
    /** Transient: reconnect on the usual backoff. */
    RECONNECT,
    /** Another connection holds the agent; it may go stale, so wait it out and try once more. */
    WAIT_AND_RETRY,
    /** Reconnecting cannot help, or would actively harm. Give up and surface why. */
    STOP
}

data class CloseVerdict(val action: CloseAction, val why: String)

/**
 * Decide what a closed connection means.
 *
 * [registered] (did INIT arrive?) is only the fallback for a transport-level drop that carried no
 * code at all — the code itself is authoritative whenever we have one.
 */
fun classifyClose(code: Int?, registered: Boolean): CloseVerdict = when (code) {
    AgentCloseCodes.HEARTBEAT_NOT_RESPONDED ->
            CloseVerdict(CloseAction.RECONNECT, "we stopped answering heartbeats")

    // Reconnecting re-sends whatever the server could not parse or accept. 3003 is overloaded:
    // it is also what an invalid token and a missing agent record produce, because the server's
    // register handler wraps everything in one catch-all. Either way a retry changes nothing.
    AgentCloseCodes.MESSAGE_IS_NOT_VALID_JSON ->
            CloseVerdict(CloseAction.STOP, "the server could not parse a frame we sent")
    AgentCloseCodes.MESSAGE_DOES_NOT_MATCH_SCHEMA ->
            CloseVerdict(
                    CloseAction.STOP,
                    "the server rejected our registration or a frame we sent (close 3003 — it " +
                            "covers a wire-format mismatch, an invalid token, and an agent that " +
                            "was never created, indistinguishably)"
            )
    AgentCloseCodes.MESSAGE_RECEIVED_BEFORE_REGISTRATION ->
            CloseVerdict(CloseAction.STOP, "we sent a frame before registering")

    AgentCloseCodes.AGENT_IS_BLOCKED ->
            CloseVerdict(CloseAction.STOP, "this agent is blocked; an operator has to unblock it")

    // The server only rejects a *provably live* incumbent. Once its last_seen ages past
    // AGENT_STALE_AFTER (3 x the 10s heartbeat) a plain reconnect takes the lease over without
    // force, so waiting is the fix. Escalating to force would kick a live peer — a person's call.
    AgentCloseCodes.AGENT_ALREADY_CONNECTED ->
            CloseVerdict(CloseAction.WAIT_AND_RETRY, "another connection already holds this agent")

    // We were displaced, or our lease was fenced. Another process owns the agent now, and
    // reconnecting immediately would displace it right back — two instances evicting each other
    // in a loop. Stop and let a human decide.
    AgentCloseCodes.AGENT_REPLACED ->
            CloseVerdict(CloseAction.STOP, "another connection took over this agent")

    else ->
            if (registered) CloseVerdict(CloseAction.RECONNECT, "the connection dropped")
            else CloseVerdict(CloseAction.RECONNECT, "the connection dropped before registering")
}
