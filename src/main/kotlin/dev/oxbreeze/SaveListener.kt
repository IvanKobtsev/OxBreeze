package dev.oxbreeze

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

class SaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isInLocalFileSystem || !file.isWritable) return
        val project = ProjectManager.getInstance().openProjects.firstOrNull {
            !it.isDisposed && ProjectFileIndex.getInstance(it).isInContent(file)
        } ?: return
        val settings = project.service<Settings>().state
        if (!settings.enabled || file.extension?.lowercase() !in settings.extensions) return
        project.service<SaveService>().schedule(document, Path.of(file.path))
    }
}

@Service(Service.Level.PROJECT)
class SaveService(private val project: Project) : com.intellij.openapi.Disposable {
    private data class Job(val sequence: Long, val future: Future<*>)
    private val jobs = ConcurrentHashMap<Document, Job>()
    private val sequence = AtomicLong()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val applying = HashSet<Document>() // UI thread only
    private val log = Logger.getInstance(SaveService::class.java)

    fun schedule(document: Document, file: Path) {
        if (document in applying) return
        val source = document.text
        val stamp = document.modificationStamp
        val id = sequence.incrementAndGet()
        jobs.remove(document)?.future?.cancel(true)
        val future = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            try {
                val settings = project.service<Settings>().state
                val root = project.basePath?.let(Path::of) ?: return@schedule
                val formatter = Discovery.resolve(file, root, settings.executable)
                    ?: error("No local formatter found. Install oxfmt or vite-plus, or set an executable override.")
                val windowsShim = formatter.executable.toString().endsWith(".cmd", true) || formatter.executable.toString().endsWith(".bat", true)
                val command = (if (windowsShim) GeneralCommandLine("cmd.exe").withParameters("/d", "/c", formatter.executable.toString())
                    else GeneralCommandLine(formatter.executable.toString()))
                    .withParameters(formatter.arguments(file))
                    .withWorkDirectory(formatter.directory.toFile())
                    .withCharset(StandardCharsets.UTF_8)
                val handler = CapturingProcessHandler(command)
                try {
                    // Drain output before writing stdin, avoiding pipe deadlocks on large files.
                    handler.startNotify()
                    val input = AppExecutorUtil.getAppExecutorService().submit {
                        handler.process.outputStream.use { it.write(source.toByteArray(StandardCharsets.UTF_8)) }
                    }
                    val output = handler.runProcess(10_000)
                    input.cancel(true)
                    if (Thread.currentThread().isInterrupted) return@schedule
                    check(!output.isTimeout) { "Formatter timed out after 10 seconds." }
                    check(output.exitCode == 0) { output.stderr.take(1500).ifBlank { "Formatter exited with code ${output.exitCode}." } }
                    if (output.stdout == source) return@schedule
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed || jobs[document]?.sequence != id || document.modificationStamp != stamp || !project.service<Settings>().state.enabled) return@invokeLater
                        applying.add(document)
                        try {
                            WriteCommandAction.runWriteCommandAction(project, "Format with OxBreeze", null, Runnable { document.setText(output.stdout) })
                            FileDocumentManager.getInstance().saveDocument(document)
                        } finally { applying.remove(document) }
                    }
                } finally { if (!handler.isProcessTerminated) handler.destroyProcess() }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted && !project.isDisposed) report(e)
            }
        }, 100, java.util.concurrent.TimeUnit.MILLISECONDS)
        jobs[document] = Job(id, future)
    }

    private fun report(error: Exception) {
        log.warn("OxBreeze formatting failed", error)
        val message = error.message ?: "Formatter failed"
        if (!reported.add(message)) return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("OxBreeze")
                .createNotification("OxBreeze", message, NotificationType.WARNING)
                .addAction(NotificationAction.createSimple("Settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, "OxBreeze") })
                .notify(project)
        }
    }
    override fun dispose() { jobs.values.forEach { it.future.cancel(true) }; jobs.clear() }
}
