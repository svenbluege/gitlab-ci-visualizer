package io.github.svenbluege.gitlabci

/** Loads a pipeline from indented YAML; [includes] maps local include paths to their content. */
internal fun pipeline(yaml: String, includes: Map<String, String> = emptyMap()): Pipeline =
    Pipeline.load(yaml.trimIndent()) { includes[it]?.trimIndent() }

internal fun Pipeline.run(
    source: String = "push", branch: String = "main", tag: String = "", variables: String = "", changesMatch: Boolean = true,
): PipelineResult = simulate(predefinedVariables(source, branch, tag, "main"), parseVariables(variables), changesMatch)

internal fun PipelineResult.job(name: String): JobResult = jobs.first { it.name == name }
internal fun PipelineResult.running(): List<String> = jobs.filter { it.included }.map { it.name }
