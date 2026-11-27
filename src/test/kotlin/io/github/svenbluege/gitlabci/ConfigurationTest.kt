package io.github.svenbluege.gitlabci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a file becomes jobs: YAML, include, !reference and extends. */
class ConfigurationTest {
    @Test
    fun `jobs are the top-level mappings that are neither keywords nor hidden`() {
        val result = pipeline($$"""
            stages: [build]
            variables: { A: "1" }
            default: { image: alpine }
            workflow: { rules: [{ when: always }] }
            image: alpine
            cache: { paths: [x] }
            before_script: [echo]
            .hidden: { script: x }
            build: { stage: build, script: make }
            pages: { stage: build, script: make }
            123: { stage: build, script: numeric name }
            scalar: just a string
        """).run()
        assertEquals(listOf("build", "pages", "123"), result.jobs.map { it.name })
    }

    @Test
    fun `without stages the default stages apply and a job lands in test`() {
        val result = pipeline("a: { script: x }").run()
        assertEquals(listOf(".pre", "build", "test", "deploy", ".post"), result.stages)
        assertEquals("test", result.job("a").stage)
    }

    @Test
    fun `pre and post always frame the declared stages`() {
        val result = pipeline("""
            stages: [.post, lint, .pre, ship]
            a: { stage: .pre, script: x }
        """).run()
        assertEquals(listOf(".pre", "lint", "ship", ".post"), result.stages)
        assertTrue(result.job("a").problems.isEmpty())
    }

    @Test
    fun `a job in an undeclared stage stays visible and is reported`() {
        val result = pipeline("""
            stages: [build]
            a: { stage: nowhere, script: x }
        """).run()
        assertEquals(listOf(".pre", "build", ".post", "nowhere"), result.stages)
        assertEquals(listOf("stage 'nowhere' is not defined in stages"), result.job("a").problems)
    }

    @Test
    fun `extends merges maps key by key, replaces lists and lets the job win`() {
        val job = pipeline("""
            .base:
              stage: build
              variables: { A: base, B: base }
              script: [one, two]
              tags: [docker]
            job:
              extends: .base
              variables: { B: job, C: job }
              script: [three]
        """).run().job("job")
        assertEquals("build", job.stage)
        assertEquals(mapOf("A" to "base", "B" to "job", "C" to "job"), job.config["variables"])
        assertEquals(listOf("three"), job.config["script"])
        assertEquals(listOf("docker"), job.config["tags"])
    }

    @Test
    fun `with several parents the later one wins, and parents may extend too`() {
        val job = pipeline("""
            .root: { stage: deploy, image: root, variables: { A: root } }
            .first: { extends: .root, image: first }
            .second: { image: second, variables: { A: second } }
            job: { extends: [.first, .second], script: x }
        """).run().job("job")
        assertEquals("deploy", job.stage)
        assertEquals("second", job.config["image"])
        assertEquals(mapOf("A" to "second"), job.config["variables"])
    }

    @Test
    fun `a visible job can be extended as well`() {
        val result = pipeline("""
            base: { stage: build, script: x }
            copy: { extends: base }
        """).run()
        assertEquals("build", result.job("copy").stage)
    }

    @Test
    fun `unknown and circular extends are reported instead of failing`() {
        val result = pipeline("""
            a: { extends: .missing, script: x }
            b: { extends: c, script: x }
            c: { extends: b, script: x }
        """).run()
        assertEquals(listOf("a", "b", "c"), result.jobs.map { it.name })
        assertTrue(result.notes.any { it == "extends: unknown job '.missing'" })
        assertTrue(result.notes.any { it.startsWith("extends: circular reference") })
    }

    @Test
    fun `reference takes over a value and is spliced into a list`() {
        val result = pipeline($$"""
            .setup:
              script: [install, configure]
              rules:
                - if: $CI_COMMIT_TAG
                  when: never
              variables: { REGION: eu }
            job:
              script:
                - before
                - !reference [.setup, script]
                - after
              rules:
                - !reference [.setup, rules]
                - when: manual
              variables:
                REGION: !reference [.setup, variables, REGION]
            other:
              rules: !reference [.setup, rules]
              script: x
        """).run()
        val job = result.job("job")
        assertEquals(listOf("before", "install", "configure", "after"), job.config["script"])
        assertEquals(mapOf("REGION" to "eu"), job.config["variables"])
        assertEquals("rule #2 (when: manual): no condition", job.reason)
        assertEquals("no rule matches", result.job("other").reason)
    }

