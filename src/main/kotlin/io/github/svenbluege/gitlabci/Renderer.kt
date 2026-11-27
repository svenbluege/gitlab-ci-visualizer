package io.github.svenbluege.gitlabci

/**
 * Draws a simulated pipeline as HTML: a lane per stage, a card per job, a line per dependency and a details panel.
 * A job's state is never shown by color alone: every state has its own icon shape and a text label.
 */
object Renderer {
    class Style(val dark: Boolean, val fontSize: Int = 13, val uiFont: String = "", val monoFont: String = "")

    /** Colors by role as light to dark. RendererTest checks their contrast. */
    val PALETTE: Map<String, Pair<String, String>> = linkedMapOf(
        "bg" to ("#ffffff" to "#1e1f22"),
        "lane" to ("#f2f3f5" to "#26282c"),
        "card" to ("#ffffff" to "#2f3136"),
        "border" to ("#c3c7cf" to "#4d5058"),
        "text" to ("#1f2328" to "#dfe1e5"),
        "muted" to ("#59606c" to "#a6aab3"),
        "edge" to ("#737b87" to "#858a94"),
        "run" to ("#008a17" to "#0aac23"),
        "manual" to ("#6a4ce2" to "#9887ef"),
        "skipped" to ("#6b7280" to "#8d929c"),
        "problem" to ("#dc3c3d" to "#eb6263"),
        "accent" to ("#257de0" to "#338ced"),
        "warning" to ("#a86400" to "#fbb315"),
    )

    // Geometry for 13px monospace text; the page scales the drawing to the IDE's font size.
    private const val CHAR = 7.9
    private const val PAD = 12
    private const val LANE_PAD = 10
    private const val HEADER = 32
    private const val CARD = 30
    private const val ROW = 40
    private const val GAP = 44
    private const val MAX_NAME = 48

    private class Box(val x: Int, val y: Int, val width: Int)
    private class Column(val title: String, val label: String, val jobs: List<JobResult>)

    fun render(result: PipelineResult, style: Style, showSkipped: Boolean, bridge: String = ""): String =
        page(graph(result, showSkipped), style, bridge)

