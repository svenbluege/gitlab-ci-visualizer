package io.github.svenbluege.gitlabci

/**
 * Evaluates GitLab `rules:if` expressions: `$VAR`, strings, `/regex/`, `null`, `==`, `!=`, `=~`, `!~`, `&&`, `||`
 * and parentheses. Throws [IllegalArgumentException] for anything it cannot parse.
 */
object Expression {
    private val tokenPattern = Regex(
        """\s*(?:\$(?<var>\w+)|"(?<dq>[^"]*)"|'(?<sq>[^']*)'|(?<re>/(?:[^/\\]|\\.)+/[ims]*)|(?<op>==|!=|=~|!~|&&|\|\||[()])|(?<null>null\b))"""
    )

    fun evaluate(expression: String, variables: Map<String, String>): Boolean {
        val parser = Parser(tokenize(expression), variables)
        val value = parser.or()
        require(parser.position == parser.tokens.size) { "unexpected '${parser.tokens[parser.position].text}'" }
        return truthy(value)
    }

    private class Token(val kind: Char, val text: String)

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var position = 0
        while (input.substring(position).isNotBlank()) {
            val groups = tokenPattern.matchAt(input, position)?.groups
                ?: throw IllegalArgumentException("cannot parse '${input.substring(position).trim()}'")
            tokens += when {
                groups["var"] != null -> Token('v', groups["var"]!!.value)
                groups["dq"] != null -> Token('s', groups["dq"]!!.value)
                groups["sq"] != null -> Token('s', groups["sq"]!!.value)
                groups["re"] != null -> Token('r', groups["re"]!!.value)
                groups["op"] != null -> Token('o', groups["op"]!!.value)
                else -> Token('n', "null")
            }
            position = groups[0]!!.range.last + 1
        }
        return tokens
    }

    /** Values are String (null when undefined), Regex or Boolean. `&&` binds tighter than `||`. */
    private class Parser(val tokens: List<Token>, val variables: Map<String, String>) {
        var position = 0

        private fun operator(vararg operators: String): String? =
            tokens.getOrNull(position)?.takeIf { it.kind == 'o' && it.text in operators }?.also { position++ }?.text

        fun or(): Any? {
            var left = and()
            while (operator("||") != null) {
                val right = and()
                left = truthy(left) || truthy(right)
            }
            return left
        }

        private fun and(): Any? {
            var left = comparison()
            while (operator("&&") != null) {
                val right = comparison()
                left = truthy(left) && truthy(right)
            }
            return left
        }

        private fun comparison(): Any? {
            val left = operand()
            val operator = operator("==", "!=", "=~", "!~") ?: return left
            val right = operand()
            return when (operator) {
                "==" -> left == right
                "!=" -> left != right
                "=~" -> matches(left, right)
                else -> !matches(left, right)
            }
        }

        private fun operand(): Any? {
            val token = tokens.getOrNull(position++) ?: throw IllegalArgumentException("unexpected end of expression")
            return when (token.kind) {
                'v' -> variables[token.text]
                's' -> token.text
                'r' -> toRegex(token.text)
                'n' -> null
                else -> {
                    require(token.text == "(") { "unexpected '${token.text}'" }
                    or().also { require(operator(")") != null) { "missing ')'" } }
                }
            }
        }
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is String -> value.isNotEmpty()
        else -> value != null
    }

    /** The pattern may also come from a variable that holds `/regex/`. An undefined operand never matches. */
    private fun matches(left: Any?, right: Any?): Boolean {
        val regex = right as? Regex ?: (right as? String)?.let(::toRegex) ?: return false
        return left is String && regex.containsMatchIn(left)
    }

    // shortcut: uses Java regex syntax where GitLab uses RE2, upgrade if a pattern behaves differently.
    private fun toRegex(literal: String): Regex {
        val end = literal.lastIndexOf('/')
        require(literal.startsWith("/") && end > 0) { "'$literal' is not a /regex/" }
        val options = literal.substring(end + 1).mapNotNull {
            mapOf('i' to RegexOption.IGNORE_CASE, 'm' to RegexOption.MULTILINE, 's' to RegexOption.DOT_MATCHES_ALL)[it]
        }
        return Regex(literal.substring(1, end), options.toSet())
    }
}
