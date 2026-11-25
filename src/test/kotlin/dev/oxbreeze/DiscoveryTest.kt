package dev.oxbreeze

import java.nio.file.Files
import kotlin.test.*

class DiscoveryTest {
    private val suffix get() = if (System.getProperty("os.name").startsWith("Windows")) ".cmd" else ""

    @Test fun lintDiscoveryIsIndependentFromFormatting() {
        val root = Files.createTempDirectory("oxbreeze lint ")
        try {
            Files.writeString(root.resolve("package.json"), "{}")
            val bin = root.resolve("node_modules/.bin/oxlint$suffix")
            Files.createDirectories(bin.parent)
            Files.writeString(bin, "")
            val file = root.resolve("src/main.ts")
            val lint = Discovery.resolve(file, root, "", Tool.LINT)!!
            assertEquals(bin, lint.executable)
            assertEquals(listOf("--lsp"), lint.serverArguments())
            assertFalse(lint.vite)
            assertEquals(root, lint.directory)
            assertNull(Discovery.resolve(file, root, ""))
            assertEquals(root.resolve("tools/my oxlint.exe"), Discovery.resolve(file, root, "tools/my oxlint.exe", Tool.LINT)!!.executable)
            val vp = Discovery.resolve(file, root, "tools/vp.cmd", Tool.LINT)!!
            assertEquals(listOf("lint", "--lsp"), vp.serverArguments())
            assertEquals(listOf("/d", "/c", root.resolve("tools/vp.cmd").toString(), "lint", "--lsp"), vp.commandLine().parametersList.list)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun lintFindsHoistedVpButSeparatesNestedExecutables() {
        val root = Files.createTempDirectory("oxbreeze lint workspace ")
        try {
            Files.writeString(root.resolve("package.json"), """{"workspaces":["packages/*"],"devDependencies":{"vite-plus":"1"}}""")
            val bin = root.resolve("node_modules/.bin/vp$suffix")
            Files.createDirectories(bin.parent)
            Files.writeString(bin, "")
            val nested = Files.createDirectories(root.resolve("packages/frontend"))
            Files.writeString(nested.resolve("package.json"), "{}")
            val file = nested.resolve("src/main.ts")
            val hoisted = Discovery.resolve(file, nested, "", Tool.LINT)!!
            assertEquals(root, hoisted.directory)
            assertEquals(listOf("lint", "--lsp"), hoisted.serverArguments())
            val local = nested.resolve("node_modules/.bin/vp$suffix")
            Files.createDirectories(local.parent)
            Files.writeString(local, "")
            val separate = Discovery.resolve(file, root, "", Tool.LINT)!!
            assertEquals(nested, separate.directory)
            assertEquals(local, separate.executable)
            assertNotEquals(hoisted, separate)
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun openedFrontendFindsHoistedWorkspaceVp() {
        val root = Files.createTempDirectory("oxbreeze workspace ")
        try {
            Files.writeString(root.resolve("package.json"), """{"workspaces":["frontend"]}""")
            val frontend = Files.createDirectories(root.resolve("frontend"))
            Files.writeString(frontend.resolve("package.json"), """{"devDependencies":{"vite-plus":"1.1.0"}}""")
            val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".cmd" else ""
            val bin = root.resolve("node_modules/.bin/vp$suffix")
            Files.createDirectories(bin.parent)
            Files.writeString(bin, "")
            val result = Discovery.resolve(frontend.resolve("src/main.ts"), frontend, "")!!
            assertEquals(bin, result.executable)
            assertEquals(root, result.directory)
            assertTrue(result.vite)
        } finally { root.toFile().deleteRecursively() }
    }
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
            assertEquals(listOf("fmt", "--lsp"), result.serverArguments())
            assertEquals(frontend, result.directory)
            assertEquals(listOf("fmt", "--stdin-filepath", file.toString()), result.arguments(file))
            val override = Discovery.resolve(file, root, "tools/oxfmt.exe")!!
            assertFalse(override.vite)
            assertEquals(listOf("--lsp"), override.serverArguments())
            assertEquals(root.resolve("tools/oxfmt.exe"), override.executable)
        } finally { root.toFile().deleteRecursively() }
    }
}
