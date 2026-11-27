package io.github.svenbluege.gitlabci

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import java.util.concurrent.Callable
import javax.swing.JComponent
import javax.swing.JPanel

/** Opens `.gitlab-ci.yml` files as a text editor with the pipeline graph next to it. */
class PipelineEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean =
        file.name.endsWith(".gitlab-ci.yml") || file.name.endsWith(".gitlab-ci.yaml")

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = TextEditorWithPreview(
        TextEditorProvider.getInstance().createEditor(project, file) as TextEditor,
        PipelinePreview(file),
        "GitLab CI Pipeline",
        TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
    )

    override fun getEditorTypeId(): String = "gitlab-ci-pipeline"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class PipelinePreview(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val source = ComboBox(arrayOf("push", "merge_request_event", "schedule", "web", "api", "trigger", "pipeline", "parent_pipeline"))
    private val branch = JBTextField("main", 12)
    private val tag = JBTextField(8)
    private val defaultBranch = JBTextField("main", 8)
    private val variables = JBTextField(26).apply { emptyText.text = "KEY=value OTHER=\"with spaces\"" }
    private val changesMatch = JBCheckBox("changes/exists match", true)
    private val showSkipped = JBCheckBox("Show skipped jobs", true)

    private val browser = if (JBCefApp.isSupported()) JBCefBrowser() else null
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val panel = JPanel(BorderLayout())
    private var shownHtml = ""
    private val updateKey = Any() // the platform wants an object used for nothing else to drop superseded updates

    init {
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT, 6, 4))
        fun add(label: String, component: JComponent) {
            toolbar.add(JBLabel(label))
            toolbar.add(component)
        }
        add("Source:", source)
        add("Branch:", branch)
        add("Tag:", tag)
        add("Default branch:", defaultBranch)
        add("Variables:", variables)
        toolbar.add(changesMatch)
        toolbar.add(showSkipped)
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(browser?.component ?: JBLabel("The pipeline view needs JCEF, which this IDE runtime does not provide."), BorderLayout.CENTER)
        browser?.let { Disposer.register(this, it) }

        for (field in listOf(branch, tag, defaultBranch, variables)) {
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: javax.swing.event.DocumentEvent) = scheduleUpdate()
            })
        }
        source.addActionListener { scheduleUpdate() }
        for (box in listOf(changesMatch, showSkipped)) box.addActionListener { scheduleUpdate() }
        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleUpdate()
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { scheduleUpdate() })
        scheduleUpdate()
    }

    private fun scheduleUpdate() {
        alarm.cancelAllRequests()
        alarm.addRequest(::update, 300)
    }

    /** Reads the form on the UI thread, then parses and reads included files in the background, where that is allowed. */
    private fun update() {
        val browser = browser ?: return
        val dark = !JBColor.isBright()
        val predefined = predefinedVariables(source.item, branch.text.trim(), tag.text.trim(), defaultBranch.text.trim())
        val overrides = parseVariables(variables.text)
        val changes = changesMatch.isSelected
        val skipped = showSkipped.isSelected
        ReadAction.nonBlocking(Callable {
            try {
                val documents = FileDocumentManager.getInstance()
                // GitLab resolves local includes from the repository root.
                val root = generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild(".git") != null } ?: file.parent
                val pipeline = Pipeline.load(documents.getDocument(file)?.text.orEmpty()) { path ->
                    root?.findFileByRelativePath(path)?.let { documents.getDocument(it)?.text }
                }
                Renderer.render(pipeline.simulate(predefined, overrides, changes), dark, skipped)
            } catch (e: Exception) { // invalid YAML while typing is the normal case here
                if (e is ControlFlowException) throw e
                Renderer.error(e.message ?: e.toString(), dark)
            }
        })
            .coalesceBy(updateKey)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { html ->
                if (html != shownHtml) {
                    shownHtml = html
                    browser.loadHTML(html)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = source
    override fun getName(): String = "Pipeline"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun selectNotify() = scheduleUpdate() // an included file may have changed in another tab
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}
