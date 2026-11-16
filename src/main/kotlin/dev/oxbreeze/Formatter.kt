package dev.oxbreeze

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

data class Formatter(val executable: Path, val directory: Path, val vite: Boolean) {
    fun arguments(file: Path) = (if (vite) listOf("fmt") else emptyList()) + listOf("--stdin-filepath", file.toString())
}

object Discovery {
    fun resolve(file: Path, root: Path, override: String): Formatter? {
        val ancestors = generateSequence(file.parent) { if (it == root) null else it.parent }
            .takeWhile { it.startsWith(root) }.toList()
        val packages = ancestors.filter { Files.isRegularFile(it.resolve("package.json")) }
        val directory = packages.firstOrNull() ?: root
        if (override.isNotBlank()) {
            val path = Path.of(override).let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
            return Formatter(path, directory, path.fileName.toString().substringBeforeLast('.').equals("vp", true))
        }
        val vite = packages.any { dir ->
            runCatching {
                val json = JsonParser.parseString(Files.readString(dir.resolve("package.json"))).asJsonObject
                listOf("dependencies", "devDependencies").any { json.getAsJsonObject(it)?.has("vite-plus") == true }
            }.getOrDefault(false)
        }
        val names = if (vite) listOf("vp") else listOf("oxfmt")
        for (dir in ancestors) for (name in names) {
            val suffixes = if (System.getProperty("os.name").startsWith("Windows")) listOf(".cmd", ".exe", "") else listOf("")
            for (suffix in suffixes) {
                val binary = dir.resolve("node_modules/.bin/$name$suffix")
                if (Files.isRegularFile(binary)) return Formatter(binary, if (Files.isRegularFile(dir.resolve("package.json"))) dir else directory, vite)
            }
        }
        return null
    }
}
