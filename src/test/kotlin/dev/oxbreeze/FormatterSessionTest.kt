package dev.oxbreeze

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.test.*

class FormatterSessionTest {
    @Test fun stalledServerIsKilledByWatchdog() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val directory = java.nio.file.Files.createTempDirectory("oxbreeze stalled ")
        try {
            val script = directory.resolve("stalled.cmd")
            java.nio.file.Files.writeString(script, "@echo off\r\nping 127.0.0.1 -n 30 >nul\r\n")
            val start = System.nanoTime()
            FormatterSession(Formatter(script, directory, false), 300).use { session ->
                assertFails { session.format(directory.resolve("test.ts"), "const x=1") }
                assertFalse(session.isRunning)
            }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5000, "Watchdog failed to stop a hung server promptly")
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun framingUsesUtf8ByteLength() {
        val body = """{"result":"你好😀"}""".toByteArray()
        val frame = "Content-Length: ${body.size}\r\n\r\n".toByteArray() + body
        assertEquals("你好😀", FormatterSession.readMessage(ByteArrayInputStream(frame))["result"].asString)
        assertFails { FormatterSession.readMessage(ByteArrayInputStream(frame.dropLast(1).toByteArray())) }
    }
    @Test fun editsUseUtf16AndOriginalOffsets() {
        val edits = JsonParser.parseString("""[
            {"range":{"start":{"line":0,"character":2},"end":{"line":0,"character":3}},"newText":"b"},
            {"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}},"newText":"long"}
        ]""")
        assertEquals("😀b\nlong", FormatterSession.applyEdits("😀a\nx", edits))
        assertEquals("unchanged", FormatterSession.applyEdits("unchanged", JsonParser.parseString("null")))
    }
    @Test fun realViteSessionFormatsRepeatedly() {
        val binary = System.getenv("OXBREEZE_TEST_VP") ?: return
        val directory = Path.of(binary).parent.parent.parent
        FormatterSession(Formatter(Path.of(binary), directory, true)).use { session ->
            repeat(3) { index ->
                val start = System.nanoTime()
                val result = session.format(directory.resolve("src/oxbreeze-smoke.ts"), "const value={a:$index}")
                assertTrue(result.contains("a: $index"), result)
                println("Vite+ request $index: ${(System.nanoTime() - start) / 1_000_000}ms")
            }
        }
    }
}
