package dev.oxbreeze

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.InitializeResult
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal object LintFiles {
    private val extensions = "js jsx mjs cjs ts tsx mts cts vue svelte astro".split(' ').toSet()
    fun supported(file: VirtualFile) = file.isInLocalFileSystem && file.extension?.lowercase() in extensions
}

internal object LintOptions {
    val options = mapOf("run" to "onType", "lint" to mapOf("enable" to true), "fmt" to mapOf("enable" to false))
    fun initialization(workspaceUri: String) = listOf(mapOf("workspaceUri" to workspaceUri, "options" to options))
}

class OxlintProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        // The service also listens to editor opens: the native provider callback is skipped
        // for files under an existing server root, including nested independent packages.
        project.service<LintService>().opened(file)
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?) =
        LspClientWidgetItem(lspClient, currentFile, ServiceWidget.icon, SettingsPage::class.java)
}

class LintFileListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        source.project.service<LintService>().opened(file)
    }
}

@Service(Service.Level.PROJECT)
class LintService(private val project: Project) : Disposable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "OxBreeze lint lifecycle").apply { isDaemon = true } }
    private val states = ConcurrentHashMap<Formatter, String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var disposed = false
    @Volatile private var generation = 0L

    fun opened(file: VirtualFile) {
        if (disposed || !LintFiles.supported(file)) return
        worker.execute { if (!disposed) start(file) }
    }

    private fun start(file: VirtualFile) {
        if (project.isDisposed || disposed || !project.service<Settings>().state.lintEnabled) return
        val eligible = ReadAction.computeBlocking<Boolean, RuntimeException> {
            file.isValid && ProjectFileIndex.getInstance(project).isInContent(file) && !ProjectFileIndex.getInstance(project).isExcluded(file)
        }
        if (!eligible) return
        val root = project.basePath?.let(Path::of) ?: return
        val override = project.service<Settings>().state.lintExecutable
        val tool = try { Discovery.resolve(Path.of(file.path), root, override, Tool.LINT) } catch (e: Exception) {
            missing.add("${file.parent.path}: ${e.message}")
            Services.changed(project)
            return
        }
        if (tool == null) {
            missing.add(file.parent.path)
            Services.changed(project)
            return
        }
        missing.remove(file.parent.path)
        val directory = ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
            LocalFileSystem.getInstance().findFileByNioFile(tool.directory)
        } ?: return
        val token = generation
        val descriptor = OxlintDescriptor(project, directory, tool, root, override) { state ->
            if (!disposed && generation == token) {
                states[tool] = state
                Services.changed(project)
            }
        }
        LspClientManager.getInstance(project).ensureClientStarted(OxlintProvider::class.java, descriptor)
    }

    fun restart() {
        if (disposed) return
        worker.execute {
            if (disposed || project.isDisposed) return@execute
            generation++
            states.clear()
            missing.clear()
            LspClientManager.getInstance(project).stopClients(OxlintProvider::class.java)
            val files = ReadAction.computeBlocking<Array<VirtualFile>, RuntimeException> { FileEditorManager.getInstance(project).openFiles }
            files.filter(LintFiles::supported).forEach { if (!disposed) start(it) }
            Services.changed(project)
        }
    }

    fun status(): String {
        if (!project.service<Settings>().state.lintEnabled) return "Disabled"
        val counts = states.values.groupingBy { it }.eachCount().entries.joinToString(", ") { (state, count) -> "$state ($count)" }
        val unavailable = if (missing.isEmpty()) "" else "Not found (${missing.size} file directories): install oxlint or vite-plus, or set an override"
        return listOf(counts, unavailable).filter(String::isNotEmpty).joinToString("; ").ifEmpty { "Idle" }
    }

    override fun dispose() {
        disposed = true
        generation++
        worker.shutdownNow()
        states.clear()
        // The platform owns and disposes the native LSP clients with the project.
    }
}

internal class OxlintDescriptor(
    project: Project,
    directory: VirtualFile,
    private val tool: Formatter,
    private val projectRoot: Path,
    private val executableOverride: String,
    private val stateChanged: (String) -> Unit,
) : LspClientDescriptor(project, "OxBreeze oxlint", directory) {
    // Snapshot the override for this descriptor, and cache discovery per directory.
    // Restart replaces the descriptor and its cache when configuration changes.
    private val assignments = ConcurrentHashMap<Path, Boolean>()
    override fun isSupportedFile(file: VirtualFile): Boolean = LintFiles.supported(file) &&
        assignments.computeIfAbsent(Path.of(file.path).parent) { parent ->
            runCatching { Discovery.resolve(parent.resolve("oxbreeze.ts"), projectRoot, executableOverride, Tool.LINT) == tool }.getOrDefault(false)
        }

    override fun createCommandLine() = tool.commandLine()

    override fun startServerProcess(): com.intellij.execution.process.BaseProcessHandler<*> {
        stateChanged("Starting")
        return try { super.startServerProcess() } catch (e: Exception) {
            stateChanged("Error: ${e.message ?: "Failed to start oxlint"}")
            throw e
        }
    }

    override fun createInitializationOptions(): Any = LintOptions.initialization(getFileUri(roots.single()))
    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any = LintOptions.options
    override val lspCustomization = object : LspCustomization() {
        override val formattingCustomizer = LspFormattingDisabled
    }
    override val lspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) { stateChanged("Running") }
        override fun serverStopped(shutdownNormally: Boolean) {
            stateChanged(if (shutdownNormally) "Stopped" else "Error: oxlint exited unexpectedly; restart to retry")
        }
    }
}
