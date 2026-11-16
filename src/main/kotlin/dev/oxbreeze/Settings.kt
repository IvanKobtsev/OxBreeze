package dev.oxbreeze

import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

object Formats {
    val extensions = "js jsx mjs cjs ts tsx mts cts json jsonc json5 css scss less pcss postcss graphql gql graphqls toml yml yaml md markdown html htm xhtml vue svelte astro mdx hbs handlebars mjml".split(' ')
    fun defaults() = extensions.filter { it != "hbs" }.toMutableList()
}

@Service(Service.Level.PROJECT)
@State(name = "OxBreeze", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class Settings : PersistentStateComponent<Settings.Data> {
    class Data {
        var enabled = true
        var executable = ""
        var extensions = Formats.defaults()
    }
    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) { data = state }
}

class SettingsPage(private val project: Project) : Configurable {
    private val enabled = JCheckBox("Format on save")
    private val executable = JTextField()
    private val checks = Formats.extensions.associateWith { JCheckBox(".$it") }
    private val settings get() = project.service<Settings>().state
    override fun getDisplayName() = "OxBreeze"
    override fun createComponent(): JComponent = JPanel(BorderLayout(8, 8)).apply {
        add(JPanel(GridLayout(0, 1)).apply {
            add(enabled)
            add(JLabel("Executable override (blank: discover local vp or oxfmt)"))
            add(executable)
            add(JLabel("Enabled file extensions"))
        }, BorderLayout.NORTH)
        add(JScrollPane(JPanel(GridLayout(0, 4)).apply { checks.values.forEach { add(it) } }))
        add(JButton("Reset defaults").apply { addActionListener {
            enabled.isSelected = true
            executable.text = ""
            checks.forEach { (ext, box) -> box.isSelected = ext != "hbs" }
        } }, BorderLayout.SOUTH)
        reset()
    }
    override fun isModified() = enabled.isSelected != settings.enabled || executable.text.trim() != settings.executable || selected() != settings.extensions
    private fun selected() = checks.filterValues { it.isSelected }.keys.toMutableList()
    override fun apply() {
        settings.enabled = enabled.isSelected
        settings.executable = executable.text.trim()
        settings.extensions = selected()
    }
    override fun reset() {
        enabled.isSelected = settings.enabled
        executable.text = settings.executable
        checks.forEach { (ext, box) -> box.isSelected = ext in settings.extensions }
    }
}
