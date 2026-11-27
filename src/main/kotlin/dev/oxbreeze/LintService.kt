package dev.oxbreeze

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.*
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import org.eclipse.lsp4j.ConfigurationItem
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
    companion object {
        private val icon = IconLoader.getIcon("/icons/oxbreeze.svg", OxlintProvider::class.java)
    }

    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        // The service also listens to editor opens: the native provider callback is skipped
        // for files under an existing server root, including nested independent packages.
        project.service<LintService>().opened(file)
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?) =
        LspClientWidgetItem(lspClient, currentFile, icon, SettingsPage::class.java)
}

class LintFileListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        source.project.service<LintService>().opened(file)
    }
}

@Service(Service.Level.PROJECT)
class LintService(private val project: Project) : Disposable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "OxBreeze lint lifecycle").apply { isDaemon = true } }
    @Volatile private var disposed = false

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
        val tool = try { Discovery.resolve(Path.of(file.path), root, override, Tool.LINT) } catch (_: Exception) {
            return
        } ?: return
        val directory = ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
            LocalFileSystem.getInstance().findFileByNioFile(tool.directory)
        } ?: return
        val descriptor = OxlintDescriptor(project, directory, tool, root, override)
        LspClientManager.getInstance(project).ensureClientStarted(OxlintProvider::class.java, descriptor)
    }

    fun restart() {
        if (disposed) return
        worker.execute {
            if (disposed || project.isDisposed) return@execute
            LspClientManager.getInstance(project).stopClients(OxlintProvider::class.java)
            val files = ReadAction.computeBlocking<Array<VirtualFile>, RuntimeException> { FileEditorManager.getInstance(project).openFiles }
            files.filter(LintFiles::supported).forEach { if (!disposed) start(it) }
        }
    }

    override fun dispose() {
        disposed = true
        worker.shutdownNow()
        // The platform owns and disposes the native LSP clients with the project.
    }
}

internal class OxlintDescriptor(
    project: Project,
    directory: VirtualFile,
    private val tool: Formatter,
    private val projectRoot: Path,
    private val executableOverride: String,
) : LspClientDescriptor(project, "OxBreeze oxlint", directory) {
    // Snapshot the override for this descriptor, and cache discovery per directory.
    // Restart replaces the descriptor and its cache when configuration changes.
    private val assignments = ConcurrentHashMap<Path, Boolean>()
    override fun isSupportedFile(file: VirtualFile): Boolean = LintFiles.supported(file) &&
        assignments.computeIfAbsent(Path.of(file.path).parent) { parent ->
            runCatching { Discovery.resolve(parent.resolve("oxbreeze.ts"), projectRoot, executableOverride, Tool.LINT) == tool }.getOrDefault(false)
        }

    override fun createCommandLine() = tool.commandLine()

    override fun createInitializationOptions(): Any = LintOptions.initialization(getFileUri(roots.single()))
    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any = LintOptions.options
    override val lspCustomization = object : LspCustomization() {
        override val formattingCustomizer = LspFormattingDisabled
    }
}
