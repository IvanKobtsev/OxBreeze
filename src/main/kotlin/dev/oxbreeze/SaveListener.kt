package dev.oxbreeze

import com.intellij.ide.actions.SaveAllAction
import com.intellij.ide.actions.SaveDocumentAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

class SaveListener : FileDocumentManagerListener, AnActionListener {
    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (!SaveAllAction.isSaveAllAction(action) && !SaveDocumentAction.isSaveDocumentAction(action)) return
        val project = event.project?.takeUnless { it.isDisposed } ?: return
        val document = (event.getData(CommonDataKeys.EDITOR)
            ?: FileEditorManager.getInstance(project).selectedTextEditor)?.document ?: return
        // The normal save callback only runs for dirty documents. Explicit Save must
        // also retry formatting an already saved file (e.g. after a stale result).
        if (!FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
            beforeDocumentSaving(document)
        }
    }

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
    private val sessions = SessionPool<Formatter, FormatterSession>({ it.isRunning }, ::FormatterSession)
    private val contexts = ConcurrentHashMap.newKeySet<Path>()
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
        val generation = sessions.generation
        jobs.remove(document)?.future?.cancel(false)
        val future = FutureTask<Unit>({
            try {
                if (disposed || sessions.generation != generation || jobs[document]?.sequence != id) return@FutureTask
                val settings = project.service<Settings>().state
                if (!settings.enabled) return@FutureTask
                val root = project.basePath?.let(Path::of) ?: return@FutureTask
                contexts.add(file)
                val formatter = Discovery.resolve(file, root, settings.executable)
                    ?: error("No local formatter found. Install oxfmt or vite-plus, or set an executable override.")
                val session = sessions.acquire(formatter, generation) ?: return@FutureTask
                if (disposed) { session.close(); return@FutureTask }
                val output = try { session.format(file, source) } catch (e: Exception) {
                    sessions.remove(formatter, session)
                    session.close()
                    throw e
                }
                if (sessions.generation != generation) return@FutureTask
                if (Thread.currentThread().isInterrupted || output == source) return@FutureTask
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || sessions.generation != generation || jobs[document]?.sequence != id || document.text != source || !project.service<Settings>().state.enabled) return@invokeLater
                    applying.add(document)
                    try {
                        WriteCommandAction.runWriteCommandAction(project, "Format with OxBreeze", null, Runnable { document.setText(output) })
                        FileDocumentManager.getInstance().saveDocument(document)
                    } finally { applying.remove(document) }
                }
            } catch (e: Exception) {
                if (sessions.generation == generation && !Thread.currentThread().isInterrupted && !project.isDisposed) report(e)
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
    /** May be called from the UI; process shutdown and initialization happen off the UI thread. */
    fun restart() {
        if (disposed) return
        val active = sessions.invalidate()
        val generation = sessions.generation
        jobs.values.forEach { it.future.cancel(false) }
        jobs.clear()
        reported.clear()
        ApplicationManager.getApplication().executeOnPooledThread {
            active.forEach { it.close() }
            if (disposed || worker.isShutdown) return@executeOnPooledThread
            runCatching {
                worker.execute {
                try {
                    if (disposed || sessions.generation != generation) return@execute
                    val settings = project.service<Settings>().state
                    val root = project.basePath?.let(Path::of) ?: return@execute
                    if (!settings.enabled) return@execute
                    contexts.mapNotNull { file ->
                        try {
                            Discovery.resolve(file, root, settings.executable)
                                ?: error("No local formatter found for $file. Install oxfmt or vite-plus, or set an executable override.")
                        } catch (e: Exception) {
                            if (!disposed && sessions.generation == generation) report(e)
                            null
                        }
                    }.distinct().forEach { formatter ->
                        try {
                            val session = sessions.acquire(formatter, generation) ?: return@execute
                            try { session.initialize() } catch (e: Exception) {
                                sessions.remove(formatter, session)
                                session.close()
                                throw e
                            }
                        } catch (e: Exception) {
                            if (!disposed && sessions.generation == generation) report(e)
                        }
                    }
                } catch (e: Exception) {
                    if (!disposed && sessions.generation == generation) report(e)
                }
                }
            }.onFailure { if (!disposed) log.warn("OxBreeze restart failed", it) }
        }
    }

    override fun dispose() {
        disposed = true
        val active = sessions.invalidate()
        worker.shutdownNow()
        jobs.values.forEach { it.future.cancel(false) }
        jobs.clear()
        Thread({ active.forEach { it.close() } }, "OxBreeze shutdown").apply { isDaemon = true; start() }
    }
}