    /**
     * The page content for a pipeline; wrap it with [page]. Columns are stages, or with [byDependencies] the levels
     * of GitLab's "Job dependencies" view. Within a column jobs are ordered by name, as GitLab orders them.
     */
    fun graph(result: PipelineResult, showSkipped: Boolean, byDependencies: Boolean = false): String {
        val jobs = result.jobs.filter { showSkipped || it.included }
        val columns = if (byDependencies) levels(result, jobs) else result.stages.mapNotNull { stage ->
            jobs.filter { it.stage == stage }.sortedWith { a, b -> gitLabOrder(a.name, b.name) }
                .takeIf { it.isNotEmpty() }?.let { Column(stage, "Stage $stage", it) }
        }
        val rows = columns.maxOfOrNull { it.jobs.size } ?: 0
        val laneHeight = HEADER + rows * ROW - (ROW - CARD) + LANE_PAD
        val boxes = HashMap<String, Box>()
        val lanes = StringBuilder()
        val cards = StringBuilder()
        var x = PAD
        columns.forEachIndexed { column, lane ->
            val stage = lane.title
            val stageJobs = lane.jobs
            val longest = stageJobs.maxOf { label(it).length + badge(it).let { badge -> if (badge.isEmpty()) 0 else badge.length + 2 } }
            val content = maxOf(((stage.length + 6) * CHAR).toInt(), (longest * CHAR).toInt() + 44)
            val width = content + 2 * LANE_PAD
            val running = stageJobs.count { it.included }
            lanes.append("""<rect class="lane" x="$x" y="$PAD" width="$width" height="$laneHeight" rx="10"/>""")
            lanes.append("""<text class="stage" x="${x + LANE_PAD + 2}" y="${PAD + 21}">${esc(stage)}</text>""")
            lanes.append("""<text class="count" x="${x + width - LANE_PAD - 2}" y="${PAD + 21}" text-anchor="end">$running/${stageJobs.size}</text>""")
            cards.append("""<g role="group" aria-label="${esc("${lane.label}: $running of ${stageJobs.size} jobs run")}">""")
            stageJobs.forEachIndexed { row, job ->
                val box = Box(x + LANE_PAD, PAD + HEADER + row * ROW, content).also { boxes[job.name] = it }
                // The tab stop starts on the top left card, the first one in reading order.
                cards.append(card(job, "j${jobs.indexOf(job)}", box, column, row, focusable = column == 0 && row == 0))
            }
            cards.append("</g>")
            x += width + GAP
        }

        val all = result.jobs.associateBy { it.name }
        val edges = StringBuilder()
        for (job in jobs) {
            val to = boxes.getValue(job.name)
            // A job listed in both needs and dependencies gets one line, drawn as needs.
            val links = (job.needs.orEmpty().map { it.job to "needs" } + job.dependencies.map { it to "deps" }).distinctBy { it.first }
            for ((name, kind) in links) {
                val from = boxes[name] ?: continue
                val sx = from.x + from.width
                val sy = from.y + CARD / 2
                val ty = to.y + CARD / 2
                val path = if (to.x > from.x) {
                    val curve = (to.x - sx) / 2
                    "M$sx,$sy C${sx + curve},$sy ${to.x - curve},$ty ${to.x},$ty"
                } else { // same stage: loop around the right side
                    val tx = to.x + to.width
                    val bulge = maxOf(sx, tx) + 34
                    "M$sx,$sy C$bulge,$sy $bulge,$ty $tx,$ty"
                }
                val off = if (job.included && all.getValue(name).included) "" else " off"
                edges.append("""<path class="edge $kind$off" data-from="${esc(name)}" data-to="${esc(job.name)}" d="$path"/>""")
            }
        }

        val html = StringBuilder("""<div class="scroll">""")
        result.blocked?.let { html.append(banner("error", "No pipeline is created for these conditions", it)) }
        result.notes.forEach { html.append(banner("note", "", it)) }
        html.append("<main>").append(summary(result, zoomable = jobs.isNotEmpty()))
        when {
            result.jobs.isEmpty() ->
                html.append("""<p class="empty">No jobs found. Jobs are the top-level keys that do not start with a dot.</p>""")
            jobs.isEmpty() ->
                html.append("""<p class="empty">No job runs in this simulation. Turn on “Show skipped jobs” to see all ${result.jobs.size} jobs.</p>""")
            else -> {
                // The last lane keeps room on its right: lines between its jobs loop around that side.
                val label = "Pipeline graph: ${jobs.size} jobs in ${columns.size} ${if (byDependencies) "dependency levels" else "stages"}. " +
                    "Arrow keys move between jobs, Enter opens the job definition, Escape clears the selection."
                html.append("""<svg class="graph" width="${x - GAP + PAD + 28}" height="${PAD + laneHeight + PAD}" role="group" aria-label="$label">""")
                html.append("""<g aria-hidden="true">$lanes</g><g class="edges" aria-hidden="true">$edges</g>$cards<g class="above" aria-hidden="true"></g></svg>""")
            }
        }
        if (result.variables.isNotEmpty()) {
            html.append("""<details class="vars"><summary>Variables in this simulation (${result.variables.size})</summary><dl>""")
            result.variables.toSortedMap().forEach { (name, value) -> html.append("<dt>${esc(name)}</dt><dd>${esc(value)}</dd>") }
            html.append("</dl></details>")
        }
        html.append("""</main></div><aside aria-label="Job details">""")
        html.append("""<p class="hint">Select a job to see why it runs or is skipped. With the keyboard: arrow keys move between jobs, """)
        html.append("""Enter opens the definition, Escape clears the selection.</p>""")
        val neededBy = result.jobs.flatMap { job -> job.needs.orEmpty().map { it.job to job.name } }.groupBy({ it.first }, { it.second })
        jobs.forEachIndexed { index, job -> html.append(details(job, "j$index", all, boxes.keys, neededBy[job.name].orEmpty())) }
        return html.append("</aside>").toString()
    }

