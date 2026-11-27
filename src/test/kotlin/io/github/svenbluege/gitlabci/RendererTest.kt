package io.github.svenbluege.gitlabci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import kotlin.math.pow

class RendererTest {
    /** One job per state: runs, skipped, manual, delayed, with a problem, plus a trigger and an included job. */
    private val result = pipeline($$"""
        include: ci/docs.yml
        stages: [build, test, deploy]
        compile: { stage: build, script: x }
        lint: { stage: build, rules: [{ if: $NEVER }], script: x }
        unit: { stage: test, needs: [compile, { job: lint, optional: true }], parallel: 3, script: x }
        broken: { stage: test, needs: [lint], script: x }
        staging: { stage: deploy, needs: [unit], dependencies: [compile], environment: { name: staging }, script: x }
        production: { stage: deploy, needs: [staging], when: manual, script: x }
        later: { stage: deploy, when: delayed, start_in: 5 minutes, script: x }
        downstream: { stage: deploy, trigger: group/project }
    """, mapOf("ci/docs.yml" to "docs: { stage: deploy, needs: [], script: x }")).run()

    private val html = Renderer.graph(result, showSkipped = true)
    private val style = Renderer.Style(dark = true)

    private class Card(
        val classes: String, val id: String, val tabindex: Int, val label: String, val name: String,
        val column: Int, val row: Int, val x: Int, val y: Int, val width: Int, val height: Int,
    )

    private fun cards(html: String): List<Card> = Regex(
        """<g class="(job[^"]*)" id="(j\d+)" role="button" tabindex="(-?\d)" aria-pressed="false" aria-label="([^"]*)" """ +
            """data-name="([^"]*)" data-col="(\d+)" data-row="(\d+)".*?<rect class="card" x="(\d+)" y="(\d+)" width="(\d+)" height="(\d+)"""",
    ).findAll(html).map {
        val v = it.groupValues
        Card(v[1], v[2], v[3].toInt(), v[4], v[5], v[6].toInt(), v[7].toInt(), v[8].toInt(), v[9].toInt(), v[10].toInt(), v[11].toInt())
    }.toList()

    private fun card(name: String, html: String = this.html) = cards(html).first { it.name == name }

    /** The details section of a job. */
    private fun detail(name: String, html: String = this.html): String =
        Regex("""<section class="detail" data-for="${card(name, html).id}" hidden>(.*?)</section>""").find(html)!!.groupValues[1]

    private fun edges(html: String): Set<String> = Regex("""<path class="edge ([^"]*)" data-from="([^"]*)" data-to="([^"]*)"""")
        .findAll(html).map { "${it.groupValues[2]} -> ${it.groupValues[3]} (${it.groupValues[1]})" }.toSet()

    private fun texts(kind: String, html: String = this.html) =
        Regex("""<text class="$kind"[^>]*>([^<]*)</text>""").findAll(html).map { it.groupValues[1] }.toList()

    // --- structure ---

    @Test
    fun `there is one lane per stage that has jobs, in stage order, with its share of running jobs`() {
        assertEquals(listOf("build", "test", "deploy"), texts("stage"))
        assertEquals(listOf("1/2", "2/2", "5/5"), texts("count"))
        assertTrue("""aria-label="Stage build: 1 of 2 jobs run"""" in html)
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 2, 2, 2), cards(html).map { it.column }.sorted())
        assertEquals(listOf("docs", "staging", "production", "later", "downstream"), cards(html).filter { it.column == 2 }.sortedBy { it.row }.map { it.name })
    }

