package io.github.svenbluege.gitlabci

/** Draws a simulated pipeline as an HTML page: one column per stage, one card per job, one line per dependency. */
object Renderer {
    private const val CHAR_WIDTH = 7.2 // 12px monospace, so widths can be computed without a browser
    private const val PAD = 16
    private const val HEADER = 26
    private const val CARD = 28
    private const val ROW = 38
    private const val GAP = 64

    private class Box(val x: Int, val y: Int, val width: Int)

    fun render(result: PipelineResult, dark: Boolean, showSkipped: Boolean): String {
        val jobs = result.jobs.filter { showSkipped || it.included }
        val columns = result.stages.map { stage -> stage to jobs.filter { it.stage == stage } }.filter { it.second.isNotEmpty() }
        val boxes = HashMap<String, Box>()
        val cards = StringBuilder()
        var x = PAD
        for ((stage, stageJobs) in columns) {
            val width = (maxOf(stage.length, stageJobs.maxOf { it.name.length + badge(it).length + 2 }) * CHAR_WIDTH).toInt() + 24
            cards.append("""<text class="stage" x="$x" y="${PAD + 12}">${esc(stage)}</text>""")
            stageJobs.forEachIndexed { row, job ->
                val box = Box(x, PAD + HEADER + row * ROW, width).also { boxes[job.name] = it }
                cards.append(card(job, box))
            }
            x += width + GAP
        }

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
                    val bulge = maxOf(sx, tx) + 36
                    "M$sx,$sy C$bulge,$sy $bulge,$ty $tx,$ty"
                }
                val off = if (job.included && result.jobs.first { it.name == name }.included) "" else " off"
                edges.append("""<path class="edge $kind$off" data-from="${esc(name)}" data-to="${esc(job.name)}" d="$path"/>""")
            }
        }

        val body = StringBuilder()
        result.blocked?.let { body.append("""<div class="banner error">No pipeline is created for these conditions — ${esc(it)}</div>""") }
        result.notes.forEach { body.append("""<div class="banner">${esc(it)}</div>""") }
        body.append("""<div class="summary">${result.jobs.count { it.included }} of ${result.jobs.size} jobs run""")
        body.append(""" · solid line: needs · dashed line: dependencies · faded: not in this pipeline</div>""")
        val height = PAD + HEADER + (columns.maxOfOrNull { it.second.size } ?: 0) * ROW + PAD
        // GAP on the right as well: lines between jobs of the last stage loop around its right side.
        body.append("""<svg width="${x + PAD}" height="$height">$edges$cards</svg><div id="tip"></div><script>$SCRIPT</script>""")
        return page(body.toString(), dark)
    }

    fun error(message: String, dark: Boolean): String = page("""<div class="banner error">${esc(message)}</div>""", dark)

    private fun page(body: String, dark: Boolean) =
        """<!DOCTYPE html><html class="${if (dark) "dark" else ""}"><head><meta charset="utf-8"><style>$STYLE</style></head><body>$body</body></html>"""

    private fun card(job: JobResult, box: Box): String {
        val classes = listOfNotNull("job", "off".takeIf { !job.included }, "problem".takeIf { job.problems.isNotEmpty() })
        return """<g class="${classes.joinToString(" ")}" data-id="${esc(job.name)}" data-tip="${esc(tooltip(job))}">""" +
            """<rect x="${box.x}" y="${box.y}" width="${box.width}" height="$CARD" rx="5"/>""" +
            """<text class="name" x="${box.x + 10}" y="${box.y + 18}">${esc(job.name)}</text>""" +
            """<text class="badge" x="${box.x + box.width - 10}" y="${box.y + 18}" text-anchor="end">${esc(badge(job))}</text></g>"""
    }

    private fun badge(job: JobResult): String = listOfNotNull(
        "⚠".takeIf { job.problems.isNotEmpty() },
        job.whenValue.takeIf { job.included && it != "on_success" },
        "trigger".takeIf { job.config["trigger"] != null },
        parallelCount(job.config["parallel"]).takeIf { it > 1 }?.let { "×$it" },
    ).joinToString(" ")

    private fun tooltip(job: JobResult): String = listOfNotNull(
        job.name,
        "stage: ${job.stage}",
        (if (job.included) "runs (when: ${job.whenValue})" else "skipped") + " — ${job.reason}",
        job.needs?.let { needs -> "needs: " + needs.joinToString { it.job + if (it.optional) " (optional)" else "" }.ifEmpty { "[] (starts immediately)" } },
        job.dependencies.takeIf { it.isNotEmpty() }?.let { "dependencies: ${it.joinToString()}" },
        job.config["extends"]?.let { "extends: $it" },
        job.config["environment"]?.let { "environment: ${(it as? Map<*, *>)?.get("name") ?: it}" },
    ).joinToString("\n") + job.problems.joinToString("") { "\n⚠ $it" }

    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("\n", "&#10;")

    private val STYLE = """
        :root { --bg:#ffffff; --fg:#1f2328; --muted:#6b7280; --card:#f3f5f7; --border:#b9c0c9; --edge:#7d8590; --accent:#3574f0; --err:#d1242f; }
        :root.dark { --bg:#1e1f22; --fg:#dfe1e5; --muted:#868a91; --card:#2b2d30; --border:#565a61; --edge:#7a7e85; --accent:#548af7; --err:#f75464; }
        body { margin:0; background:var(--bg); color:var(--fg); font:12px ui-monospace,Consolas,Menlo,monospace; }
        .banner { margin:8px 16px 0; padding:6px 10px; border:1px solid var(--border); border-radius:5px; color:var(--muted); }
        .banner.error { border-color:var(--err); color:var(--err); white-space:pre-wrap; }
        .summary { margin:10px 16px 0; color:var(--muted); }
        svg { display:block; }
        text { fill:var(--fg); }
        .stage { fill:var(--muted); font-weight:bold; text-transform:uppercase; }
        .badge { fill:var(--muted); }
        .job rect { fill:var(--card); stroke:var(--border); }
        .job.problem rect { stroke:var(--err); }
        .job.problem .badge { fill:var(--err); }
        .job.off { opacity:.4; }
        .job.off rect { stroke-dasharray:4 3; }
        .job.off .name { text-decoration:line-through; }
        .edge { fill:none; stroke:var(--edge); stroke-width:1.5; pointer-events:none; }
        .edge.deps { stroke-dasharray:5 4; }
        .edge.off { opacity:.3; }
        svg.dim .job:not(.hl), svg.dim .edge:not(.hl) { opacity:.15; }
        .job.hl rect, .edge.hl { stroke:var(--accent); stroke-width:2; }
        #tip { display:none; position:fixed; max-width:520px; padding:6px 9px; white-space:pre-wrap; word-break:break-word;
               background:var(--card); border:1px solid var(--border); border-radius:5px; box-shadow:0 2px 8px rgba(0,0,0,.3); }
    """.trimIndent()

    // Hovering a job highlights everything it depends on and everything depending on it, and shows its details.
    // window.name survives the reload after each edit, so it carries the scroll position.
    private val SCRIPT = """
        const svg = document.querySelector('svg'), tip = document.getElementById('tip');
        const jobs = new Map([...document.querySelectorAll('.job')].map(job => [job.dataset.id, job]));
        const edges = [...document.querySelectorAll('.edge')];
        function walk(id, from, to, seen) {
          for (const edge of edges) {
            if (edge.dataset[from] !== id || seen.has(edge)) continue;
            seen.add(edge);
            edge.classList.add('hl');
            jobs.get(edge.dataset[to]).classList.add('hl');
            walk(edge.dataset[to], from, to, seen);
          }
        }
        for (const [id, job] of jobs) {
          job.addEventListener('mouseenter', () => {
            svg.classList.add('dim');
            job.classList.add('hl');
            walk(id, 'from', 'to', new Set());
            walk(id, 'to', 'from', new Set());
            svg.append(...svg.querySelectorAll('.edge.hl')); // above the cards, so lines crossing other stages stay visible
            tip.textContent = job.dataset.tip;
            tip.style.display = 'block';
          });
          job.addEventListener('mousemove', event => {
            const below = event.clientY + 16 + tip.offsetHeight < innerHeight;
            tip.style.left = Math.max(4, Math.min(event.clientX + 14, innerWidth - tip.offsetWidth - 8)) + 'px';
            tip.style.top = (below ? event.clientY + 16 : Math.max(4, event.clientY - tip.offsetHeight - 10)) + 'px';
          });
          job.addEventListener('mouseleave', () => {
            svg.classList.remove('dim');
            svg.prepend(...svg.querySelectorAll('.edge.hl'));
            document.querySelectorAll('.hl').forEach(node => node.classList.remove('hl'));
            tip.style.display = 'none';
          });
        }
        const [left, top] = (window.name || '0,0').split(',');
        scrollTo(+left || 0, +top || 0);
        addEventListener('scroll', () => { window.name = scrollX + ',' + scrollY; });
    """.trimIndent()
}