    /**
     * GitLab's "Job dependencies" view: a job sits one column right of the last job it waits for, and without `needs`
     * it waits for the jobs of the previous stage. Only the shown jobs count, so with skipped jobs hidden the columns
     * are GitLab's, and with skipped jobs shown every line still points to the right.
     */
    private fun levels(result: PipelineResult, jobs: List<JobResult>): List<Column> {
        val byName = jobs.associateBy { it.name }
        val level = HashMap<String, Int>()
        fun levelOf(job: JobResult, path: Set<String>): Int = level.getOrPut(job.name) {
            val before = job.needs?.mapNotNull { byName[it.job] }
                ?: result.stages.take(result.stages.indexOf(job.stage)).asReversed()
                    .firstNotNullOfOrNull { stage -> jobs.filter { it.stage == stage }.ifEmpty { null } }.orEmpty()
            // The path guards against jobs that need each other in a circle.
            before.filter { it.name !in path }.maxOfOrNull { levelOf(it, path + job.name) + 1 } ?: 0
        }
        return jobs.groupBy { levelOf(it, emptySet()) }.toSortedMap().values.mapIndexed { index, column ->
            val sorted = column.sortedWith(compareBy<JobResult> { result.stages.indexOf(it.stage) }.thenComparing { a, b -> gitLabOrder(a.name, b.name) })
            val stages = sorted.map { it.stage }.distinct().joinToString(" · ")
            Column(stages, "Dependency level ${index + 1}, stage $stages", sorted)
        }
    }

    /** A message instead of the graph, e.g. for invalid YAML. [stale] is the last graph, kept visible while typing. */
    fun error(message: String, stale: String? = null): String {
        val banner = banner("error", "This file cannot be read as a pipeline", message)
        return stale?.replaceFirst("""<div class="scroll">""", """<div class="scroll">$banner""")?.replaceFirst("<main>", """<main class="stale">""")
            ?: """<div class="scroll">$banner</div>"""
    }

    /** [bridge] is JavaScript that defines `window.openJob(name)`; without it the page offers no navigation. */
    fun page(body: String, style: Style, bridge: String = ""): String {
        val colors = PALETTE.entries.joinToString("") { (role, color) -> "--$role:${if (style.dark) color.second else color.first};" }
        fun font(name: String) = name.filter { it.isLetterOrDigit() || it in " -_" }.ifEmpty { "x" }
        val root = ":root{color-scheme:${if (style.dark) "dark" else "light"};$colors" +
            "--ui:'${font(style.uiFont)}',system-ui,'Segoe UI',sans-serif;" +
            "--mono:'${font(style.monoFont)}','JetBrains Mono',Consolas,Menlo,'DejaVu Sans Mono',monospace;" +
            "--scale:${style.fontSize / 13.0};font-size:${style.fontSize}px}"
        return """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>Pipeline graph</title><style>$root$STYLE</style></head>""" +
            """<body>$ICONS$body<div id="tip" aria-hidden="true"></div><script>$bridge</script><script>$SCRIPT</script></body></html>"""
    }

    private fun card(job: JobResult, id: String, box: Box, column: Int, row: Int, focusable: Boolean): String {
        val classes = listOfNotNull("job", kind(job), "off".takeIf { !job.included }).joinToString(" ")
        val spoken = (listOf(job.name, "stage ${job.stage}", "${status(job)}: ${job.reason}") + job.problems.map { "problem: $it" }).joinToString(", ")
        val tip = "${job.name}\n${status(job)} — ${job.reason}" + job.problems.joinToString("") { "\n⚠ $it" }
        return """<g class="$classes" id="$id" role="button" tabindex="${if (focusable) 0 else -1}" aria-pressed="false" """ +
            """aria-label="${esc(spoken)}" data-name="${esc(job.name)}" data-col="$column" data-row="$row" data-tip="${esc(tip)}">""" +
            """<rect class="ring" x="${box.x - 3}" y="${box.y - 3}" width="${box.width + 6}" height="${CARD + 6}" rx="10"/>""" +
            """<rect class="card" x="${box.x}" y="${box.y}" width="${box.width}" height="$CARD" rx="7"/>""" +
            """<use class="icon" href="#i-${kind(job)}" x="${box.x + 8}" y="${box.y + 7}" width="16" height="16"/>""" +
            """<text class="name" x="${box.x + 32}" y="${box.y + 20}">${esc(label(job))}</text>""" +
            """<text class="badge" x="${box.x + box.width - 10}" y="${box.y + 20}" text-anchor="end">${esc(badge(job))}</text></g>"""
    }

