package io.github.svenbluege.gitlabci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpressionTest {
    private val variables = mapOf(
        "CI_COMMIT_BRANCH" to "feature/login",
        "CI_DEFAULT_BRANCH" to "main",
        "CI_PIPELINE_SOURCE" to "push",
        "EMPTY" to "",
        "PATTERN" to "/^FEATURE/i",
    )

    @Test
    fun `evaluates GitLab rule expressions`() {
        val cases = mapOf(
            "\$CI_COMMIT_BRANCH" to true,
            "\$UNDEFINED" to false,
            "\$EMPTY" to false,
            "\$CI_PIPELINE_SOURCE == \"push\"" to true,
            "\$CI_PIPELINE_SOURCE == 'schedule'" to false,
            "\$CI_COMMIT_BRANCH != \$CI_DEFAULT_BRANCH" to true,
            "\$UNDEFINED == null" to true,
            "\$EMPTY == null" to false,
            "\$EMPTY == \"\"" to true,
            "\$CI_COMMIT_BRANCH =~ /^feature\\//" to true,
            "\$CI_COMMIT_BRANCH =~ /^FEATURE/" to false,
            "\$CI_COMMIT_BRANCH =~ /^FEATURE/i" to true,
            "\$CI_COMMIT_BRANCH !~ /^release/" to true,
            "\$CI_COMMIT_BRANCH =~ \$PATTERN" to true,
            "\$UNDEFINED =~ /x/" to false,
            "\$UNDEFINED !~ /x/" to true,
            // && binds tighter than ||
            "\$UNDEFINED && \$UNDEFINED || \$CI_COMMIT_BRANCH" to true,
            "\$CI_COMMIT_BRANCH || \$UNDEFINED && \$UNDEFINED" to true,
            "(\$CI_COMMIT_BRANCH || \$UNDEFINED) && \$UNDEFINED" to false,
            "\$CI_COMMIT_BRANCH == \"main\" || (\$CI_PIPELINE_SOURCE == \"push\" && \$CI_COMMIT_BRANCH =~ /login$/)" to true,
        )
        for ((expression, expected) in cases) assertEquals(expression, expected, Expression.evaluate(expression, variables))
    }

    @Test
    fun `rejects what it cannot parse`() {
        for (expression in listOf("", "\$A ==", "\$A == \"x\" \$B", "(\$A", "\$A = \"x\"", "\$A =~ \"x\"")) {
            assertThrows(expression, IllegalArgumentException::class.java) { Expression.evaluate(expression, mapOf("A" to "x")) }
        }
    }
}
