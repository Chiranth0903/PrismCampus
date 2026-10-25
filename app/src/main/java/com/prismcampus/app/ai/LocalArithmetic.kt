package com.prismcampus.app.ai

import java.math.BigDecimal
import java.math.MathContext

/** Small, bounded arithmetic parser. Never sends basic calculations to an LLM. */
object LocalArithmetic {
    private val units = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    fun answer(query: String): String? {
        if (query.length > 160) return null
        var expression = query.lowercase(java.util.Locale.US).trim().trimEnd('?', '.', '!')
            .replace(Regex("^(?:(?:please|what is|what's|calculate|compute|tell me)\\s+)+"), "")
            .replace("multiplied by", "*").replace("divided by", "/")
            .replace(Regex("\\b(plus|add)\\b"), "+")
            .replace(Regex("\\b(minus|subtract)\\b"), "-")
            .replace(Regex("\\b(times|into)\\b"), "*")
            .replace('×', '*').replace('÷', '/')
        val words = (units + tens.keys).joinToString("|")
        expression = expression.replace(Regex("\\b($words)(?:[ -]+(${units.take(10).joinToString("|")}))?\\b")) {
            val first = it.groupValues[1]
            val second = it.groupValues[2]
            if (second.isNotEmpty() && first !in tens) return@replace it.value
            ((tens[first] ?: units.indexOf(first)) + if (second.isEmpty()) 0 else units.indexOf(second)).toString()
        }.replace(" ", "")
        if (!expression.matches(Regex("[0-9.+*/()\\-]+")) || expression.none { it in "+-*/" }) return null
        return try {
            val value = Parser(expression).parse().stripTrailingZeros().toPlainString()
            if (value.length > 100) null else "The answer is $value."
        } catch (_: ArithmeticException) {
            "Division by zero is undefined."
        } catch (_: IllegalArgumentException) { null }
    }

    private class Parser(private val source: String) {
        private var position = 0
        private var depth = 0
        fun parse(): BigDecimal = expression().also { require(position == source.length) }
        private fun consume(char: Char): Boolean =
            (position < source.length && source[position] == char).also { if (it) position++ }
        private fun expression(): BigDecimal {
            var value = term()
            while (true) value = when {
                consume('+') -> value.add(term(), MathContext.DECIMAL64)
                consume('-') -> value.subtract(term(), MathContext.DECIMAL64)
                else -> return value
            }
        }
        private fun term(): BigDecimal {
            var value = factor()
            while (true) value = when {
                consume('*') -> value.multiply(factor(), MathContext.DECIMAL64)
                consume('/') -> value.divide(factor(), MathContext.DECIMAL64)
                else -> return value
            }
        }
        private fun factor(): BigDecimal {
            require(++depth <= 16)
            val value = when {
                consume('-') -> factor().negate()
                consume('+') -> factor()
                consume('(') -> expression().also { require(consume(')')) }
                else -> {
                    val start = position
                    while (position < source.length && (source[position].isDigit() || source[position] == '.')) position++
                    require(position > start)
                    source.substring(start, position).toBigDecimal()
                }
            }
            depth--
            return value
        }
    }
}
