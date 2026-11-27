package io.github.svenbluege.gitlabci

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

typealias Config = Map<String, Any?>

data class Need(val job: String, val optional: Boolean)

class JobResult(
    val name: String,
    val stage: String,
    /** Whether the job is part of the simulated pipeline. */
    val included: Boolean,
    val whenValue: String,
    /** Why the job is or is not included. */
    val reason: String,
    /** null: no `needs`, the job waits for all earlier stages. */
    val needs: List<Need>?,
    val dependencies: List<String>,
    val problems: MutableList<String>,
    val config: Config,
    /** The local include that defines the job, null for the file itself. */
    val source: String? = null,
)

class PipelineResult(
    val stages: List<String>,
    val jobs: List<JobResult>,
    /** Set when `workflow:rules` prevent the pipeline. */
    val blocked: String?,
    val notes: List<String>,
    /** The variables the rules were evaluated with, without job-level ones. */
    val variables: Map<String, String> = emptyMap(),
)

/** A parsed `.gitlab-ci.yml` with includes, `!reference` tags and `extends` resolved. */
class Pipeline private constructor(
    private val config: Config,
    private val notes: MutableList<String>,
    private val sources: Map<String, String>,
) {

    private val jobs: Map<String, Config> = config
        .filter { (name, value) -> name !in RESERVED && !name.startsWith(".") && value is Map<*, *> }
        .mapValues { (name, _) -> expand(name, emptySet()) }

    private val declaredStages: List<String> = listOf(".pre") +
        ((config["stages"] as? List<*>)?.map { it.toString() } ?: listOf("build", "test", "deploy")).minus(setOf(".pre", ".post")) +
        ".post"

    /** Merges the jobs named by `extends` (later ones win) under the job's own keys. */
    @Suppress("UNCHECKED_CAST")
    private fun expand(name: String, seen: Set<String>): Config {
        val job = config[name] as? Config
        if (job == null || name in seen) {
            notes += if (job == null) "extends: unknown job '$name'" else "extends: circular reference to '$name'"
            return emptyMap()
        }
        val parents = flatten(job["extends"]).fold(emptyMap<String, Any?>()) { merged, parent ->
            deepMerge(merged, expand(parent.toString(), seen + name))
        }
        return deepMerge(parents, job)
    }

    /**
     * Decides which jobs a pipeline with these variables contains. [overrides] win over variables from the file,
     * like variables given when running a pipeline manually. `changes` and `exists` cannot be checked, so
     * [changesMatch] says whether rules using them count as matching.
     */
    fun simulate(
        predefined: Map<String, String>,
        overrides: Map<String, String> = emptyMap(),
        changesMatch: Boolean = true,
    ): PipelineResult {
        val notes = this.notes.toMutableList()
        var variables = predefined + variablesOf(config["variables"])
        var blocked: String? = null
        val workflowRules = (config["workflow"] as? Map<*, *>)?.get("rules")
        if (workflowRules != null) {
            val match = firstMatch(workflowRules, variables + overrides, changesMatch, notes)
            if (match == null || match.value["when"] == "never") blocked = "workflow: ${describe(match)}"
            else variables = variables + variablesOf(match.value["variables"])
        }

        val results = jobs.map { (name, job) -> evaluate(name, job, variables, overrides, changesMatch, blocked != null) }
        val stages = declaredStages + results.map { it.stage }.distinct().filter { it !in declaredStages }
        val byName = results.associateBy { it.name }
        for (job in results.filter { it.included }) {
            for (need in job.needs.orEmpty()) {
                val target = byName[need.job]
                when {
                    target == null -> if (!need.optional) job.problems += "needs unknown job '${need.job}'"
                    !target.included -> if (!need.optional) job.problems += "needs '${need.job}', which is not in this pipeline"
                    stages.indexOf(target.stage) > stages.indexOf(job.stage) -> job.problems += "needs '${need.job}' from a later stage"
                }
            }
        }
        return PipelineResult(stages, results, blocked, notes, variables + overrides)
    }

    private fun evaluate(
        name: String, job: Config, base: Map<String, String>, overrides: Map<String, String>,
        changesMatch: Boolean, blocked: Boolean,
    ): JobResult {
        val problems = mutableListOf<String>()
        val variables = base + variablesOf(job["variables"]) + overrides
        var whenValue = job["when"]?.toString() ?: "on_success"
        var needs = job["needs"]
        val runs: Boolean
        val reason: String
        if (job["rules"] != null) {
            val match = firstMatch(job["rules"], variables, changesMatch, problems)
            match?.value?.get("when")?.let { whenValue = it.toString() }
            match?.value?.get("needs")?.let { needs = it }
            runs = match != null && whenValue != "never"
            reason = describe(match)
        } else {
            // Without rules a job behaves like `only: [branches, tags]`, so it is not in merge request pipelines.
            val only = policy(job["only"] ?: listOf("branches", "tags"), variables, changesMatch, problems)
            val except = job["except"]?.let { policy(it, variables, changesMatch, problems) }.orEmpty()
            runs = only.all { it } && except.none { it }
            reason = when {
                except.any { it } -> "except matches"
                !only.all { it } -> if (job["only"] == null) "no rules: only in branch and tag pipelines" else "only does not match"
                job["only"] != null -> "only matches"
                job["except"] != null -> "except does not match"
                else -> "no rules"
            }
        }
        val stage = job["stage"]?.toString() ?: "test"
        if (stage !in declaredStages) problems += "stage '$stage' is not defined in stages"
        return JobResult(
            name, stage, runs && !blocked, whenValue,
            if (blocked) "no pipeline is created (workflow rules)" else reason,
            needs?.let(::needsOf), flatten(job["dependencies"]).map { it.toString() }, problems, job, sources[name],
        )
    }

    companion object {
        private val RESERVED = setOf(
            "stages", "variables", "default", "workflow", "include", "spec",
            "image", "services", "cache", "before_script", "after_script",
        )
        private val SOURCE_KEYWORDS = mapOf(
            "merge_requests" to "merge_request_event", "schedules" to "schedule", "pushes" to "push",
            "triggers" to "trigger", "pipelines" to "pipeline", "external_pull_requests" to "external_pull_request_event",
            "web" to "web", "api" to "api", "external" to "external", "chat" to "chat",
        )

        /** [readInclude] returns the text of a local include (path relative to the repository root), or null. */
        fun load(text: String, readInclude: (String) -> String? = { null }): Pipeline {
            val notes = mutableListOf<String>()
            val sources = mutableMapOf<String, String>()
            val merged = withIncludes(text, null, readInclude, notes, sources, 0)
            // Same order as GitLab: includes, then !reference, then extends.
            @Suppress("UNCHECKED_CAST")
            return Pipeline(resolveReferences(merged, merged, 0) as Config, notes, sources)
        }

        // shortcut: only `include: local` without wildcards is read, upgrade when project/remote/template includes matter.
        private fun withIncludes(
            text: String, file: String?, readInclude: (String) -> String?,
            notes: MutableList<String>, sources: MutableMap<String, String>, depth: Int,
        ): Config {
            val config = parseYaml(text)
            var merged: Config = emptyMap()
            for (include in flatten(config["include"])) {
                val path = (include as? String ?: (include as? Map<*, *>)?.get("local") as? String)?.removePrefix("/")
                val included = path?.takeIf { depth < 10 }?.let(readInclude)
                if (included == null) notes += "Include not resolved, its jobs are missing: $include"
                else merged = deepMerge(merged, withIncludes(included, path, readInclude, notes, sources, depth + 1))
            }
            // The including file wins the merge, so it is also where a redefined job is found.
            for (key in config.keys) if (file != null) sources[key] = file else sources.remove(key)
            return deepMerge(merged, config - "include")
        }

        private class Reference(val path: List<String>)

        @Suppress("UNCHECKED_CAST")
        private fun parseYaml(text: String): Config {
            val constructor = object : SafeConstructor(LoaderOptions()) {
                init {
                    yamlConstructors[Tag("!reference")] = object : AbstractConstruct() {
                        override fun construct(node: Node): Any =
                            Reference(constructSequence(node as SequenceNode).map { it.toString() })
                    }
                }
            }
            // A file with a `spec:` header has two documents, the configuration is the last one.
            val document = Yaml(constructor).loadAll(text).lastOrNull()
            require(document == null || document is Map<*, *>) { "The file must contain a YAML mapping of jobs" }
            return normalize(document ?: emptyMap<String, Any?>()) as Config
        }

        /** Turns every map key into a String, so the unchecked casts to [Config] are safe. */
        private fun normalize(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { it.key.toString() to normalize(it.value) }
            is List<*> -> value.map(::normalize)
            else -> value
        }

        /** Replaces `!reference [job, key]` by its target. Inside a list, a referenced list is spliced in. */
        private fun resolveReferences(value: Any?, root: Config, depth: Int): Any? = when (value) {
            is Reference -> {
                require(depth < 10) { "!reference [${value.path.joinToString()}] is circular or nested too deeply" }
                resolveReferences(value.path.fold(root as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }, root, depth + 1)
            }
            is Map<*, *> -> value.mapValues { resolveReferences(it.value, root, depth) }
            is List<*> -> value.flatMap { item ->
                val resolved = resolveReferences(item, root, depth)
                if (item is Reference && resolved is List<*>) resolved else listOf(resolved)
            }
            else -> value
        }

        /** Maps are merged key by key, everything else (lists too) is replaced, as GitLab does for extends and include. */
        @Suppress("UNCHECKED_CAST")
        private fun deepMerge(base: Config, override: Config): Config = base + override.mapValues { (key, value) ->
            val old = base[key]
            if (old is Map<*, *> && value is Map<*, *>) deepMerge(old as Config, value as Config) else value
        }

        private fun flatten(value: Any?): List<Any?> = when (value) {
            null -> emptyList()
            is List<*> -> value.flatMap(::flatten)
            else -> listOf(value)
        }

        private fun variablesOf(value: Any?): Map<String, String> = (value as? Map<*, *>).orEmpty().entries
            .associate { (name, v) -> name.toString() to ((v as? Map<*, *>)?.get("value") ?: v)?.toString().orEmpty() }

        /** `needs` on other pipelines or projects are not jobs of this pipeline and are left out. */
        private fun needsOf(value: Any?): List<Need> = flatten(value).mapNotNull { need ->
            when {
                need !is Map<*, *> -> Need(need.toString(), false)
                need["project"] == null && need["pipeline"] == null -> (need["job"] as? String)?.let { Need(it, need["optional"] == true) }
                else -> null
            }
        }

        private fun test(expression: String, variables: Map<String, String>, problems: MutableList<String>): Boolean = try {
            Expression.evaluate(expression, variables)
        } catch (e: IllegalArgumentException) {
            problems += "cannot evaluate '$expression': ${e.message}"
            false
        }

        private fun firstMatch(
            rules: Any?, variables: Map<String, String>, changesMatch: Boolean, problems: MutableList<String>,
        ): IndexedValue<Map<*, *>>? = flatten(rules).filterIsInstance<Map<*, *>>().withIndex().firstOrNull { (_, rule) ->
            (rule["if"]?.let { test(it.toString(), variables, problems) } ?: true) &&
                ((rule["changes"] == null && rule["exists"] == null) || changesMatch)
        }

        private fun describe(match: IndexedValue<Map<*, *>>?): String {
            val rule = match?.value ?: return "no rule matches"
            val conditions = listOf("if", "changes", "exists").filter { rule[it] != null }.joinToString(", ") { "$it: ${rule[it]}" }
            return "rule #${match.index + 1}" + (rule["when"]?.let { " (when: $it)" } ?: "") + ": " + conditions.ifEmpty { "no condition" }
        }

        /** One Boolean per clause of an `only`/`except` policy: `only` needs all of them, `except` any. */
        private fun policy(policy: Any?, variables: Map<String, String>, changesMatch: Boolean, problems: MutableList<String>): List<Boolean> {
            val clauses = policy as? Map<*, *> ?: mapOf("refs" to policy)
            return listOfNotNull(
                clauses["refs"]?.let { refs -> flatten(refs).any { refMatches(it.toString(), variables, problems) } },
                clauses["variables"]?.let { expressions -> flatten(expressions).any { test(it.toString(), variables, problems) } },
                clauses["changes"]?.let { changesMatch },
            )
        }

        private fun refMatches(ref: String, variables: Map<String, String>, problems: MutableList<String>): Boolean = when {
            ref == "branches" -> "CI_COMMIT_BRANCH" in variables
            ref == "tags" -> "CI_COMMIT_TAG" in variables
            ref in SOURCE_KEYWORDS -> variables["CI_PIPELINE_SOURCE"] == SOURCE_KEYWORDS[ref]
            ref.startsWith("/") -> test("\$CI_COMMIT_REF_NAME =~ $ref", variables, problems)
            else -> variables["CI_COMMIT_REF_NAME"] == ref.substringBefore('@')
        }
    }
}