    @Test
    fun `every state has its own class and icon`() {
        val expected = mapOf(
            "compile" to "job run", "lint" to "job skipped off", "broken" to "job problem",
            "production" to "job manual", "later" to "job delayed", "downstream" to "job run",
        )
        for ((name, classes) in expected) assertEquals(name, classes, card(name).classes)
        for (kind in listOf("run", "skipped", "problem", "manual", "delayed")) {
            assertTrue(kind, """href="#i-$kind"""" in html)
            assertTrue(kind, """<symbol id="i-$kind"""" in Renderer.page("", style))
        }
    }

    @Test
    fun `badges name what the icon cannot show`() {
        // in reading order: build, test, deploy
        assertEquals(listOf("", "", "×3", "", "", "", "manual", "delayed", "trigger"), texts("badge"))
    }

    @Test
    fun `lines show needs and dependencies, and fade when an end is skipped`() {
        assertEquals(
            setOf(
                "compile -> unit (needs)", "lint -> unit (needs off)", "lint -> broken (needs off)",
                "unit -> staging (needs)", "compile -> staging (deps)", "staging -> production (needs)",
            ),
            edges(html),
        )
    }

    @Test
    fun `hiding skipped jobs removes their cards and lines but keeps them in the counts and details`() {
        val hidden = Renderer.graph(result, showSkipped = false)
        assertFalse("""data-name="lint"""" in hidden)
        assertEquals(setOf("compile -> unit (needs)", "unit -> staging (needs)", "compile -> staging (deps)", "staging -> production (needs)"), edges(hidden))
        assertTrue("<b>1</b> skipped" in hidden)
        assertTrue("""<span class="mono">lint</span> (optional) (skipped)""" in detail("unit", hidden))
        assertEquals(0, card("compile", hidden).tabindex)
    }

    @Test
    fun `the summary counts jobs by state and explains the line styles`() {
        val summary = html.substringAfter("""<header class="summary">""").substringBefore("</header>")
        assertEquals(
            listOf("5 run", "1 manual", "1 delayed", "1 skipped", "1 with problems"),
            Regex("""<b>(\d+)</b> ([a-z ]+)</li>""").findAll(summary).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toList(),
        )
        assertTrue(""">needs</span>""" in summary)
        assertTrue(""">dependencies</span>""" in summary)
    }

    // --- details ---

    @Test
    fun `the details explain the decision and the relations of a job`() {
        val staging = detail("staging")
        assertTrue("<dt>Status</dt><dd>" in staging && "Runs</dd>" in staging)
        assertTrue("<dt>Why</dt><dd>no rules</dd>" in staging)
        assertTrue("<dt>Stage</dt><dd>deploy</dd>" in staging)
        assertTrue("""<dt>Needs</dt><dd><button type="button" class="ref" data-ref="unit">unit</button></dd>""" in staging)
        assertTrue("""<dt>Needed by</dt><dd><button type="button" class="ref" data-ref="production">production</button></dd>""" in staging)
        assertTrue("""<dt>Artifacts from</dt><dd><button type="button" class="ref" data-ref="compile">compile</button></dd>""" in staging)
        assertTrue("<dt>Environment</dt><dd>staging</dd>" in staging)
        assertTrue("""<button type="button" class="goto" data-name="staging">Go to definition</button>""" in staging)

        assertTrue("All jobs of earlier stages" in detail("compile"))
        assertTrue("Nothing: it starts as soon as the pipeline starts" in detail("docs"))
        assertTrue("<dt>Defined in</dt><dd>ci/docs.yml</dd>" in detail("docs"))
        assertFalse("Defined in" in detail("compile"))
        assertTrue("""data-ref="lint">lint</button> (optional) (skipped)""" in detail("unit"))
        assertTrue("<dt>Parallel</dt><dd>3 jobs</dd>" in detail("unit"))
        assertTrue("Skipped: not part of this pipeline" in detail("lint"))
        assertTrue("<dt>Why</dt><dd>no rule matches</dd>" in detail("lint"))
        assertTrue("Manual: waits until someone starts it" in detail("production"))
        assertTrue("Delayed: starts after 5 minutes" in detail("later"))
        assertTrue("<dt>Triggers</dt><dd>group/project</dd>" in detail("downstream"))
        assertTrue("""<dt>Problems</dt><dd><ul class="problems"><li>needs 'lint', which is not in this pipeline</li></ul></dd>""" in detail("broken"))
    }

    @Test
    fun `the variables of the simulation are listed by name`() {
        val variables = html.substringAfter("""<details class="vars">""").substringBefore("</details>")
        assertTrue("Variables in this simulation (${result.variables.size})" in variables)
        assertTrue("<dt>CI_COMMIT_BRANCH</dt><dd>main</dd>" in variables)
        val names = Regex("<dt>([^<]*)</dt>").findAll(variables).map { it.groupValues[1] }.toList()
        assertEquals(names.sorted(), names)
    }

    // --- accessibility ---

    @Test
    fun `jobs are buttons with a spoken description and exactly one tab stop`() {
        val cards = cards(html)
        assertEquals(result.jobs.size, cards.size)
        // the top left card, although the included job "docs" comes first in the file order
        assertEquals(listOf("compile"), cards.filter { it.tabindex == 0 }.map { it.name })
        assertTrue(cards.all { it.tabindex == 0 || it.tabindex == -1 })
        assertEquals("compile, stage build, Runs: no rules", card("compile").label)
        assertEquals("lint, stage build, Skipped: no rule matches", card("lint").label)
        assertEquals("production, stage deploy, Manual: no rules", card("production").label)
        assertEquals("later, stage deploy, Delayed: no rules", card("later").label)
        assertEquals("broken, stage test, Runs: no rules, problem: needs &#39;lint&#39;, which is not in this pipeline".replace("&#39;", "'"), card("broken").label)
        // 30px high: above the 24px minimum target size
        assertTrue(cards.all { it.height >= 24 })
    }

    @Test
    fun `the drawing is labelled and its decoration is hidden from assistive technology`() {
        val svg = html.substringAfter("<svg class=\"graph\"").substringBefore(">")
        assertTrue("""role="group"""" in svg)
        assertTrue("Pipeline graph: 9 jobs in 3 stages." in svg)
        assertTrue("Arrow keys move between jobs, Enter opens the job definition, Escape clears the selection." in svg)
        assertTrue("""<g class="edges" aria-hidden="true">""" in html)
        assertTrue("""<g class="above" aria-hidden="true">""" in html)
        assertTrue("""<aside aria-label="Job details">""" in html)
        assertTrue("With the keyboard: arrow keys move between jobs" in html)
        // every icon is decoration next to a text
        assertTrue(Regex("""<svg class="icon [^>]*>""").findAll(html).all { """aria-hidden="true"""" in it.value })
        assertTrue(Regex("<button [^>]*>").findAll(html).all { """type="button"""" in it.value })
        // the zoom buttons show only a sign, so they carry a name
        assertTrue("""<span class="zoom" role="group" aria-label="Zoom">""" in html)
        assertTrue("""aria-label="Zoom out"""" in html && """aria-label="Zoom in"""" in html && ">Fit</button>" in html)
        assertEquals(cards(html).size, Regex("""<section class="detail" """).findAll(html).count())
    }

    @Test
    fun `the page declares language, title and a hidden tooltip`() {
        val page = Renderer.render(result, style, showSkipped = true)
        assertTrue(page.startsWith("""<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>Pipeline graph</title>"""))
        assertTrue("""<div id="tip" aria-hidden="true"></div>""" in page)
        assertEquals(1, Regex("<main>").findAll(page).count())
        assertTrue(":focus-visible" in page)
    }

    /** WCAG 2.1: 4.5:1 for text (1.4.3), 3:1 for icons, lines and focus marks (1.4.11), on every surface they appear on. */
    @Test
    fun `colors keep their contrast in both themes`() {
        for (dark in listOf(false, true)) {
            fun color(role: String) = Renderer.PALETTE.getValue(role).let { if (dark) it.second else it.first }.let(::rgb)
            fun tinted(role: String, share: Double) = color(role).zip(color("card")) { a, b -> a * share + b * (1 - share) }
            val surfaces = mapOf(
                "bg" to color("bg"), "lane" to color("lane"), "card" to color("card"),
                "run card" to tinted("run", 0.09), "manual card" to tinted("manual", 0.09),
                "problem card" to tinted("problem", 0.09), "selected card" to tinted("accent", 0.16),
            )
            for ((surface, background) in surfaces) {
                for (ink in listOf("text", "muted")) {
                    val ratio = contrast(color(ink), background)
                    assertTrue("$ink on $surface (dark=$dark) is $ratio", ratio >= 4.5)
                }
                for (mark in listOf("edge", "run", "manual", "skipped", "problem", "accent", "warning")) {
                    val ratio = contrast(color(mark), background)
                    assertTrue("$mark on $surface (dark=$dark) is $ratio", ratio >= 3.0)
                }
            }
        }
    }

    private fun rgb(hex: String) = listOf(1, 3, 5).map { hex.substring(it, it + 2).toInt(16).toDouble() }

    private fun luminance(color: List<Double>): Double {
        val (r, g, b) = color.map { (it / 255).let { c -> if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) } }
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: List<Double>, b: List<Double>): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    // --- geometry ---

    @Test
    fun `cards do not overlap and everything stays inside the drawing`() {
        val (width, height) = Regex("""<svg class="graph" width="(\d+)" height="(\d+)"""").find(html)!!.destructured.let { (w, h) -> w.toInt() to h.toInt() }
        val cards = cards(html)
        for (card in cards) {
            assertTrue(card.name, card.x >= 0 && card.y >= 0 && card.x + card.width <= width && card.y + card.height <= height)
            for (other in cards) {
                val apart = card === other || card.x + card.width <= other.x || other.x + other.width <= card.x ||
                    card.y + card.height <= other.y || other.y + other.height <= card.y
                assertTrue("${card.name} overlaps ${other.name}", apart)
            }
        }
        val numbers = Regex("""data-from="([^"]*)" data-to="([^"]*)" d="M(\d+),(\d+) C(\d+),(\d+) (\d+),(\d+) (\d+),(\d+)"""")
        for (edge in numbers.findAll(html)) {
            val (from, to) = card(edge.groupValues[1]) to card(edge.groupValues[2])
            val x = listOf(3, 5, 7, 9).map { edge.groupValues[it].toInt() }
            val y = listOf(4, 6, 8, 10).map { edge.groupValues[it].toInt() }
            assertEquals("starts at the right edge of ${from.name}", from.x + from.width, x.first())
            assertEquals(from.y + from.height / 2, y.first())
            assertEquals(to.y + to.height / 2, y.last())
            // towards a later stage it ends at the left edge, within a stage it loops around the right edge
            assertEquals(if (to.column > from.column) to.x else to.x + to.width, x.last())
            assertTrue("${from.name} -> ${to.name} leaves the drawing", x.all { it in 0..width } && y.all { it in 0..height })
        }
    }

    @Test
    fun `long names are shortened on the card only`() {
        val name = "integration-tests-" + "x".repeat(60)
        val long = Renderer.graph(pipeline("$name: { script: x }").run(), showSkipped = true)
        assertEquals(listOf(name.take(47) + "…"), texts("name", long))
        assertTrue(card(name, long).label.startsWith("$name, stage test"))
        assertTrue("<span>$name</span>" in detail(name, long))
        assertTrue(card(name, long).width < 450)
    }

    // --- states of the page ---

    @Test
    fun `text from the file cannot inject markup`() {
        val hostile = pipeline($$"""
            variables: { 'X': '</dd><script>alert(1)</script>' }
            include: { remote: 'https://x/<script>' }
            '<em>&"x"': { stage: '<i>', environment: '<u>', script: x }
        """).run()
        val page = Renderer.render(hostile, style, showSkipped = true)
        for (raw in listOf("<em>", "<i>", "<u>", "<script>alert", "https://x/<script>")) assertFalse(raw, raw in page)
        assertTrue("""data-name="&lt;em&gt;&amp;&quot;x&quot;"""" in page)
        assertFalse("<script>" in Renderer.error("<script>alert(1)</script>"))
    }

    @Test
    fun `empty and blocked pipelines say so`() {
        assertTrue("No jobs found." in Renderer.graph(pipeline("").run(), showSkipped = true))

        val nothing = pipeline($$"a: { rules: [{ if: $NEVER }], script: x }").run()
        val hidden = Renderer.graph(nothing, showSkipped = false)
        assertTrue("No job runs in this simulation. Turn on “Show skipped jobs” to see all 1 jobs." in hidden)
        assertFalse("<svg class=\"graph\"" in hidden)
        assertFalse("class=\"zoom\"" in hidden)

        val blocked = pipeline("""
            include: { template: Jobs/Build.gitlab-ci.yml }
            workflow: { rules: [{ when: never }] }
            a: { script: x }
        """).run()
        val page = Renderer.graph(blocked, showSkipped = true)
        assertTrue("""<div class="banner error" role="alert">""" in page)
        assertTrue("<strong>No pipeline is created for these conditions</strong>workflow: rule #1 (when: never): no condition" in page)
        assertTrue("""<div class="banner note" role="note">""" in page)
        assertTrue("Include not resolved, its jobs are missing: {template=Jobs/Build.gitlab-ci.yml}" in page)
        assertEquals("job skipped off", card("a", page).classes)
    }

    @Test
    fun `an error is shown alone or above the last graph`() {
        val alone = Renderer.error("mapping values are not allowed here\n in line 3")
        assertTrue("""role="alert"""" in alone)
        assertTrue("<strong>This file cannot be read as a pipeline</strong>mapping values are not allowed here&#10; in line 3" in alone)
        assertFalse("<main" in alone)

        val stale = Renderer.error("broken", html)
        assertTrue(stale.startsWith("""<div class="scroll"><div class="banner error" role="alert">"""))
        assertTrue("""<main class="stale">""" in stale)
        assertEquals(cards(html).size, cards(stale).size)
    }

    @Test
    fun `the page follows the theme, the font size and the fonts of the IDE`() {
        val dark = Renderer.page("", Renderer.Style(dark = true))
        val light = Renderer.page("", Renderer.Style(dark = false, fontSize = 16, uiFont = "Inter", monoFont = "JetBrains Mono"))
        assertTrue("color-scheme:dark" in dark && "--bg:#1e1f22;" in dark && "--run:#0aac23;" in dark)
        assertTrue("color-scheme:light" in light && "--bg:#ffffff;" in light && "--run:#008a17;" in light)
        assertTrue("--scale:1.0;font-size:13px" in dark)
        assertTrue("--scale:${16 / 13.0};font-size:16px" in light)
        assertTrue("--ui:'Inter',system-ui" in light && "--mono:'JetBrains Mono','JetBrains Mono',Consolas" in light)
        // a font name cannot break out of the style sheet
        val hostile = Renderer.page("", Renderer.Style(dark = true, uiFont = "x';}</style><script>alert(1)</script>"))
        assertFalse("<script>alert" in hostile)
    }

    @Test
    fun `navigation is offered only when the IDE provides it`() {
        val bridge = "window.openJob = function(name) { send(name) };"
        assertTrue("<script>$bridge</script>" in Renderer.page(html, style, bridge))
        assertTrue("<script></script>" in Renderer.page(html, style))
        assertTrue("classList.toggle('no-open', !window.openJob)" in Renderer.page(html, style))
    }

    /** Renders a real file without an IDE: set GITLAB_CI_FILE (and optionally GITLAB_CI_OUT, GITLAB_CI_BRANCH, GITLAB_CI_THEME). */
    @Test
    fun `renders the file from the environment`() {
        val path = System.getenv("GITLAB_CI_FILE")
        assumeNotNull(path)
        val file = File(path).absoluteFile
        val root = generateSequence(file.parentFile) { it.parentFile }.firstOrNull { File(it, ".git").exists() } ?: file.parentFile
        val simulated = Pipeline.load(file.readText()) { File(root, it).takeIf(File::isFile)?.readText() }
            .simulate(predefinedVariables("push", System.getenv("GITLAB_CI_BRANCH") ?: "main", "", "main"))
        val page = Renderer.render(simulated, Renderer.Style(dark = System.getenv("GITLAB_CI_THEME") != "light"), showSkipped = true)
        File(System.getenv("GITLAB_CI_OUT") ?: "build/pipeline.html").writeText(page)
        println("${simulated.jobs.count { it.included }} of ${simulated.jobs.size} jobs, notes: ${simulated.notes}")
    }
}
