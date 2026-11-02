package com.kingmang.ixion.optimizer

import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.BuiltInType

/**
 * Bounded algebraic normal form (Zhegalkin polynomial) simplification.
 * Only boolean literals and local variable reads are admitted as leaves. In particular,
 * calls, property/index access and comparisons are not atoms: changing their evaluation
 * count or short-circuit guards could change effects, exceptions or termination.
 */
class BooleanAlgebraOptimizer {
    fun optimize(expression: Expression): Expression {
        if (expression.realType != BuiltInType.BOOLEAN ||
            expression !is BinaryExpression && expression !is PrefixExpression) return expression
        val conversion = Conversion()
        val polynomial = conversion.read(expression) ?: return expression
        val candidate = conversion.emit(polynomial, expression)
        // Also consider a complemented polynomial: !a is cheaper than true ^ a.
        val complement = polynomial.toMutableSet().also { toggle(it, 0) }
        val negated = PrefixExpression(expression.position, token(TokenType.NOT, expression),
            conversion.emit(complement, expression)).also { it.realType = BuiltInType.BOOLEAN }
        val best = if (cost(negated) < cost(candidate)) negated else candidate
        return if (cost(best) < conversion.nodes) best else expression
    }

    private class Conversion {
        val variables = linkedMapOf<String, IdentifierExpression>()
        var nodes = 0
        private var work = 0

        // A monomial is a bitset of variables; a polynomial is the set of monomials
        // with coefficient 1. Mask 0 denotes the constant 1; the empty set is 0.
        fun read(expression: Expression): Set<Int>? {
            if (++nodes > MAX_NODES || expression.realType != BuiltInType.BOOLEAN) return null
            return when (expression) {
                is LiteralExpression -> when (expression.literal.type) {
                    TokenType.TRUE -> setOf(0)
                    TokenType.FALSE -> emptySet()
                    else -> null
                }
                is IdentifierExpression -> {
                    val name = expression.identifier.source
                    if (name !in variables) {
                        if (variables.size >= MAX_VARIABLES) return null
                        variables[name] = expression
                    }
                    setOf(1 shl variables.keys.indexOf(name))
                }
                is PrefixExpression -> {
                    if (expression.operator.type != TokenType.NOT) return null
                    val right = read(expression.right) ?: return null
                    add(right, setOf(0))
                }
                is BinaryExpression -> {
                    val op = expression.operator.type
                    if (op !in OPERATORS) return null
                    val left = read(expression.left) ?: return null
                    val right = read(expression.right) ?: return null
                    when (op) {
                        TokenType.AND -> multiply(left, right)
                        TokenType.OR -> add(add(left, right) ?: return null,
                            multiply(left, right) ?: return null)
                        TokenType.EQUAL -> add(add(left, right) ?: return null, setOf(0))
                        else -> add(left, right) // XOR and boolean inequality
                    }
                }
                else -> null
            }
        }

        private fun add(left: Set<Int>, right: Set<Int>): Set<Int>? {
            val result = left.toMutableSet()
            for (term in right) {
                if (++work > MAX_WORK) return null
                toggle(result, term)
                if (result.size > MAX_TERMS) return null
            }
            return result
        }

        private fun multiply(left: Set<Int>, right: Set<Int>): Set<Int>? {
            val result = mutableSetOf<Int>()
            for (a in left) for (b in right) {
                if (++work > MAX_WORK) return null
                toggle(result, a or b) // x*x = x
                if (result.size > MAX_TERMS) return null
            }
            return result
        }

        fun emit(polynomial: Set<Int>, original: Expression): Expression {
            val leaves = variables.values.toList()
            val terms = polynomial.sorted().map { mask ->
                if (mask == 0) literal(true, original)
                else leaves.indices.filter { mask and (1 shl it) != 0 }
                    .map { leaves[it] as Expression }
                    .reduce { left, right -> binary(left, TokenType.AND, right, original) }
            }
            return if (terms.isEmpty()) literal(false, original)
            else terms.reduce { left, right -> binary(left, TokenType.XOR, right, original) }
        }
    }

    companion object {
        private const val MAX_VARIABLES = 8
        private const val MAX_NODES = 256
        private const val MAX_TERMS = 128
        private const val MAX_WORK = 4096
        private val OPERATORS = setOf(TokenType.AND, TokenType.OR, TokenType.XOR,
            TokenType.EQUAL, TokenType.NOTEQUAL)

        private fun toggle(terms: MutableSet<Int>, term: Int) {
            if (!terms.add(term)) terms.remove(term)
        }

        // AST size is a conservative local heuristic, not a prediction of JIT performance.
        private fun cost(expression: Expression): Int = when (expression) {
            is BinaryExpression -> 1 + cost(expression.left) + cost(expression.right)
            is PrefixExpression -> 1 + cost(expression.right)
            else -> 1
        }

        private fun token(type: TokenType, original: Expression) =
            Token(type, original.position?.line ?: 0, original.position?.col ?: 0, when (type) {
                TokenType.AND -> "&&"
                TokenType.XOR -> "^"
                TokenType.NOT -> "!"
                TokenType.TRUE -> "true"
                else -> "false"
            })

        private fun literal(value: Boolean, original: Expression): Expression =
            LiteralExpression(original.position, token(if (value) TokenType.TRUE else TokenType.FALSE, original))
                .also { it.realType = BuiltInType.BOOLEAN }

        private fun binary(left: Expression, op: TokenType, right: Expression, original: Expression): Expression =
            BinaryExpression(original.position, left, token(op, original), right)
                .also { it.realType = BuiltInType.BOOLEAN }
    }
}
