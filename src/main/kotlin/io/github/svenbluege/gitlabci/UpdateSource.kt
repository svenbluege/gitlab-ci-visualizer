package io.github.svenbluege.gitlabci

import com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider

/** The description of the latest release, written by .github/workflows/release.yml. */
const val UPDATE_URL = "https://github.com/svenbluege/gitlab-ci-visualizer/releases/latest/download/updatePlugins.xml"

/** Adds the releases to the places the IDE asks in its regular plugin update check. */
class UpdateSource : UpdateSettingsProvider {
    override fun getPluginRepositories(): List<String> = listOf(UPDATE_URL)
}
