package dev.oxbreeze

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryTest {
    @Test fun defaultsExcludeOnlyHbs() {
        assertFalse("hbs" in Formats.defaults())
        assertEquals(Formats.extensions.toSet() - "hbs", Formats.defaults().toSet())
    }
    @Test fun nestedVitePackageUsesVp() {
        val root = Files.createTempDirectory("oxbreeze test ")
        try {
            val frontend = Files.createDirectories(root.resolve("frontend"))
            Files.writeString(frontend.resolve("package.json"), """{"devDependencies":{"vite-plus":"catalog:"}}""")
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".cmd" else ""
            val bin = frontend.resolve("node_modules/.bin/vp$suffix")
            Files.createDirectories(bin.parent)
            Files.writeString(bin, "")
            val file = frontend.resolve("src/main.ts")
            val result = Discovery.resolve(file, root, "")!!
            assertTrue(result.vite)
            assertEquals(frontend, result.directory)
            assertEquals(listOf("fmt", "--stdin-filepath", file.toString()), result.arguments(file))
            val override = Discovery.resolve(file, root, "tools/oxfmt.exe")!!
            assertFalse(override.vite)
            assertEquals(root.resolve("tools/oxfmt.exe"), override.executable)
        } finally { root.toFile().deleteRecursively() }
    }
}
