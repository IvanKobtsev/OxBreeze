package dev.oxbreeze

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
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

class SaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isInLocalFileSystem || !file.isWritable) return
        val project = ProjectManager.getInstance().openProjects.firstOrNull {
            !it.isDisposed && ProjectFileIndex.getInstance(it).isInContent(file)
        } ?: ProjectManager.getInstance().openProjects.filter { candidate ->
            if (candidate.isDisposed) false else candidate.basePath?.let { base ->
                val root = Path.of(base)
                val path = Path.of(file.path)
                path.startsWith(root) && !ProjectFileIndex.getInstance(candidate).isExcluded(file) &&
                    root.relativize(path).none { part -> part.toString() in setOf(".idea", ".git", "node_modules") }
            } == true
        }.maxByOrNull { it.basePath?.length ?: 0 } ?: return
        val settings = project.service<Settings>().state
        if (!settings.enabled || file.extension?.lowercase() !in settings.extensions) return
        project.service<SaveService>().schedule(document, Path.of(file.path))
    }
}

@Service(Service.Level.PROJECT)
class SaveService(private val project: Project) : com.intellij.openapi.Disposable {
    private data class Job(val sequence: Long, val source: String, val future: Future<*>)
    private val jobs = ConcurrentHashMap<Document, Job>()
    private val sequence = AtomicLong()
    private val sessions = ConcurrentHashMap<Formatter, FormatterSession>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "OxBreeze formatting").apply { isDaemon = true } }
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val applying = HashSet<Document>() // UI thread only
    private val log = Logger.getInstance(SaveService::class.java)
    @Volatile private var disposed = false

    fun schedule(document: Document, file: Path) {
        if (disposed || document in applying) return
        val source = document.text
        val previous = jobs[document]
        if (previous?.source == source && !previous.future.isDone) return
        val id = sequence.incrementAndGet()
        jobs.remove(document)?.future?.cancel(false)
        val future = FutureTask<Unit>({
            try {
                if (disposed || jobs[document]?.sequence != id) return@FutureTask
                val settings = project.service<Settings>().state
                val root = project.basePath?.let(Path::of) ?: return@FutureTask
                val formatter = Discovery.resolve(file, root, settings.executable)
                    ?: error("No local formatter found. Install oxfmt or vite-plus, or set an executable override.")
                val session = sessions.compute(formatter) { key, existing ->
                    if (existing?.isRunning == true) existing else {
                        existing?.close()
                        FormatterSession(key)
                    }
                }!!
                if (disposed) { session.close(); return@FutureTask }
                val output = try { session.format(file, source) } catch (e: Exception) {
                    sessions.remove(formatter, session)
                    session.close()
                    throw e
                }
                if (Thread.currentThread().isInterrupted || output == source) return@FutureTask
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || jobs[document]?.sequence != id || document.text != source || !project.service<Settings>().state.enabled) return@invokeLater
                    applying.add(document)
                    try {
                        WriteCommandAction.runWriteCommandAction(project, "Format with OxBreeze", null, Runnable { document.setText(output) })
                        FileDocumentManager.getInstance().saveDocument(document)
                    } finally { applying.remove(document) }
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted && !project.isDisposed) report(e)
            }
        }, Unit)
        jobs[document] = Job(id, source, future)
        worker.execute(future)
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
    override fun dispose() {
        disposed = true
        worker.shutdownNow()
        jobs.values.forEach { it.future.cancel(false) }
        jobs.clear()
        val active = sessions.values.toList()
        sessions.clear()
        Thread({ active.forEach { it.close() } }, "OxBreeze shutdown").apply { isDaemon = true; start() }
    }
}

