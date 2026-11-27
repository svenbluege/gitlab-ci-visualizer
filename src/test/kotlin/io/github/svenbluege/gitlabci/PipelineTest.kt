package io.github.svenbluege.gitlabci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class PipelineTest {
    private val main = """
        include:
          - local: /ci/deploy.yml
          - template: Security/SAST.gitlab-ci.yml
        stages: [build, test, deploy]
        variables:
          DEPLOY_ENV: { value: dev, description: Target }
        workflow:
          rules:
            - if: ${'$'}CI_PIPELINE_SOURCE == "merge_request_event"
              when: never
            - when: always
        .base:
          stage: build
          variables: { A: "1" }
        .main-only:
          rules:
            - if: ${'$'}CI_COMMIT_TAG
              when: never
            - if: ${'$'}CI_COMMIT_BRANCH == ${'$'}CI_DEFAULT_BRANCH
        compile:
          extends: .base
          script: make
        lint:
          stage: build
          script: lint
          rules:
            - if: ${'$'}CI_COMMIT_BRANCH =~ /^feature\//
        unit:
          stage: test
          needs: [compile, { job: lint, optional: true }]
          parallel: { matrix: [{ OS: [linux, mac], NODE: ["20", "22"] }, { OS: [windows] }] }
          script: test
        package:
          stage: test
          needs: [lint]
          script: pack
        legacy:
          stage: test
          only: [tags, /^release-.*$/]
          except: { variables: [${'$'}SKIP_LEGACY == "1"] }
          script: old
    """.trimIndent()

    private val deploy = """
        deploy:
          stage: deploy
          rules:
            - !reference [.main-only, rules]
            - if: ${'$'}DEPLOY_ENV == "prod"
              when: manual
          dependencies: [compile]
          script: deploy
    """.trimIndent()

    private val pipeline = Pipeline.load(main) { path -> deploy.takeIf { path == "ci/deploy.yml" } }

    private fun run(source: String = "push", branch: String = "main", tag: String = "", variables: String = "") =
        pipeline.simulate(predefinedVariables(source, branch, tag, "main"), parseVariables(variables))

    private fun PipelineResult.running() = jobs.filter { it.included }.map { it.name }.toSet()
    private fun PipelineResult.job(name: String) = jobs.first { it.name == name }

    @Test
    fun `push to the default branch`() {
        val result = run()
        assertEquals(setOf("deploy", "compile", "unit", "package"), result.running())
        assertNull(result.blocked)
        assertEquals(listOf(".pre", "build", "test", "deploy", ".post"), result.stages)
        assertEquals("build", result.job("compile").stage) // from extends
        assertEquals("rule #2: if: \$CI_COMMIT_BRANCH == \$CI_DEFAULT_BRANCH", result.job("deploy").reason) // !reference spliced into rules
        assertEquals(listOf("compile"), result.job("deploy").dependencies)
        assertEquals("no rule matches", result.job("lint").reason)
        assertTrue(result.job("unit").problems.isEmpty()) // lint is missing but optional
        assertEquals(listOf("needs 'lint', which is not in this pipeline"), result.job("package").problems)
        assertEquals(1, result.notes.count { "SAST" in it }) // template include cannot be resolved
    }

    @Test
    fun `feature branch runs lint and its dependants`() {
        val result = run(branch = "feature/x")
        assertEquals(setOf("compile", "lint", "unit", "package"), result.running())
        assertEquals(listOf(Need("compile", false), Need("lint", true)), result.job("unit").needs)
    }

    @Test
    fun `needs on skipped or unknown jobs are problems`() {
        val broken = Pipeline.load("a:\n  rules: [{ if: \$X }]\n  script: a\nb:\n  needs: [a, nope]\n  script: b")
        val job = broken.simulate(predefinedVariables("push", "main", "", "main")).jobs.first { it.name == "b" }
        assertEquals(listOf("needs 'a', which is not in this pipeline", "needs unknown job 'nope'"), job.problems)
    }

    @Test
    fun `variables given for the run override the file`() {
        val result = run(tag = "", branch = "dev", variables = "DEPLOY_ENV=prod")
        assertEquals("manual", result.job("deploy").whenValue)
        assertTrue(result.job("deploy").included)
        assertFalse(run(branch = "dev").job("deploy").included) // DEPLOY_ENV defaults to dev
    }

    @Test
    fun `tags and only-except`() {
        assertEquals(setOf("compile", "unit", "package", "legacy"), run(tag = "v1").running())
        assertEquals("rule #1 (when: never): if: \$CI_COMMIT_TAG", run(tag = "v1").job("deploy").reason)
        assertTrue(run(branch = "release-1").job("legacy").included)
        assertEquals("except matches", run(branch = "release-1", variables = "SKIP_LEGACY=1").job("legacy").reason)
        assertEquals("only does not match", run().job("legacy").reason)
    }

    @Test
    fun `workflow rules can prevent the pipeline`() {
        val result = run(source = "merge_request_event")
        assertEquals("workflow: rule #1 (when: never): if: \$CI_PIPELINE_SOURCE == \"merge_request_event\"", result.blocked)
        assertTrue(result.running().isEmpty())
    }

    @Test
    fun `jobs without rules are not part of merge request pipelines`() {
        val plain = Pipeline.load("a:\n  script: a\nb:\n  only: [merge_requests]\n  script: b")
        val result = plain.simulate(predefinedVariables("merge_request_event", "feature", "", "main"))
        assertEquals(listOf("b"), result.jobs.filter { it.included }.map { it.name })
    }

    @Test
    fun `parallel counts`() {
        assertEquals(5, parallelCount(pipeline.simulate(emptyMap()).job("unit").config["parallel"]))
        assertEquals(3, parallelCount(3))
        assertEquals(1, parallelCount(null))
    }

    @Test
    fun `renders jobs, edges and state`() {
        val html = Renderer.render(run(), dark = true, showSkipped = true)
        assertTrue("""class="dark"""" in html)
        assertTrue("""data-id="compile"""" in html)
        assertTrue("""class="job off" data-id="lint"""" in html)
        assertTrue("""class="edge needs" data-from="compile" data-to="unit"""" in html)
        assertTrue("""class="edge needs off" data-from="lint" data-to="unit"""" in html)
        assertTrue("""class="edge deps" data-from="compile" data-to="deploy"""" in html)
        assertTrue("×5" in html)
        assertFalse("data-id=\"lint\"" in Renderer.render(run(), dark = false, showSkipped = false))
    }

    /** Renders a real file without an IDE: set GITLAB_CI_FILE (and optionally GITLAB_CI_OUT, GITLAB_CI_BRANCH). */
    @Test
    fun `renders the file from the environment`() {
        val path = System.getenv("GITLAB_CI_FILE")
        assumeNotNull(path)
        val file = File(path)
        val root = generateSequence(file.absoluteFile.parentFile) { it.parentFile }.first { File(it, ".git").exists() }
        val result = Pipeline.load(file.readText()) { File(root, it).takeIf(File::isFile)?.readText() }
            .simulate(predefinedVariables("push", System.getenv("GITLAB_CI_BRANCH") ?: "main", "", "main"))
        File(System.getenv("GITLAB_CI_OUT") ?: "build/pipeline.html").writeText(Renderer.render(result, dark = true, showSkipped = true))
        println("${result.jobs.count { it.included }} of ${result.jobs.size} jobs, notes: ${result.notes}")
    }
}
