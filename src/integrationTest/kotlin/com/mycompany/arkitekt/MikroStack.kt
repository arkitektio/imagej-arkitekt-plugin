package com.mycompany.arkitekt

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * A real mikro, started in Docker for a test run and removed after it: the server, its database,
 * redis and object storage (`src/integrationTest/stack`). The same stack, in the same order, as
 * mikro's own Python suite starts (`tests/stack.py` there).
 */
class MikroStack private constructor(
        private val directory: File,
        private val project: String,
        val mikroPort: Int,
        val storagePort: Int
) {
    private fun compose(vararg arguments: String, timeoutMinutes: Long = 10): Int {
        val command = listOf("docker", "compose", "-p", project, "-f", "docker-compose.yml") + arguments
        val process =
                ProcessBuilder(command)
                        .directory(directory)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .apply {
                            environment()["MIKRO_HOST_PORT"] = mikroPort.toString()
                            environment()["RUSTFS_HOST_PORT"] = storagePort.toString()
                        }
                        .start()
        check(process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            "docker compose ${arguments.joinToString(" ")} did not finish in $timeoutMinutes minutes"
        }
        return process.exitValue()
    }

    private fun start() {
        check(compose("up", "-d") == 0) { "The mikro stack did not start" }
        // The buckets the server's configuration names.
        check(compose("run", "--rm", "initc", "python", "init.py") == 0) { "The buckets could not be made" }
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(4)
        while (!answers()) {
            check(System.currentTimeMillis() < deadline) { "mikro did not answer on port $mikroPort" }
            Thread.sleep(1000)
        }
    }

    private fun answers(): Boolean =
            try {
                val connection = URL("http://127.0.0.1:$mikroPort/graphql").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 2000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("""{"query":"{__typename}"}""".toByteArray()) }
                connection.responseCode == 200
            } catch (e: Exception) {
                false
            }

    /** Remove the stack and what it stored. */
    fun stop() {
        compose("down", "-v", "--remove-orphans", timeoutMinutes = 3)
    }

    companion object {
        /** The token the stack's configuration maps to its test user (`configs/mikro.yaml`). */
        const val TOKEN = "test"

        /** Whether there is a Docker to start it in. */
        fun available(): Boolean =
                try {
                    ProcessBuilder("docker", "compose", "version")
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor() == 0
                } catch (e: Exception) {
                    false
                }

        /** Start it on two free ports. Stopped again if it does not come up. */
        fun start(): MikroStack {
            val directory = File(System.getProperty("arkitekt.stack") ?: "src/integrationTest/stack")
            // Held open together so the two differ, released before compose binds them.
            val (mikro, storage) =
                    ServerSocket(0).use { a -> ServerSocket(0).use { b -> a.localPort to b.localPort } }
            val stack = MikroStack(directory, "arkitekt-plugin-it-${ProcessHandle.current().pid()}", mikro, storage)
            try {
                stack.start()
            } catch (e: Throwable) {
                stack.stop()
                throw e
            }
            return stack
        }
    }
}
