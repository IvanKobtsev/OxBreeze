package dev.oxbreeze

import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import java.nio.file.Files
import java.nio.file.Path

enum class Tool(val binary: String, val subcommand: String) {
    FORMAT("oxfmt", "fmt"), LINT("oxlint", "lint")
}

data class Formatter(val executable: Path, val directory: Path, val vite: Boolean, val tool: Tool = Tool.FORMAT) {
    fun serverArguments() = (if (vite) listOf(tool.subcommand) else emptyList()) + "--lsp"
    fun arguments(file: Path) = (if (vite) listOf("fmt") else emptyList()) + listOf("--stdin-filepath", file.toString())
    fun commandLine(): GeneralCommandLine {
        val shim = executable.toString().let { it.endsWith(".cmd", true) || it.endsWith(".bat", true) }
        val command = if (shim) GeneralCommandLine("cmd.exe").withParameters("/d", "/c", executable.toString())
            else GeneralCommandLine(executable.toString())
        return command.withParameters(serverArguments()).withWorkDirectory(directory.toFile())
    }
}

object Discovery {
    fun resolve(file: Path, root: Path, override: String, tool: Tool = Tool.FORMAT): Formatter? {
        // IDE projects may open a single package of a workspace with hoisted binaries.
        val boundary = generateSequence(root) { it.parent }.firstOrNull { dir ->
            Files.exists(dir.resolve(".git")) || runCatching {
                JsonParser.parseString(Files.readString(dir.resolve("package.json"))).asJsonObject.has("workspaces")
            }.getOrDefault(false)
        } ?: root
        val ancestors = generateSequence(file.parent) { if (it == boundary) null else it.parent }
            .takeWhile { it.startsWith(boundary) }.toList()
        val packages = ancestors.filter { Files.isRegularFile(it.resolve("package.json")) }
        val directory = packages.firstOrNull() ?: root
        if (override.isNotBlank()) {
            val path = Path.of(override).let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
            return Formatter(path, directory, path.fileName.toString().substringBeforeLast('.').equals("vp", true), tool)
        }
        val vite = packages.any { dir ->
            runCatching {
                val json = JsonParser.parseString(Files.readString(dir.resolve("package.json"))).asJsonObject
                listOf("dependencies", "devDependencies").any { json.getAsJsonObject(it)?.has("vite-plus") == true }
            }.getOrDefault(false)
        }
        val names = if (vite) listOf("vp") else listOf(tool.binary)
        for (dir in ancestors) for (name in names) {
            val suffixes = if (System.getProperty("os.name").startsWith("Windows")) listOf(".cmd", ".exe", "") else listOf("")
            for (suffix in suffixes) {
                val binary = dir.resolve("node_modules/.bin/$name$suffix")
                if (Files.isRegularFile(binary)) return Formatter(binary, if (Files.isRegularFile(dir.resolve("package.json"))) dir else directory, vite, tool)
            }
        }
        return null
    }
}