    private fun details(job: JobResult, id: String, all: Map<String, JobResult>, shown: Set<String>, neededBy: List<String>): String {
        fun ref(name: String) =
            if (name in shown) """<button type="button" class="ref" data-ref="${esc(name)}">${esc(name)}</button>"""
            else """<span class="mono">${esc(name)}</span>"""
        fun state(name: String) = when {
            all[name] == null -> " (unknown)"
            !all.getValue(name).included -> " (skipped)"
            else -> ""
        }
        val needs = job.needs
        val rows = listOfNotNull(
            "Status" to icon(kind(job)) + esc(explain(job)),
            "Why" to esc(job.reason),
            "Stage" to esc(job.stage),
            "Needs" to when {
                needs == null -> "All jobs of earlier stages (stage order)"
                needs.isEmpty() -> "Nothing: it starts as soon as the pipeline starts"
                else -> needs.joinToString(", ") { ref(it.job) + (if (it.optional) " (optional)" else "") + state(it.job) }
            },
            neededBy.takeIf { it.isNotEmpty() }?.let { "Needed by" to it.joinToString(", ", transform = ::ref) },
            job.dependencies.takeIf { it.isNotEmpty() }?.let { "Artifacts from" to it.joinToString(", ") { name -> ref(name) + state(name) } },
            job.config["extends"]?.let { "Extends" to esc((it as? List<*>)?.joinToString() ?: it.toString()) },
            job.config["environment"]?.let { "Environment" to esc(((it as? Map<*, *>)?.get("name") ?: it).toString()) },
            parallelCount(job.config["parallel"]).takeIf { it > 1 }?.let { "Parallel" to "$it jobs" },
            job.config["trigger"]?.let { "Triggers" to esc(((it as? Map<*, *>)?.get("project") ?: (it as? String) ?: "a child pipeline").toString()) },
            job.source?.let { "Defined in" to esc(it) },
            job.problems.takeIf { it.isNotEmpty() }?.let { problems ->
                "Problems" to """<ul class="problems">${problems.joinToString("") { "<li>${esc(it)}</li>" }}</ul>"""
            },
        )
        return """<section class="detail" data-for="$id" hidden><h2>${icon(kind(job))}<span>${esc(job.name)}</span>""" +
            """<button type="button" class="goto" data-name="${esc(job.name)}">Go to definition</button></h2><dl>""" +
            rows.joinToString("") { (term, value) -> "<dt>$term</dt><dd>$value</dd>" } + "</dl></section>"
    }

    private fun summary(result: PipelineResult, zoomable: Boolean): String {
        val counts = result.jobs.groupingBy(::kind).eachCount()
        val chips = listOf("run" to "run", "manual" to "manual", "delayed" to "delayed", "skipped" to "skipped", "problem" to "with problems")
            .filter { it.first in counts }
            .joinToString("") { (kind, text) -> """<li class="chip">${icon(kind)}<b>${counts[kind]}</b> $text</li>""" }
        fun key(kind: String, text: String) =
            """<span class="key"><svg width="34" height="10" aria-hidden="true"><path class="edge $kind" d="M1,5 H27"/></svg>$text</span>"""
        val zoom = if (!zoomable) "" else """<span class="zoom" role="group" aria-label="Zoom">""" +
            """<button type="button" data-zoom="-0.1" aria-label="Zoom out" title="Zoom out (-)">−</button>""" +
            """<output id="zoom" aria-label="Zoom level">100%</output>""" +
            """<button type="button" data-zoom="0.1" aria-label="Zoom in" title="Zoom in (+)">+</button>""" +
            """<button type="button" data-zoom="fit" title="Fit the pipeline to the width of the view (0)">Fit</button></span>"""
        return """<header class="summary"><ul class="chips" aria-label="Jobs in this simulation">$chips</ul>""" +
            key("needs", "needs") + key("deps", "dependencies") + zoom + "</header>"
    }

