package io.github.svenbluege.gitlabci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which jobs a pipeline contains for given conditions. */
class SimulationTest {
    // --- rules ---

    private val rules = pipeline($$"""
        job:
          when: delayed
          needs: [a]
          rules:
            - if: $CI_COMMIT_TAG
              when: never
            - if: $CI_COMMIT_BRANCH == "release"
              when: manual
              needs: [b]
            - if: $CI_COMMIT_BRANCH == "docs"
              changes: [docs/**]
            - if: $CI_COMMIT_BRANCH == "main"
          script: x
        a: { script: x }
        b: { script: x }
    """)

    @Test
    fun `the first matching rule decides, never excludes, no match excludes`() {
        assertEquals("rule #4: if: \$CI_COMMIT_BRANCH == \"main\"", rules.run(branch = "main").job("job").reason)
        assertTrue(rules.run(branch = "main").job("job").included)

        val tag = rules.run(tag = "v1").job("job")
        assertFalse(tag.included)
        assertEquals("rule #1 (when: never): if: \$CI_COMMIT_TAG", tag.reason)

        val other = rules.run(branch = "feature").job("job")
        assertFalse(other.included)
        assertEquals("no rule matches", other.reason)
    }

    @Test
    fun `a rule can set when and needs, otherwise the job's own apply`() {
        val main = rules.run(branch = "main").job("job")
        assertEquals("delayed", main.whenValue)
        assertEquals(listOf(Need("a", false)), main.needs)

        val release = rules.run(branch = "release").job("job")
        assertEquals("manual", release.whenValue)
        assertEquals(listOf(Need("b", false)), release.needs)
    }

    @Test
    fun `changes and exists follow the switch because files cannot be checked`() {
        assertTrue(rules.run(branch = "docs", changesMatch = true).job("job").included)
        assertFalse(rules.run(branch = "docs", changesMatch = false).job("job").included)
        val exists = pipeline("job: { rules: [{ exists: [Dockerfile] }], script: x }")
        assertEquals("rule #1: exists: [Dockerfile]", exists.run().job("job").reason)
        assertFalse(exists.run(changesMatch = false).job("job").included)
    }

    @Test
    fun `a rule without conditions always matches`() {
        val job = pipeline("job: { rules: [{ when: manual }], script: x }").run(source = "schedule").job("job")
        assertTrue(job.included)
        assertEquals("manual", job.whenValue)
    }

    @Test
    fun `an expression that cannot be evaluated is a problem and does not match`() {
        val job = pipeline($$"""
            job:
              rules:
                - if: $A ==
                - if: $CI_COMMIT_BRANCH
              script: x
        """).run().job("job")
        assertEquals(listOf("cannot evaluate '\$A ==': unexpected end of expression"), job.problems)
        assertEquals("rule #2: if: \$CI_COMMIT_BRANCH", job.reason)
    }

    // --- variables ---

    @Test
    fun `variables override each other from predefined up to the ones given for the run`() {
        val pipeline = pipeline($$"""
            variables:
              LEVEL: global
              TARGET: { value: dev, description: Where to deploy }
            workflow:
              rules:
                - if: $CI_COMMIT_BRANCH == "workflow"
                  variables: { LEVEL: workflow }
                - when: always
            plain: { rules: [{ if: '$LEVEL == "global"' }], script: x }
            flow: { rules: [{ if: '$LEVEL == "workflow"' }], script: x }
            own: { variables: { LEVEL: job }, rules: [{ if: '$LEVEL == "job"' }], script: x }
            given: { variables: { LEVEL: job }, rules: [{ if: '$LEVEL == "run"' }], script: x }
            described: { rules: [{ if: '$TARGET == "dev"' }], script: x }
            predefined: { rules: [{ if: '$CI_PIPELINE_SOURCE == "push" && $CI == "true"' }], script: x }
        """)
        assertEquals(listOf("plain", "own", "described", "predefined"), pipeline.run().running())
        assertEquals(listOf("flow", "own", "described", "predefined"), pipeline.run(branch = "workflow").running())
        assertEquals(listOf("given", "described", "predefined"), pipeline.run(variables = "LEVEL=run").running())
        assertEquals(listOf("plain", "own", "predefined"), pipeline.run(variables = "TARGET=prod").running())

        val shown = pipeline.run(variables = "TARGET=prod").variables
        assertEquals("prod", shown["TARGET"])
        assertEquals("global", shown["LEVEL"])
        assertEquals("main", shown["CI_COMMIT_BRANCH"])
    }

