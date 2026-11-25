package dev.oxbreeze

import com.google.gson.*
import java.io.BufferedInputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** All calls are made on workers. The independent watchdog also covers blocked pipe writes. */
class FormatterSession(private val formatter: Formatter, private val timeoutMillis: Long = 10_000) : AutoCloseable {
    private val process: Process
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement>>()
    private val ids = AtomicInteger()
    @Volatile private var closed = false
    private var initialized = false
    val isRunning: Boolean get() = !closed && process.isAlive
    private val stderr = StringBuilder()

    init {
        process = formatter.commandLine().createProcess()
        Thread({
            try {
                val input = BufferedInputStream(process.inputStream)
                while (!closed) {
                    val message = readMessage(input)
                    val id = message.get("id") ?: continue
                    if (message.has("method")) {
                        val result = if (message["method"].asString == "workspace/configuration") {
                            JsonArray().apply { message.getAsJsonObject("params").getAsJsonArray("items").forEach { add(JsonObject()) } }
                        } else JsonNull.INSTANCE
                        send(JsonObject().apply { addProperty("jsonrpc", "2.0"); add("id", id); add("result", result) })
                    } else {
                        val response = pending.remove(id.asInt) ?: continue
                        if (message.has("error")) response.completeExceptionally(IllegalStateException(message["error"].toString()))
                        else response.complete(message.get("result") ?: JsonNull.INSTANCE)
                    }
                }
            } catch (e: Exception) {
                pending.values.forEach { it.completeExceptionally(IllegalStateException("Formatter connection closed: ${e.message}", e)) }
                close()
            }
        }, "OxBreeze LSP reader").apply { isDaemon = true; start() }
        Thread({ process.errorStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(stderr) {
            if (stderr.length < 4096) stderr.append(it).append('\n')
        } } } }, "OxBreeze formatter stderr").apply { isDaemon = true; start() }
    }

    @Synchronized fun initialize() {
        check(!closed && process.isAlive) { "Formatter exited. ${synchronized(stderr) { stderr.toString() }}" }
        val watchdog = timer.schedule({ close() }, timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            if (!initialized) {
                request("initialize", json("""{"processId":${ProcessHandle.current().pid()},"rootUri":${Gson().toJson(formatter.directory.toUri().toString())},"capabilities":{"general":{"positionEncodings":["utf-16"]}},"initializationOptions":{"fmt":{"enable":true},"lint":{"enable":false}}}"""))
                notify("initialized", JsonObject())
                initialized = true
            }
        } finally { watchdog.cancel(false) }
    }

    @Synchronized fun format(file: Path, source: String): String {
        initialize()
        val watchdog = timer.schedule({ close() }, timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            val uri = file.toUri().toString()
            notify("textDocument/didOpen", JsonObject().apply { add("textDocument", JsonObject().apply {
                addProperty("uri", uri); addProperty("languageId", language(file)); addProperty("version", 1); addProperty("text", source)
            }) })
            try {
                val edits = request("textDocument/formatting", JsonObject().apply {
                    add("textDocument", JsonObject().apply { addProperty("uri", uri) })
                    add("options", json("""{"tabSize":2,"insertSpaces":true}"""))
                })
                return documentText(applyEdits(source, edits))
            } finally { if (!closed) notify("textDocument/didClose", JsonObject().apply { add("textDocument", JsonObject().apply { addProperty("uri", uri) }) }) }
        } finally { watchdog.cancel(false) }
    }

    private fun request(method: String, params: JsonObject): JsonElement {
        val id = ids.incrementAndGet()
        val response = CompletableFuture<JsonElement>()
        pending[id] = response
        try {
            send(JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params) })
            return response.get(10, TimeUnit.SECONDS)
        } finally { pending.remove(id) }
    }
    private fun notify(method: String, params: JsonObject) = send(JsonObject().apply {
        addProperty("jsonrpc", "2.0"); addProperty("method", method); add("params", params)
    })
    private fun send(message: JsonObject) {
        val bytes = message.toString().toByteArray(UTF_8)
        synchronized(process.outputStream) {
            process.outputStream.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(UTF_8))
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
    }
    override fun close() {
        closed = true
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Formatter stopped or timed out; the next save will restart it.")) }
        // Never wait for a process on the UI thread, including project disposal.
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    companion object {
        internal fun documentText(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')
        private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "OxBreeze watchdog").apply { isDaemon = true } }
        private fun json(text: String) = JsonParser.parseString(text).asJsonObject
        private fun language(file: Path) = when (file.toString().substringAfterLast('.')) {
            "ts", "mts", "cts" -> "typescript"
            "tsx" -> "typescriptreact"
            "jsx" -> "javascriptreact"
            "js", "mjs", "cjs" -> "javascript"
            else -> file.toString().substringAfterLast('.')
        }
        internal fun readMessage(input: java.io.InputStream): JsonObject {
            var length = -1
            var total = 0
            while (true) {
                val line = StringBuilder()
                while (true) {
                    val byte = input.read()
                    if (byte < 0) throw EOFException()
                    check(++total <= 8192) { "Oversized LSP header" }
                    if (byte == 10) break
                    if (byte != 13) line.append(byte.toChar())
                }
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) length = line.toString().substringAfter(':').trim().toInt()
            }
            check(length in 0..32_000_000) { "Invalid LSP message length" }
            val bytes = input.readNBytes(length)
            check(bytes.size == length) { "Truncated LSP message" }
            return json(String(bytes, UTF_8))
        }
        internal fun applyEdits(source: String, result: JsonElement): String {
            if (result.isJsonNull) return source
            fun offset(position: JsonObject): Int {
                val line = position["line"].asInt
                val character = position["character"].asInt
                require(line >= 0 && character >= 0)
                var start = 0
                repeat(line) { val end = source.indexOf('\n', start); require(end >= 0); start = end + 1 }
                val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
                require(start + character <= end)
                return start + character
            }
            val edits = result.asJsonArray.map { element ->
                val edit = element.asJsonObject
                val range = edit.getAsJsonObject("range")
                Triple(offset(range.getAsJsonObject("start")), offset(range.getAsJsonObject("end")), edit["newText"].asString)
            }.sortedByDescending { it.first }
            val text = StringBuilder(source)
            var boundary = source.length
            edits.forEach { (start, end, replacement) -> require(start <= end && end <= boundary); text.replace(start, end, replacement); boundary = start }
            return text.toString()
        }
    }
}
