package dev.oxbreeze

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.intellij.util.messages.Topic
import java.awt.event.MouseEvent

@Service(Service.Level.PROJECT)
class Services(private val project: Project) {
    fun restartFormatter() = project.service<SaveService>().restart()
    fun restartLint() = project.service<LintService>().restart()
    fun restartBoth() {
        restartFormatter()
        restartLint()
    }

    companion object {
        val CHANGED = Topic.create("OxBreeze service status", Runnable::class.java)
        fun changed(project: Project) {
            if (project.isDisposed) return
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) project.messageBus.syncPublisher(CHANGED).run()
            }
        }
    }
}

class ServiceWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = "OxBreeze"
    override fun getDisplayName() = "OxBreeze services"
    override fun isAvailable(project: Project) = !project.isDefault
    override fun createWidget(project: Project): StatusBarWidget = ServiceWidget(project)
}

class ServiceWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.IconPresentation {
    companion object {
        val icon = IconLoader.getIcon("/icons/oxbreeze.svg", ServiceWidget::class.java)
    }

    override fun ID() = "OxBreeze"
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getIcon() = icon
    override fun getTooltipText() = "OxBreeze — Formatter: ${project.service<SaveService>().status()}; Oxlint: ${project.service<LintService>().status()}"

    override fun install(statusBar: StatusBar) {
        project.messageBus.connect(this).subscribe(Services.CHANGED, Runnable { statusBar.updateWidget(ID()) })
    }

    override fun getClickConsumer() = Consumer<MouseEvent> { event ->
        if (project.isDisposed) return@Consumer
        val services = project.service<Services>()
        val group = DefaultActionGroup()
        fun row(text: String) {
            group.add(object : DumbAwareAction(text) {
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
                override fun actionPerformed(e: AnActionEvent) {}
            })
        }
        fun action(text: String, perform: () -> Unit) {
            group.add(object : DumbAwareAction(text) {
                override fun actionPerformed(e: AnActionEvent) { if (!project.isDisposed) perform() }
            })
        }
        row("Formatter: ${project.service<SaveService>().status()}")
        row("Oxlint: ${project.service<LintService>().status()}")
        group.addSeparator()
        action("Restart both services", services::restartBoth)
        action("Restart formatter", services::restartFormatter)
        action("Restart oxlint", services::restartLint)
        group.addSeparator()
        action("Settings") { ShowSettingsUtil.getInstance().showSettingsDialog(project, "OxBreeze") }
        JBPopupFactory.getInstance().createActionGroupPopup(
            "OxBreeze", group, com.intellij.ide.DataManager.getInstance().getDataContext(event.component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
        ).show(RelativePoint(event))
    }
}