    @Test
    fun `a reference may point to another reference, but not in a circle`() {
        val chained = pipeline("""
            .a: { script: [a] }
            .b: { script: !reference [.a, script] }
            job: { script: !reference [.b, script] }
        """).run()
        assertEquals(listOf("a"), chained.job("job").config["script"])
        assertThrows(IllegalArgumentException::class.java) {
            pipeline("""
                .a: { script: !reference [.b, script] }
                .b: { script: !reference [.a, script] }
            """)
        }
    }

    @Test
    fun `yaml anchors and merge keys work`() {
        val job = pipeline("""
            .defaults: &defaults
              stage: deploy
              tags: [docker]
            job:
              <<: *defaults
              script: x
        """).run().job("job")
        assertEquals("deploy", job.stage)
        assertEquals(listOf("docker"), job.config["tags"])
    }

    @Test
    fun `local includes are merged in every notation, and the including file wins`() {
        val includes = mapOf(
            "ci/build.yml" to "build: { stage: build, script: included, tags: [a] }",
            "ci/test.yml" to "include: ci/nested.yml\ntest: { script: x }",
            "ci/nested.yml" to "nested: { script: x }",
        )
        val result = pipeline("""
            include:
              - /ci/build.yml
              - local: ci/test.yml
            build: { script: overridden }
            own: { script: x }
        """, includes).run()
        assertEquals(listOf("build", "nested", "test", "own"), result.jobs.map { it.name })
        assertEquals("overridden", result.job("build").config["script"])
        assertEquals(listOf("a"), result.job("build").config["tags"])
        assertTrue(result.notes.isEmpty())
        // Where "Go to definition" leads: the file that defines the job last.
        assertNull(result.job("build").source)
        assertNull(result.job("own").source)
        assertEquals("ci/test.yml", result.job("test").source)
        assertEquals("ci/nested.yml", result.job("nested").source)
    }

    @Test
    fun `a single include can be a plain string`() {
        val result = pipeline("include: ci/one.yml", mapOf("ci/one.yml" to "one: { script: x }")).run()
        assertEquals(listOf("one"), result.jobs.map { it.name })
    }

    @Test
    fun `includes that cannot be read are listed and the rest still loads`() {
        val result = pipeline("""
            include:
              - local: missing.yml
              - template: Security/SAST.gitlab-ci.yml
              - project: group/project
                file: /ci.yml
              - remote: https://example.com/ci.yml
              - https://example.com/other.yml
            job: { script: x }
        """).run()
        assertEquals(listOf("job"), result.jobs.map { it.name })
        assertEquals(5, result.notes.size)
        assertTrue(result.notes.all { it.startsWith("Include not resolved") })
        assertTrue(result.notes.any { "SAST" in it })
    }

    @Test
    fun `an include that includes itself ends`() {
        val result = pipeline("include: loop.yml", mapOf("loop.yml" to "include: loop.yml\njob: { script: x }")).run()
        assertEquals(listOf("job"), result.jobs.map { it.name })
    }

    @Test
    fun `a spec header is skipped`() {
        val result = pipeline("""
            spec:
              inputs:
                environment: { default: dev }
            ---
            job: { script: x }
        """).run()
        assertEquals(listOf("job"), result.jobs.map { it.name })
    }

    @Test
    fun `an empty file has no jobs`() {
        assertTrue(pipeline("").run().jobs.isEmpty())
        assertTrue(pipeline("# only a comment").run().jobs.isEmpty())
    }

    @Test
    fun `broken yaml and non-mappings are errors with a message`() {
        assertThrows(RuntimeException::class.java) { pipeline("job: [unclosed") }
        val error = assertThrows(IllegalArgumentException::class.java) { pipeline("- a\n- b") }
        assertEquals("The file must contain a YAML mapping of jobs", error.message)
    }

    @Test
    fun `finds where a job is defined`() {
        val text = "stages: [a]\n\n.build:\n  script: x\nbuild:\n  needs: [build-image]\n\"quoted job\":\n  script: x\nbuild-image: {}\n"
        assertEquals(text.indexOf("build:\n  needs"), definitionOffset(text, "build"))
        assertEquals(text.indexOf("\"quoted job\""), definitionOffset(text, "quoted job"))
        assertEquals(text.indexOf("build-image: {}"), definitionOffset(text, "build-image"))
        assertNull(definitionOffset(text, "script"))
        assertNull(definitionOffset(text, "absent"))
    }

    @Test
    fun `only pipeline files get the view`() {
        for (name in listOf(".gitlab-ci.yml", ".gitlab-ci.yaml", "backend.gitlab-ci.yml")) assertTrue(name, isPipelineFile(name))
        for (name in listOf("build.yml", "gitlab-ci.yml.bak", "docker-compose.yml", ".gitlab-ci.yml.orig")) assertTrue(name, !isPipelineFile(name))
    }
}