    @Test
    fun `predefined variables describe branch, tag and merge request pipelines`() {
        val branch = predefinedVariables("push", "feature/My_Branch", "", "main")
        assertEquals("feature/My_Branch", branch["CI_COMMIT_BRANCH"])
        assertEquals("feature/My_Branch", branch["CI_COMMIT_REF_NAME"])
        assertEquals("feature-my-branch", branch["CI_COMMIT_REF_SLUG"])
        assertEquals("main", branch["CI_DEFAULT_BRANCH"])
        assertEquals("push", branch["CI_PIPELINE_SOURCE"])
        assertNull(branch["CI_COMMIT_TAG"])

        val tag = predefinedVariables("push", "main", "v1.0", "main")
        assertEquals("v1.0", tag["CI_COMMIT_TAG"])
        assertEquals("v1.0", tag["CI_COMMIT_REF_NAME"])
        assertNull(tag["CI_COMMIT_BRANCH"])

        val mergeRequest = predefinedVariables("merge_request_event", "feature", "", "develop")
        assertNull(mergeRequest["CI_COMMIT_BRANCH"])
        assertEquals("feature", mergeRequest["CI_MERGE_REQUEST_SOURCE_BRANCH_NAME"])
        assertEquals("develop", mergeRequest["CI_MERGE_REQUEST_TARGET_BRANCH_NAME"])
        assertEquals("1", mergeRequest["CI_MERGE_REQUEST_IID"])
    }

    @Test
    fun `run variables are parsed from one line`() {
        assertEquals(mapOf("A" to "1", "B" to "two words", "C" to "", "D" to "x=y"), parseVariables("""A=1  B="two words" C= D=x=y"""))
        assertEquals(emptyMap<String, String>(), parseVariables("  not a variable  "))
    }

    // --- only / except ---

    @Test
    fun `a job without rules runs for branches and tags but not for merge requests`() {
        val plain = pipeline("job: { script: x }")
        assertEquals("no rules", plain.run().job("job").reason)
        assertTrue(plain.run(tag = "v1").job("job").included)
        assertTrue(plain.run(source = "schedule").job("job").included)
        val mergeRequest = plain.run(source = "merge_request_event").job("job")
        assertFalse(mergeRequest.included)
        assertEquals("no rules: only in branch and tag pipelines", mergeRequest.reason)
    }

    @Test
    fun `only matches keywords, branch names and patterns`() {
        val pipeline = pipeline($$"""
            mr: { only: [merge_requests], script: x }
            scheduled: { only: [schedules], script: x }
            manual-run: { only: [web], script: x }
            tagged: { only: [tags], script: x }
            branches: { only: [branches], script: x }
            pushed: { only: [pushes], script: x }
            named: { only: [main, develop@group/project], script: x }
            pattern: { only: [/^release-.*$/], script: x }
            single: { only: main, script: x }
        """)
        assertEquals(listOf("branches", "pushed", "named", "single"), pipeline.run().running())
        assertEquals(listOf("branches", "pushed", "named"), pipeline.run(branch = "develop").running())
        assertEquals(listOf("branches", "pushed", "pattern"), pipeline.run(branch = "release-2").running())
        assertEquals(listOf("tagged", "pushed"), pipeline.run(tag = "v1").running())
        assertEquals(listOf("scheduled", "branches", "named", "single"), pipeline.run(source = "schedule").running())
        assertEquals(listOf("manual-run", "branches", "named", "single"), pipeline.run(source = "web").running())
        assertEquals(listOf("mr"), pipeline.run(source = "merge_request_event", branch = "feature").running())
        assertEquals("only matches", pipeline.run().job("named").reason)
        assertEquals("only does not match", pipeline.run().job("tagged").reason)
    }

