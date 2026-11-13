package telegram

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream

/**
 * Minimal MCP server over stdio (newline-delimited JSON-RPC 2.0). The official
 * SDKs don't model the experimental claude/channel notifications, and the
 * protocol surface used here is four methods, so it's done by hand.
 */
class McpServer(
    private val name: String,
    private val version: String,
    private val instructions: String,
    private val tools: () -> JsonArray,
    private val callTool: suspend (name: String, args: JsonObject) -> JsonObject,
    private val onNotification: suspend (method: String, params: JsonObject) -> Unit,
) {
    private val out: PrintStream = PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), false, Charsets.UTF_8)
    private val lock = Any()

    @Volatile var closed = false
        private set

    init {
        // Anything printed to System.out by a library would corrupt the protocol stream.
        System.setOut(System.err)
    }

    private fun write(msg: JsonObject) {
        synchronized(lock) {
            if (closed) throw IllegalStateException("MCP connection closed")
            out.print(msg.toString())
            out.print('\n')
            out.flush()
            if (out.checkError()) {
                closed = true
                throw IllegalStateException("MCP connection closed")
            }
        }
    }

    /** Throws when the client is gone — callers use that to tell the sender. */
    fun notify(method: String, params: JsonObject) {
        write(json("jsonrpc" to "2.0", "method" to method, "params" to params))
    }

    /** Reads stdin until EOF. Returns when the client disconnects. */
    suspend fun run(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        while (true) {
            val line = try { reader.readLine() } catch (_: Exception) { null } ?: break
            if (line.isBlank()) continue
            val msg = try { Json.parseToJsonElement(line).jsonObject } catch (e: Exception) {
                log("bad MCP frame: ${e.message}")
                continue
            }
            scope.launch { handle(msg) }
        }
        closed = true
    }

    private suspend fun handle(msg: JsonObject) {
        val method = msg.str("method")
        val id = msg["id"]
        val params = msg.o("params") ?: JsonObject(emptyMap())
        if (method == null) return // a response to something we sent — we send no requests
        if (id == null || id is JsonNull) {
            try { onNotification(method, params) } catch (e: Exception) { log("notification $method failed: $e") }
            return
        }
        val result: JsonObject? = try {
            when (method) {
                "initialize" -> json(
                    "protocolVersion" to (params.str("protocolVersion") ?: "2025-06-18"),
                    "capabilities" to json(
                        "tools" to json(),
                        "experimental" to json(
                            "claude/channel" to json(),
                            // Permission-relay opt-in: we authenticate the replier via
                            // access.allowFrom before anything reaches the session.
                            "claude/channel/permission" to json(),
                        ),
                    ),
                    "serverInfo" to json("name" to name, "version" to version),
                    "instructions" to instructions,
                )
                "ping" -> json()
                "tools/list" -> json("tools" to tools())
                "tools/call" -> callTool(params.str("name") ?: "", params.o("arguments") ?: JsonObject(emptyMap()))
                else -> null
            }
        } catch (e: Exception) {
            respondError(id, -32603, e.message ?: e.toString())
            return
        }
        if (result == null) respondError(id, -32601, "Method not found: $method")
        else try { write(json("jsonrpc" to "2.0", "id" to id, "result" to result)) } catch (_: Exception) {}
    }

    private fun respondError(id: JsonElement, code: Int, message: String) {
        try {
            write(json("jsonrpc" to "2.0", "id" to id, "error" to json("code" to code, "message" to message)))
        } catch (_: Exception) {}
    }
}