    private fun banner(kind: String, title: String, text: String): String {
        val icon = if (kind == "error") icon("problem") else icon("warning", "problem")
        val heading = if (title.isEmpty()) "" else "<strong>${esc(title)}</strong>"
        return """<div class="banner $kind" role="${if (kind == "error") "alert" else "note"}">$icon<div>$heading${esc(text)}</div></div>"""
    }

    private fun icon(kind: String, symbol: String = kind) =
        """<svg class="icon $kind" width="16" height="16" aria-hidden="true"><use href="#i-$symbol"/></svg>"""

    /** Picks icon and color. Problems come first: they need attention whether or not the job runs. */
    private fun kind(job: JobResult): String = when {
        job.problems.isNotEmpty() -> "problem"
        !job.included -> "skipped"
        job.whenValue == "manual" || job.whenValue == "delayed" -> job.whenValue
        else -> "run"
    }

    private fun status(job: JobResult): String = when {
        !job.included -> "Skipped"
        job.whenValue == "manual" -> "Manual"
        job.whenValue == "delayed" -> "Delayed"
        else -> "Runs"
    }

    private fun explain(job: JobResult): String = when {
        !job.included -> "Skipped: not part of this pipeline"
        job.whenValue == "manual" -> "Manual: waits until someone starts it"
        job.whenValue == "delayed" -> "Delayed: starts after ${job.config["start_in"] ?: "a delay"}"
        job.whenValue == "always" -> "Runs always, even if an earlier job fails"
        job.whenValue == "on_failure" -> "Runs only if an earlier job fails"
        else -> "Runs"
    }

    private fun label(job: JobResult) = if (job.name.length > MAX_NAME) job.name.take(MAX_NAME - 1) + "…" else job.name

    private fun badge(job: JobResult): String = listOfNotNull(
        job.whenValue.takeIf { job.included && it != "on_success" },
        "trigger".takeIf { job.config["trigger"] != null },
        parallelCount(job.config["parallel"]).takeIf { it > 1 }?.let { "×$it" },
    ).joinToString(" ")

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("\n", "&#10;")

    // One shape per state, so the states differ without color. The inner strokes take the card color.
    private val ICONS = """
        <svg class="defs" width="0" height="0" aria-hidden="true"><defs>
        <marker id="arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0.5 L7.5,4 L0,7.5 z" fill="context-stroke"/></marker>
        <symbol id="i-run" viewBox="0 0 16 16"><circle cx="8" cy="8" r="7.5" fill="currentColor"/><path d="M4.6 8.3l2.3 2.3 4.5-4.8" fill="none" stroke="var(--card)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></symbol>
        <symbol id="i-manual" viewBox="0 0 16 16"><circle cx="8" cy="8" r="7.5" fill="currentColor"/><path d="M6.3 4.7v6.6l5.3-3.3z" fill="var(--card)"/></symbol>
        <symbol id="i-delayed" viewBox="0 0 16 16"><circle cx="8" cy="8" r="7.5" fill="currentColor"/><path d="M8 4.2V8l2.7 1.8" fill="none" stroke="var(--card)" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></symbol>
        <symbol id="i-skipped" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6.6" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M3.5 12.5l9-9" stroke="currentColor" stroke-width="1.8"/></symbol>
        <symbol id="i-problem" viewBox="0 0 16 16"><path d="M8 1.6l6.9 12.4H1.1z" fill="currentColor" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/><path d="M8 6.2v3.9" stroke="var(--card)" stroke-width="1.8" stroke-linecap="round"/><circle cx="8" cy="12.3" r="1" fill="var(--card)"/></symbol>
        </defs></svg>
    """.trimIndent().replace("\n", "")

