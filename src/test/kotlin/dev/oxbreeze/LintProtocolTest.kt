package dev.oxbreeze

import com.google.gson.*
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import kotlin.test.*

class LintProtocolTest {
    @Test fun realViteLintDiagnosticsAndQuickFixes() {
        val binary = System.getenv("OXBREEZE_TEST_VP") ?: return
        checkServer(Path.of(binary), true, false)
        checkServer(Path.of(binary), true, true)
    }

    @Test fun realStandaloneLintDiagnosticsAndQuickFixes() {
        val binary = System.getenv("OXBREEZE_TEST_OXLINT") ?: return
        checkServer(Path.of(binary), false, false)
        checkServer(Path.of(binary), false, true)
    }

    private fun checkServer(binary: Path, vite: Boolean, pull: Boolean) {
        val directory = Files.createTempDirectory("oxbreeze lint protocol ")
        val executor = Executors.newSingleThreadExecutor()
        var process: Process? = null
        try {
            Files.writeString(directory.resolve("package.json"), """{"type":"module"}""")
            Files.writeString(directory.resolve(".oxlintrc.json"), """{"rules":{"no-debugger":"error","no-unused-vars":"off"}}""")
            Files.writeString(directory.resolve("test.ts"), "debugger;\n")
            val running = Formatter(binary, directory, vite, Tool.LINT).commandLine().createProcess()
            process = running
            val received = LinkedBlockingQueue<JsonObject>()
            val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
            val stderr = StringBuilder()
            Thread { running.errorStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(stderr) { stderr.append(it).append('\n') } } } }.apply { isDaemon = true; start() }
            fun send(message: JsonObject) {
                val bytes = message.toString().toByteArray(UTF_8)
                synchronized(running.outputStream) {
                    running.outputStream.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(UTF_8))
                    running.outputStream.write(bytes)
                    running.outputStream.flush()
                }
            }
            executor.submit {
                val input = BufferedInputStream(running.inputStream)
                while (running.isAlive) {
                    val message = FormatterSession.readMessage(input)
                    trace.add(message.toString())
                    if (message.has("method") && message.has("id")) {
                        val result = if (message["method"].asString == "workspace/configuration") JsonArray().apply {
                            message.getAsJsonObject("params").getAsJsonArray("items").forEach { add(Gson().toJsonTree(LintOptions.options)) }
                        } else JsonNull.INSTANCE
                        send(JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", message["id"]); add("result", result) })
                    } else received.put(message)
                }
            }
            var id = 0
            fun notify(method: String, params: JsonElement) = send(JsonObject().apply {
                addProperty("jsonrpc", "2.0"); addProperty("method", method); add("params", params)
            })
            fun awaitMessage(predicate: (JsonObject) -> Boolean): JsonObject {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (System.nanoTime() < deadline) {
                    val message = received.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (predicate(message)) return message
                }
                error("Timed out waiting for lint server: ${synchronized(stderr) { stderr.toString() }}; messages: $trace")
            }
            fun request(method: String, params: JsonElement): JsonElement {
                val requestId = ++id
                send(JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id", requestId); addProperty("method", method); add("params", params) })
                val response = awaitMessage { it.get("id")?.asInt == requestId }
                check(!response.has("error")) { response.toString() }
                return response["result"]
            }
            fun json(value: String) = JsonParser.parseString(value)
            val uri = directory.resolve("test.ts").toUri().toString()
            val rootUri = directory.toUri().toString()
            val initialized = request("initialize", JsonObject().apply {
                addProperty("processId", ProcessHandle.current().pid())
                addProperty("rootUri", rootUri)
                add("capabilities", json("""{"general":{"positionEncodings":["utf-16"]},"workspace":{"configuration":true},"textDocument":{"codeAction":{"codeActionLiteralSupport":{"codeActionKind":{"valueSet":["quickfix"]}}}}}""").asJsonObject.apply {
                    if (pull) getAsJsonObject("textDocument").add("diagnostic", json("""{"dynamicRegistration":false,"relatedDocumentSupport":false}"""))
                })
                add("initializationOptions", Gson().toJsonTree(LintOptions.initialization(rootUri)))
            })
            assertNotNull(initialized.asJsonObject["capabilities"])
            notify("initialized", JsonObject())
            notify("textDocument/didOpen", JsonObject().apply {
                add("textDocument", JsonObject().apply {
                    addProperty("uri", uri); addProperty("languageId", "typescript"); addProperty("version", 1); addProperty("text", "debugger;\n")
                })
            })
            fun pullDiagnostics() = request("textDocument/diagnostic", Gson().toJsonTree(mapOf("textDocument" to mapOf("uri" to uri)))).asJsonObject.getAsJsonArray("items")
            val diagnostics = if (pull) pullDiagnostics() else awaitMessage {
                it.get("method")?.asString == "textDocument/publishDiagnostics" && it.getAsJsonObject("params")["uri"].asString == uri && it.getAsJsonObject("params").getAsJsonArray("diagnostics").size() > 0
            }.getAsJsonObject("params").getAsJsonArray("diagnostics")
            assertTrue(diagnostics.toString().contains("debugger"), diagnostics.toString())
            val actions = request("textDocument/codeAction", JsonObject().apply {
                add("textDocument", json(Gson().toJson(mapOf("uri" to uri))))
                add("range", diagnostics[0].asJsonObject["range"])
                add("context", JsonObject().apply { add("diagnostics", diagnostics); add("only", json("""["quickfix"]""")) })
            })
            assertTrue(actions.isJsonArray && actions.asJsonArray.size() > 0, actions.toString())
            notify("textDocument/didChange", JsonObject().apply {
                add("textDocument", Gson().toJsonTree(mapOf("uri" to uri, "version" to 2)))
                add("contentChanges", Gson().toJsonTree(listOf(mapOf("text" to "export {};\n"))))
            })
            if (pull) assertTrue(pullDiagnostics().isEmpty)
            else awaitMessage { it.get("method")?.asString == "textDocument/publishDiagnostics" && it.getAsJsonObject("params")["uri"].asString == uri && it.getAsJsonObject("params").getAsJsonArray("diagnostics").isEmpty }
        } finally {
            process?.let { runCatching { it.descendants().forEach { child -> child.destroyForcibly() } }; it.destroyForcibly() }
            executor.shutdownNow()
            directory.toFile().deleteRecursively()
        }
    }
}
