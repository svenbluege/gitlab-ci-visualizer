package io.github.svenbluege.gitlabci

import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Starts a headless IDE with the plugin: checks the registration in plugin.xml and that the editor can be built. */
class PipelineEditorTest : BasePlatformTestCase() {
    private fun providers(name: String) = FileEditorProviderManager.getInstance()
        .getProviderList(project, myFixture.addFileToProject(name, "job: { script: x }").virtualFile)

    fun `test pipeline files get the split editor instead of the plain one`() {
        val provider = providers(".gitlab-ci.yml").filterIsInstance<PipelineEditorProvider>().single()
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)

        val file = myFixture.findFileInTempDir(".gitlab-ci.yml")
        val editor = provider.createEditor(project, file)
        Disposer.register(testRootDisposable, editor)
        assertTrue(editor is TextEditorWithPreview)
        editor as TextEditorWithPreview
        assertEquals(file, editor.file)
        assertEquals("Pipeline", editor.previewEditor.name)
        assertTrue(editor.previewEditor.isValid)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue() // the split editor finishes its setup asynchronously
    }

    fun `test other yaml files keep their editor`() {
        assertTrue(providers("docker-compose.yml").none { it is PipelineEditorProvider })
    }
}
