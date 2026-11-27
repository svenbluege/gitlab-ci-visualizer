package io.github.svenbluege.gitlabci

import com.intellij.ide.ui.LafManagerListener
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
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
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.net.HttpConfigurable
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import java.util.concurrent.Callable
import javax.swing.JComponent
import javax.swing.JPanel

/** Opens `.gitlab-ci.yml` files as a text editor with the pipeline graph next to it. */
class PipelineEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = isPipelineFile(file.name)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = TextEditorWithPreview(
        TextEditorProvider.getInstance().createEditor(project, file) as TextEditor,
        PipelinePreview(project, file),
        "GitLab CI Pipeline",
        TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
    )

    override fun getEditorTypeId(): String = "gitlab-ci-pipeline"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class PipelinePreview(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private class Rendered(val graph: String?, val error: String, val targets: Map<String, Pair<VirtualFile, Int>>)

    // The simulation is remembered per project, so it is still there when the file is opened again.
    private val settings = PropertiesComponent.getInstance(project)
    private val source = ComboBox(arrayOf("push", "merge_request_event", "schedule", "web", "api", "trigger", "pipeline", "parent_pipeline"))
        .apply { item = settings.getValue("$KEY.source", "push") }
    private val branch = JBTextField(settings.getValue("$KEY.branch", "main"), 12)
    private val tag = JBTextField(settings.getValue("$KEY.tag", ""), 8).apply { emptyText.text = "none" }
    private val defaultBranch = JBTextField(settings.getValue("$KEY.defaultBranch", "main"), 8)
    private val variables = JBTextField(settings.getValue("$KEY.variables", ""), 26)
        .apply { emptyText.text = "KEY=value OTHER=\"with spaces\"" }
    private val grouping = ComboBox(arrayOf("Stage", "Job dependencies"))
        .apply { item = settings.getValue("$KEY.grouping", "Stage") }
    private val changesMatch = JBCheckBox("changes/exists rules match", settings.getBoolean("$KEY.changesMatch", true))
    private val showSkipped = JBCheckBox("Show skipped jobs", settings.getBoolean("$KEY.showSkipped", true))

    private val browser = run {
        // The browser reads the proxy settings in a class initializer. If this editor is the first to use the browser,
        // as when the IDE reopens the file at startup, loading them there is logged as an error of this plugin.
        // HttpConfigurable is deprecated: should it be removed, the browser no longer reads it either.
        runCatching { HttpConfigurable.getInstance() }
        if (JBCefApp.isSupported()) JBCefBrowser() else null
    }
    private val openJob = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val updateKey = Any() // the platform wants an object used for nothing else to drop superseded updates
    private val panel = JPanel(BorderLayout())
    private var shownHtml = ""
    private var lastGraph: String? = null
    private var targets: Map<String, Pair<VirtualFile, Int>> = emptyMap()

    init {
        val toolbar = JPanel(WrapLayout(FlowLayout.LEFT, 10, 4))
        toolbar.border = IdeBorderFactory.createBorder(SideBorder.BOTTOM)
        // Label and field share a panel so that wrapping never separates them; labelFor names the field for screen readers.
        fun add(label: String, field: JComponent, tooltip: String) {
            field.toolTipText = tooltip
            toolbar.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JBLabel(label).apply { labelFor = field })
                add(field)
            })
        }
        add("Source:", source, "CI_PIPELINE_SOURCE: what starts the pipeline")
        add("Branch:", branch, "CI_COMMIT_BRANCH. For a merge request: its source branch")
        add("Tag:", tag, "CI_COMMIT_TAG. A tag pipeline has no branch")
        add("Default branch:", defaultBranch, "CI_DEFAULT_BRANCH, also used as the target branch of a merge request")
        add("Variables:", variables, "Extra variables, as given when running a pipeline by hand. They override variables from the file")
        add("Group by:", grouping, "As GitLab's \"Group jobs by\": one column per stage, or columns by what each job waits for")
        changesMatch.toolTipText = "changes and exists cannot be checked here. Selected: rules that use them count as matching"
        showSkipped.toolTipText = "Also show the jobs that are not part of the simulated pipeline"
        toolbar.add(changesMatch)
        toolbar.add(showSkipped)
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(browser?.component ?: JBLabel("The pipeline view needs JCEF, which this IDE runtime does not provide."), BorderLayout.CENTER)
        browser?.let {
            it.component.accessibleContext.accessibleName = "Pipeline graph"
            Disposer.register(this, it)
        }
        openJob?.let { query ->
            Disposer.register(this, query)
            query.addHandler { job ->
                ApplicationManager.getApplication().invokeLater { open(job) }
                null
            }
        }

        for (field in listOf(branch, tag, defaultBranch, variables)) {
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: javax.swing.event.DocumentEvent) = scheduleUpdate()
            })
        }
        source.addActionListener { scheduleUpdate() }
        grouping.addActionListener { scheduleUpdate() }
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
        settings.setValue("$KEY.source", source.item)
        settings.setValue("$KEY.branch", branch.text)
        settings.setValue("$KEY.tag", tag.text)
        settings.setValue("$KEY.defaultBranch", defaultBranch.text)
        settings.setValue("$KEY.variables", variables.text)
        settings.setValue("$KEY.grouping", grouping.item)
        settings.setValue("$KEY.changesMatch", changesMatch.isSelected, true)
        settings.setValue("$KEY.showSkipped", showSkipped.isSelected, true)
        val predefined = predefinedVariables(source.item, branch.text.trim(), tag.text.trim(), defaultBranch.text.trim())
        val overrides = parseVariables(variables.text)
        val changes = changesMatch.isSelected
        val skipped = showSkipped.isSelected
        val byDependencies = grouping.selectedIndex == 1
        ReadAction.nonBlocking(Callable {
            try {
                val documents = FileDocumentManager.getInstance()
                // GitLab resolves local includes from the repository root.
                val root = generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild(".git") != null } ?: file.parent
                val text = documents.getDocument(file)?.text.orEmpty()
                val texts = HashMap<String?, Pair<VirtualFile, String>>() // by include path, null is this file
                texts[null] = file to text
                val pipeline = Pipeline.load(text) { path ->
                    root?.findFileByRelativePath(path)?.let { included ->
                        documents.getDocument(included)?.text?.also { texts[path] = included to it }
                    }
                }
                val result = pipeline.simulate(predefined, overrides, changes)
                val targets = result.jobs.mapNotNull { job ->
                    texts[job.source]?.let { (target, content) -> job.name to (target to (definitionOffset(content, job.name) ?: 0)) }
                }.toMap()
                Rendered(Renderer.graph(result, skipped, byDependencies), "", targets)
            } catch (e: Exception) { // invalid YAML while typing is the normal case here
                if (e is ControlFlowException) throw e
                Rendered(null, e.message ?: e.toString(), emptyMap())
            }
        })
            .coalesceBy(updateKey)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { rendered ->
                if (rendered.graph != null) {
                    lastGraph = rendered.graph
                    targets = rendered.targets
                }
                // While the YAML is broken, the last graph stays visible below the message.
                val body = rendered.graph ?: Renderer.error(rendered.error, lastGraph)
                val font = UIUtil.getLabelFont()
                val style = Renderer.Style(
                    !JBColor.isBright(), font.size, font.family, EditorColorsManager.getInstance().globalScheme.editorFontName,
                )
                val bridge = openJob?.let { "window.openJob = function(name) { ${it.inject("name")} };" }.orEmpty()
                val html = Renderer.page(body, style, bridge)
                if (html != shownHtml) {
                    shownHtml = html
                    browser.loadHTML(html)
                }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Moves the caret to the job's definition, in this file or in the include that defines it. */
    private fun open(job: String) {
        val (target, offset) = targets[job] ?: return
        if (!target.isValid) return
        val split = TextEditorWithPreview.getParentSplitEditor(this)
        if (target == file && split?.getLayout() == TextEditorWithPreview.Layout.SHOW_PREVIEW) {
            split.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW) // the text is hidden in preview-only mode
        }
        OpenFileDescriptor(project, target, offset).navigate(true)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = browser?.component ?: panel
    override fun getName(): String = "Pipeline"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun selectNotify() = scheduleUpdate() // an included file may have changed in another tab
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}

    private companion object {
        const val KEY = "gitlab-ci-visualizer"
    }
}
