# GitLab CI Visualizer

A plugin for JetBrains IDEs (built and tested against WebStorm 2026.2) that shows a `.gitlab-ci.yml` as a pipeline
graph next to the editor.

- **Pipeline view**: one column per stage, one card per job, updated while you type.
- **Dependencies**: `needs` are solid lines, `dependencies` dashed ones. Hover a job to highlight everything it
  depends on and everything that depends on it, and to see its details.
- **Simulation**: choose the pipeline source, branch, tag, default branch and extra variables
  (`KEY=value OTHER="with spaces"`). Jobs that would not be part of that pipeline are faded, and the tooltip names
  the rule that decided it. `workflow:rules` and broken `needs` are reported too.

## Build and install

    ./gradlew buildPlugin

Then install `build/distributions/gitlab-ci-visualizer-*.zip` with *Settings → Plugins → ⚙ → Install Plugin from
Disk*. `./gradlew runIde` starts a sandbox IDE with the plugin instead.

The build needs JDK 25 (the IDE's own runtime in `<IDE>/jbr` works as `JAVA_HOME`). It downloads WebStorm unless
you point it at an installed IDE with `-PlocalIdePath=<IDE install dir>`.

`./gradlew test` runs the tests. To render a file without an IDE:

    GITLAB_CI_FILE=path/to/.gitlab-ci.yml GITLAB_CI_OUT=pipeline.html ./gradlew test --rerun

## What is evaluated

`stages`, `extends`, `!reference`, `include: local`, `rules` (`if`, `when`, `needs`), `only`/`except` (refs and
variables), `workflow:rules`, `needs`, `dependencies`, `when`, `parallel`, `trigger`.

Not evaluated:

- `include` of `project`, `remote`, `template`, `component` and wildcards: listed as a note, their jobs are missing.
- `changes` and `exists`: one checkbox decides whether such rules count as matching.
- Variables inside variable values are not expanded; `spec:inputs` are not interpolated.
- Only files named `*.gitlab-ci.yml` get the view, included fragments do not.
