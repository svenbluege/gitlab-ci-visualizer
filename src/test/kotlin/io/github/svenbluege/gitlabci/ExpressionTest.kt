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
        "VAR_2" to "a && b",
    )

    private fun check(cases: Map<String, Boolean>) {
        for ((expression, expected) in cases) assertEquals(expression, expected, Expression.evaluate(expression, variables))
    }

    @Test
    fun `a variable alone is true when it is defined and not empty`() = check(mapOf(
        $$"$CI_COMMIT_BRANCH" to true,
        $$"$UNDEFINED" to false,
        $$"$EMPTY" to false,
        $$"  $CI_COMMIT_BRANCH  " to true,
    ))

    @Test
    fun `equality compares strings, variables and null`() = check(mapOf(
        $$"""$CI_PIPELINE_SOURCE == "push"""" to true,
        $$"$CI_PIPELINE_SOURCE == 'schedule'" to false,
        $$"""$CI_PIPELINE_SOURCE=="push"""" to true,
        $$"""$CI_PIPELINE_SOURCE != "push"""" to false,
        $$"$CI_COMMIT_BRANCH != $CI_DEFAULT_BRANCH" to true,
        $$"""$VAR_2 == "a && b"""" to true,
        $$"$UNDEFINED == null" to true,
        $$"$UNDEFINED != null" to false,
        $$"$UNDEFINED == $ALSO_UNDEFINED" to true,
        $$"$EMPTY == null" to false,
        $$"""$EMPTY == """"" to true,
        $$"""$UNDEFINED == """"" to false,
        $$"null == $UNDEFINED" to true,
    ))

    @Test
    fun `regular expressions match anywhere and honour flags`() = check(mapOf(
        $$"""$CI_COMMIT_BRANCH =~ /^feature\//""" to true,
        $$"$CI_COMMIT_BRANCH =~ /login/" to true,
        $$"$CI_COMMIT_BRANCH =~ /^login/" to false,
        $$"$CI_COMMIT_BRANCH =~ /^FEATURE/" to false,
        $$"$CI_COMMIT_BRANCH =~ /^FEATURE/i" to true,
        $$"""$CI_COMMIT_BRANCH =~ /^(main|feature\/.+)$/""" to true,
        $$"$CI_COMMIT_BRANCH !~ /^release/" to true,
        $$"$CI_COMMIT_BRANCH !~ /^feature/" to false,
        $$"$CI_COMMIT_BRANCH =~ $PATTERN" to true,
        $$"$CI_COMMIT_BRANCH =~ $UNDEFINED" to false,
        $$"$UNDEFINED =~ /x/" to false,
        $$"$UNDEFINED !~ /x/" to true,
        $$"$EMPTY =~ /^$/" to true,
    ))

    @Test
    fun `and binds tighter than or, parentheses override`() = check(mapOf(
        $$"$UNDEFINED && $UNDEFINED || $CI_COMMIT_BRANCH" to true,
        $$"$CI_COMMIT_BRANCH || $UNDEFINED && $UNDEFINED" to true,
        $$"($CI_COMMIT_BRANCH || $UNDEFINED) && $UNDEFINED" to false,
        $$"$CI_COMMIT_BRANCH && $CI_DEFAULT_BRANCH && $EMPTY" to false,
        $$"""$CI_COMMIT_BRANCH == "main" || ($CI_PIPELINE_SOURCE == "push" && $CI_COMMIT_BRANCH =~ /login$/)""" to true,
        $$"""(($CI_PIPELINE_SOURCE == "push"))""" to true,
        $$"""$CI_PIPELINE_SOURCE == "push" && $CI_COMMIT_BRANCH != "main"""" to true,
    ))

    @Test
    fun `rejects what it cannot parse`() {
        val broken = listOf(
            "", "   ", $$"$A ==", $$"""$A == "x" $B""", $$"($A", $$"$A)", $$"""$A = "x"""", $$"""$A =~ "x"""",
            $$"$A =~ /[/", $$"$A &&", $$"|| $A", "main", $$"$A == \"unterminated",
        )
        for (expression in broken) {
            assertThrows(expression, IllegalArgumentException::class.java) { Expression.evaluate(expression, mapOf("A" to "x")) }
        }
    }
}