    private val STYLE = """
        body { margin:0; height:100vh; display:flex; flex-direction:column; background:var(--bg); color:var(--text); font-family:var(--ui); font-size:1rem; line-height:1.45; }
        .defs { position:absolute; }
        .scroll { flex:1; overflow:auto; padding:.75rem; }
        .mono, .detail h2, .vars dl { font-family:var(--mono); }
        .icon { flex:none; }
        .icon.run { color:var(--run); } .icon.manual, .icon.delayed { color:var(--manual); } .icon.skipped { color:var(--skipped); }
        .icon.problem { color:var(--problem); } .icon.warning { color:var(--warning); }
        .banner { display:flex; gap:.55rem; max-width:62rem; margin:0 0 .6rem; padding:.5rem .7rem; border:1px solid var(--border); border-left:4px solid var(--warning); border-radius:6px; background:var(--lane); white-space:pre-wrap; overflow-wrap:anywhere; }
        .banner.error { border-left-color:var(--problem); }
        .banner .icon { margin-top:.15rem; }
        .banner strong { display:block; }
        .summary { display:flex; flex-wrap:wrap; align-items:center; gap:.45rem 1rem; margin:0 0 .7rem; }
        .chips { display:flex; flex-wrap:wrap; gap:.4rem; margin:0; padding:0; list-style:none; }
        .chip { display:inline-flex; align-items:center; gap:.35rem; padding:.15rem .65rem .15rem .4rem; border:1px solid var(--border); border-radius:999px; background:var(--lane); }
        .key { display:inline-flex; align-items:center; gap:.3rem; color:var(--muted); }
        .zoom { display:inline-flex; align-items:center; gap:.25rem; margin-left:auto; }
        .zoom button { min-width:1.85rem; padding:0 .5rem; }
        .zoom output { min-width:3rem; text-align:center; color:var(--muted); }
        .empty { color:var(--muted); }
        .stale { opacity:.5; }
        svg.graph { display:block; zoom:var(--scale); font-family:var(--mono); font-size:13px; }
        .lane { fill:var(--lane); }
        .stage, .count { fill:var(--muted); font-size:11px; }
        .stage { font-weight:700; letter-spacing:.06em; text-transform:uppercase; }
        .job { cursor:pointer; outline:none; }
        .ring { fill:none; stroke:none; }
        .job:focus-visible .ring { stroke:var(--accent); stroke-width:2; }
        .card { fill:var(--card); stroke:var(--border); }
        .name { fill:var(--text); }
        .badge { fill:var(--muted); font-size:12px; }
        .job.run .icon { color:var(--run); } .job.manual .icon, .job.delayed .icon { color:var(--manual); }
        .job.skipped .icon { color:var(--skipped); } .job.problem .icon { color:var(--problem); }
        .job.run .card { fill:color-mix(in srgb, var(--run) 9%, var(--card)); stroke:color-mix(in srgb, var(--run) 50%, var(--border)); }
        .job.manual .card, .job.delayed .card { fill:color-mix(in srgb, var(--manual) 9%, var(--card)); stroke:color-mix(in srgb, var(--manual) 50%, var(--border)); }
        .job.problem .card { fill:color-mix(in srgb, var(--problem) 9%, var(--card)); stroke:var(--problem); }
        .job.off .card { stroke-dasharray:4 3; }
        .job.off .name { fill:var(--muted); }
        .job:hover .card, .job.hl .card { stroke:var(--accent); stroke-width:1.5; }
        .job.selected .card { fill:color-mix(in srgb, var(--accent) 16%, var(--card)); stroke:var(--accent); stroke-width:2.5; stroke-dasharray:none; }
        .edge { fill:none; stroke:var(--edge); stroke-width:1.5; marker-end:url(#arrow); pointer-events:none; }
        .edge.deps { stroke-dasharray:5 4; }
        .edge.off { opacity:.45; }
        svg.dim .edge:not(.hl) { opacity:.18; }
        .edge.hl { stroke:var(--accent); stroke-width:2.25; opacity:1; }
        .edge.off.hl { opacity:.55; }
        .vars { margin-top:.9rem; color:var(--muted); }
        .vars summary { cursor:pointer; width:max-content; }
        .vars dl, .detail dl { display:grid; grid-template-columns:max-content 1fr; gap:.2rem 1rem; margin:.4rem 0 0; }
        .vars dd, .detail dd { margin:0; color:var(--text); overflow-wrap:anywhere; }
        aside { flex:none; max-height:45vh; overflow:auto; padding:.6rem .75rem; border-top:1px solid var(--border); background:var(--lane); }
        .hint { margin:0; color:var(--muted); }
        .detail h2 { display:flex; align-items:center; gap:.5rem; margin:0; font-size:1.05rem; overflow-wrap:anywhere; }
        .detail dt { color:var(--muted); }
        .detail dd .icon { margin-right:.4rem; vertical-align:-.2em; }
        .problems { margin:0; padding-left:1.1rem; }
        button { min-height:1.85rem; font:inherit; color:var(--text); cursor:pointer; border:1px solid var(--border); border-radius:5px; background:var(--card); }
        button:hover { border-color:var(--accent); }
        button:focus-visible, summary:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
        .ref { padding:0 .4rem; font-family:var(--mono); }
        .goto { margin-left:auto; padding:.15rem .7rem; white-space:nowrap; font:1rem var(--ui); }
        .no-open .goto { display:none; }
        #tip { display:none; position:fixed; max-width:34rem; padding:.4rem .6rem; white-space:pre-wrap; overflow-wrap:anywhere; pointer-events:none;
               background:var(--card); border:1px solid var(--border); border-radius:6px; box-shadow:0 3px 10px rgba(0,0,0,.3); }
    """.trimIndent()