/** The predefined CI/CD variables GitLab would set for such a pipeline. A tag wins over the branch. */
fun predefinedVariables(source: String, branch: String, tag: String, defaultBranch: String): Map<String, String> = buildMap {
    val ref = tag.ifBlank { branch }
    put("CI", "true")
    put("GITLAB_CI", "true")
    put("CI_PIPELINE_SOURCE", source)
    put("CI_DEFAULT_BRANCH", defaultBranch)
    put("CI_COMMIT_REF_NAME", ref)
    put("CI_COMMIT_REF_SLUG", ref.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(63))
    when {
        tag.isNotBlank() -> put("CI_COMMIT_TAG", tag)
        source == "merge_request_event" -> {
            put("CI_MERGE_REQUEST_IID", "1")
            put("CI_MERGE_REQUEST_EVENT_TYPE", "detached")
            put("CI_MERGE_REQUEST_SOURCE_BRANCH_NAME", branch)
            put("CI_MERGE_REQUEST_TARGET_BRANCH_NAME", defaultBranch)
        }
        else -> put("CI_COMMIT_BRANCH", branch)
    }
}

/** Parses `KEY=value OTHER="with spaces"`. */
fun parseVariables(text: String): Map<String, String> =
    Regex("""(\w+)=("[^"]*"|\S*)""").findAll(text).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

/** Number of jobs a `parallel` keyword produces. */
fun parallelCount(parallel: Any?): Int = when (parallel) {
    is Int -> parallel
    is Map<*, *> -> (parallel["matrix"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().sumOf { entry ->
        entry.values.fold(1) { product, values -> product * ((values as? List<*>)?.size ?: 1) }
    }
    else -> 1
}

/** Whether a file gets the pipeline view. Included fragments have other names and stay plain YAML. */
fun isPipelineFile(name: String): Boolean = name.endsWith(".gitlab-ci.yml") || name.endsWith(".gitlab-ci.yaml")

/** Offset of the top-level key that defines [job] in [text], or null. */
fun definitionOffset(text: String, job: String): Int? =
    Regex("""^(["']?)${Regex.escape(job)}\1\s*:""", RegexOption.MULTILINE).find(text)?.range?.first
