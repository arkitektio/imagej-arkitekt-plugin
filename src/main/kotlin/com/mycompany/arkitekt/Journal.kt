package com.mycompany.arkitekt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** What an agent says of a task when the server asks (`INQUIRY_ANSWER.status`). */
object TaskStatus {
    /** Never heard of it: said by the agent the task was given to, this ends the task LOST. */
    const val UNKNOWN = "UNKNOWN"
    const val RUNNING = "RUNNING"
    /** It reported its end; that report is on its way or has arrived. */
    const val ENDED = "ENDED"
}

/**
 * Everything this process reports, numbered and kept until the server acknowledges it.
 *
 * One journal per process: its [session] is the `session_id` the process registers with, and
 * every report gets the next position `pos` in it (1, 2, 3, … with no gaps). A report stays here
 * until a `JOURNAL_ACK` covers it, so one written into a connection that died is sent again on
 * the next, and the server, which knows it by its position, takes it once.
 *
 * It is also each task's gate: a task's reports are numbered per task (`task_step`), and nothing
 * is recorded for a task after its terminal report.
 *
 * Kept in memory: a report not acknowledged when ImageJ closes is lost with it, and the server
 * ends that task LOST.
 */
class Journal(val session: String = UUID.randomUUID().toString()) {
    private var next = 1L
    private var acked = 0L
    private val owed = ArrayDeque<Pair<Long, String>>()
    /** The last step of every task running here. */
    private val steps = HashMap<String, Long>()
    /** The tasks that reported their end, most recent last; bounded. */
    private val ended = LinkedHashSet<String>()

    /** Called after every record, for the connection to send it. */
    @Volatile var onRecord: (() -> Unit)? = null

    /** Take a task on. False when it is already running here or has ended: it runs once. */
    @Synchronized
    fun begin(task: String): Boolean {
        if (task in steps || task in ended) return false
        steps[task] = 0
        return true
    }

    /**
     * Number a report and keep it. False when its task has already ended: nothing follows a
     * terminal report.
     */
    fun record(event: AgentEvent): Boolean {
        synchronized(this) {
            val task = event.reportedTask
            var step: Long? = null
            if (task != null) {
                if (task in ended) return false
                // Only a task that ran here has steps: a report on another one carries none.
                steps[task]?.let {
                    step = it + 1
                    steps[task] = it + 1
                }
                if (event.isTerminal) {
                    steps.remove(task)
                    ended.add(task)
                    if (ended.size > REMEMBERED) ended.remove(ended.first())
                }
            }
            val pos = next++
            val fields = agentJson.encodeToJsonElement(AgentEvent.serializer(), event).jsonObject.toMutableMap()
            fields["pos"] = JsonPrimitive(pos)
            fields["journal_session"] = JsonPrimitive(session)
            fields["agent_ts"] = JsonPrimitive(System.currentTimeMillis() / 1000.0)
            step?.let { fields["task_step"] = JsonPrimitive(it) }
            owed.addLast(pos to JsonObject(fields).toString())
        }
        onRecord?.invoke()
        return true
    }

    /** The server has everything through [through]: forget it. */
    @Synchronized
    fun ack(through: Long) {
        while (owed.isNotEmpty() && owed.first().first <= through) owed.removeFirst()
        if (through > acked) acked = minOf(through, next - 1)
    }

    /** The reports after [pos], in order, as they go on the wire. */
    @Synchronized fun owedAfter(pos: Long): List<Pair<Long, String>> = owed.filter { it.first > pos }

    /** Whether anything is still unacknowledged. */
    val owes: Boolean @Synchronized get() = owed.isNotEmpty()

    /** The highest position the server has acknowledged. */
    val ackedThrough: Long @Synchronized get() = acked

    /**
     * The positions from [expected] on that are no longer here, or null when [expected] can be
     * sent. The server only asks for what it has not taken, so this is a loss, said out loud
     * with `JOURNAL_SKIP`.
     */
    @Synchronized
    fun missing(expected: Long): LongRange? {
        val lowest = owed.firstOrNull()?.first ?: next
        return if (expected < lowest) expected until lowest else null
    }

    @Synchronized
    fun status(task: String): String = when (task) {
        in steps -> TaskStatus.RUNNING
        in ended -> TaskStatus.ENDED
        else -> TaskStatus.UNKNOWN
    }

    private companion object {
        const val REMEMBERED = 1000
    }
}