    // The selection and the scroll position live in window.name: it survives the reload after every edit.
    private val SCRIPT = """
        const scroller = document.querySelector('.scroll'), svg = document.querySelector('svg.graph');
        const tip = document.getElementById('tip'), hint = document.querySelector('.hint');
        const jobs = [...document.querySelectorAll('.job')], edges = [...document.querySelectorAll('.edge[data-from]')];
        const byName = new Map(jobs.map(job => [job.dataset.name, job]));
        const below = document.querySelector('.edges'), above = document.querySelector('.above');
        let state = {}, selected = null;
        try { state = JSON.parse(window.name) || {}; } catch (ignored) {}
        const save = () => { window.name = JSON.stringify(state); };

        function walk(name, from, to, seen) {
          for (const edge of edges) {
            if (edge.dataset[from] !== name || seen.has(edge)) continue;
            seen.add(edge);
            edge.classList.add('hl');
            byName.get(edge.dataset[to]).classList.add('hl');
            walk(edge.dataset[to], from, to, seen);
          }
        }
        // Marks a job, everything it waits for and everything waiting for it. Those lines move above the cards.
        function highlight(job) {
          if (!svg) return;
          svg.querySelectorAll('.hl').forEach(node => node.classList.remove('hl'));
          below.append(...edges);
          svg.classList.toggle('dim', !!job);
          if (!job) return;
          job.classList.add('hl');
          walk(job.dataset.name, 'from', 'to', new Set());
          walk(job.dataset.name, 'to', 'from', new Set());
          above.append(...edges.filter(edge => edge.classList.contains('hl')));
        }
        function select(job) {
          if (selected) { selected.classList.remove('selected'); selected.setAttribute('aria-pressed', 'false'); }
          selected = job;
          if (job) { job.classList.add('selected'); job.setAttribute('aria-pressed', 'true'); }
          document.querySelectorAll('.detail').forEach(detail => { detail.hidden = !job || detail.dataset.for !== job.id; });
          if (hint) hint.hidden = !!job;
          highlight(job);
          state.selected = job ? job.dataset.name : null;
          save();
        }
        function tabStop(job) { jobs.forEach(other => other.setAttribute('tabindex', other === job ? '0' : '-1')); }
        function open(name) { if (window.openJob) window.openJob(name); }
        function move(job, columns, rows) {
          const column = jobs.filter(other => +other.dataset.col === +job.dataset.col + columns);
          const target = column[Math.max(0, Math.min(+job.dataset.row + rows, column.length - 1))];
          if (target) target.focus();
        }
        // Without a chosen zoom the pipeline shrinks to the width of the view, but not below a readable size.
        const scale = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--scale')) || 1;
        const fit = () => Math.max(0.7, Math.min(1, (scroller.clientWidth - 28) / (svg.width.baseVal.value * scale)));
        function zoom(change) {
          if (!svg) return;
          if (change === 'fit') state.zoom = null;
          else if (change) state.zoom = Math.max(0.5, Math.min(2, Math.round(((state.zoom || fit()) + change) * 10) / 10));
          const factor = state.zoom || fit();
          svg.style.zoom = scale * factor;
          document.getElementById('zoom').textContent = Math.round(factor * 100) + '%';
          save();
        }
        const steps = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1], Home: [0, -999], End: [0, 999] };
        for (const job of jobs) {
          job.addEventListener('focus', () => { tabStop(job); select(job); });
          job.addEventListener('click', () => select(job));
          job.addEventListener('dblclick', () => open(job.dataset.name));
          job.addEventListener('keydown', event => {
            if (steps[event.key]) move(job, ...steps[event.key]);
            else if (event.key === 'Enter') open(job.dataset.name);
            else if (event.key === 'Escape') select(null);
            else if (event.key === '+' || event.key === '=') zoom(0.1);
            else if (event.key === '-') zoom(-0.1);
            else if (event.key === '0') zoom('fit');
            else if (event.key !== ' ') return;
            event.preventDefault();
          });
          job.addEventListener('mouseenter', () => { highlight(job); tip.textContent = job.dataset.tip; tip.style.display = 'block'; });
          job.addEventListener('mousemove', event => {
            const fits = event.clientY + 18 + tip.offsetHeight < innerHeight;
            tip.style.left = Math.max(4, Math.min(event.clientX + 14, innerWidth - tip.offsetWidth - 8)) + 'px';
            tip.style.top = (fits ? event.clientY + 18 : Math.max(4, event.clientY - tip.offsetHeight - 10)) + 'px';
          });
          job.addEventListener('mouseleave', () => { highlight(selected); tip.style.display = 'none'; });
        }
        document.querySelectorAll('.ref').forEach(button => button.addEventListener('click', () => byName.get(button.dataset.ref).focus()));
        document.querySelectorAll('.goto').forEach(button => button.addEventListener('click', () => open(button.dataset.name)));
        document.querySelectorAll('[data-zoom]').forEach(button =>
          button.addEventListener('click', () => zoom(button.dataset.zoom === 'fit' ? 'fit' : +button.dataset.zoom)));
        addEventListener('resize', () => zoom());
        zoom();
        document.body.classList.toggle('no-open', !window.openJob);
        if (scroller) {
          scroller.addEventListener('click', event => { if (!event.target.closest('.job')) select(null); });
          // Restored without focusing: the reload happens while the user types in the editor.
          if (byName.has(state.selected)) { select(byName.get(state.selected)); tabStop(selected); }
          scroller.scrollTo(state.x || 0, state.y || 0);
          scroller.addEventListener('scroll', () => { state.x = scroller.scrollLeft; state.y = scroller.scrollTop; save(); });
        }
    """.trimIndent()
}

/** GitLab's order of job names: text by character, numbers by value, so `test 2` comes before `test 10`. */
fun gitLabOrder(a: String, b: String): Int {
    val chunks = Regex("""\d+|\D+""")
    val left = chunks.findAll(a).map { it.value }.toList()
    val right = chunks.findAll(b).map { it.value }.toList()
    for (index in 0 until minOf(left.size, right.size)) {
        val (x, y) = left[index] to right[index]
        val order = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
        if (order != 0) return order
    }
    return left.size - right.size
}