    @Test
    fun `only needs all of its clauses, except any of them`() {
        val pipeline = pipeline($$"""
            both: { only: { refs: [main], variables: [$DEPLOY == "yes", $FORCE] }, script: x }
            files: { only: { changes: [src/**] }, script: x }
            never-on-main: { except: [main], script: x }
            unless: { except: { variables: [$SKIP == "1"] }, script: x }
        """)
        assertEquals(listOf("files", "unless"), pipeline.run().running())
        assertEquals(listOf("both", "files", "unless"), pipeline.run(variables = "DEPLOY=yes").running())
        assertEquals(listOf("both", "files", "unless"), pipeline.run(variables = "FORCE=1").running())
        assertEquals(listOf("files", "never-on-main", "unless"), pipeline.run(branch = "feature", variables = "DEPLOY=yes").running())
        assertEquals(listOf("files"), pipeline.run(variables = "SKIP=1").running())
        assertEquals(listOf("unless"), pipeline.run(changesMatch = false).running())
        assertEquals("except matches", pipeline.run().job("never-on-main").reason)
        assertEquals("except does not match", pipeline.run().job("unless").reason)
        // except never brings a job into a merge request pipeline
        assertFalse(pipeline.run(source = "merge_request_event", branch = "feature").job("never-on-main").included)
    }

    // --- workflow ---

    private val workflow = pipeline($$"""
        workflow:
          rules:
            - if: $CI_PIPELINE_SOURCE == "merge_request_event"
              when: never
            - if: $CI_COMMIT_BRANCH == "main"
              variables: { DEPLOY: "yes" }
            - if: $CI_COMMIT_TAG
        deploy: { rules: [{ if: '$DEPLOY == "yes"' }], script: x }
        build: { script: x }
    """)

    @Test
    fun `workflow rules can prevent the whole pipeline`() {
        val mergeRequest = workflow.run(source = "merge_request_event")
        assertEquals("workflow: rule #1 (when: never): if: \$CI_PIPELINE_SOURCE == \"merge_request_event\"", mergeRequest.blocked)
        assertTrue(mergeRequest.running().isEmpty())
        assertEquals("no pipeline is created (workflow rules)", mergeRequest.job("build").reason)

        val feature = workflow.run(branch = "feature")
        assertEquals("workflow: no rule matches", feature.blocked)
        assertTrue(feature.running().isEmpty())
    }

    @Test
    fun `workflow rules can set variables for the jobs`() {
        assertNull(workflow.run().blocked)
        assertEquals(listOf("deploy", "build"), workflow.run().running())
        assertEquals(listOf("build"), workflow.run(tag = "v1").running())
        assertNull(pipeline("job: { script: x }").run().blocked)
    }

    // --- needs and dependencies ---

    @Test
    fun `needs come in several notations and other pipelines are left out`() {
        val result = pipeline("""
            stages: [build, test]
            a: { stage: build, script: x }
            b: { stage: build, script: x }
            listed:
              stage: test
              needs:
                - a
                - job: b
                  optional: true
                - job: b
                  artifacts: false
                - project: group/other
                  job: build
                  ref: main
                - pipeline: other
            none: { stage: test, needs: [], script: x }
            implicit: { stage: test, script: x }
            artifacts: { stage: test, dependencies: [a], script: x }
        """).run()
        assertEquals(listOf(Need("a", false), Need("b", true), Need("b", false)), result.job("listed").needs)
        assertEquals(emptyList<Need>(), result.job("none").needs)
        assertNull(result.job("implicit").needs)
        assertEquals(listOf("a"), result.job("artifacts").dependencies)
        assertTrue(result.jobs.all { it.problems.isEmpty() })
    }

    @Test
    fun `needs that cannot be met are problems of the running job`() {
        val result = pipeline($$"""
            stages: [build, test]
            skipped: { stage: build, rules: [{ if: $NEVER }], script: x }
            late: { stage: test, script: x }
            job:
              stage: build
              needs:
                - skipped
                - unknown
                - late
                - job: skipped
                  optional: true
                - job: unknown-too
                  optional: true
              script: x
            idle: { stage: test, rules: [{ if: $NEVER }], needs: [unknown], script: x }
        """).run()
        assertEquals(
            listOf("needs 'skipped', which is not in this pipeline", "needs unknown job 'unknown'", "needs 'late' from a later stage"),
            result.job("job").problems,
        )
        assertTrue("a skipped job is not checked", result.job("idle").problems.isEmpty())
    }

    @Test
    fun `parallel multiplies a job`() {
        assertEquals(1, parallelCount(null))
        assertEquals(4, parallelCount(4))
        val matrix = pipeline("""
            job:
              parallel:
                matrix:
                  - { OS: [linux, mac], NODE: ["20", "22"], ARCH: x64 }
                  - { OS: [windows] }
              script: x
        """).run().job("job").config["parallel"]
        assertEquals(5, parallelCount(matrix))
    }
}
