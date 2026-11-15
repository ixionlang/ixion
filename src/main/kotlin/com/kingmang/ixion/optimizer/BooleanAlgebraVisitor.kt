package com.kingmang.ixion.optimizer

import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.BuiltInType

/**
 * Bounded algebraic normal form (Zhegalkin polynomial) simplification.
 * Only boolean literals and variable reads are admitted as leaves so rewrites can
 * safely change evaluation order and short-circuit guards.
 */
class BooleanAlgebraVisitor : AstRewriteVisitor() {
    override fun transformExpression(expression: Expression): Expression {
        if (expression.realType != BuiltInType.BOOLEAN ||
            expression !is BinaryExpression && expression !is PrefixExpression) return expression
        return Conversion(expression).simplify()
    }

    private class Conversion(private val original: Expression) {
        private val position = original.position
        private val variables = linkedMapOf<String, IdentifierExpression>()
        private var nodes = 0
        private var work = 0

        fun simplify(): Expression {
            val polynomial = read(original) ?: return original
            // A negated complement can be cheaper: !a vs true ^ a.
            val complement = if (0 in polynomial) polynomial - 0 else polynomial + 0
            val directCost = cost(polynomial)
            val negatedCost = 1 + cost(complement)
            if (minOf(directCost, negatedCost) >= nodes) return original

            if (directCost <= negatedCost) return emit(polynomial)
            return PrefixExpression(position, token(TokenType.NOT), emit(complement))
                .also { it.realType = BuiltInType.BOOLEAN }
        }

        // Each set bit represents a variable in a monomial. Mask 0 is true, and an empty set is false.
        private fun read(expression: Expression): Set<Int>? {
            nodes++
            if (nodes > MAX_NODES || expression.realType != BuiltInType.BOOLEAN) return null
            return when (expression) {
                is LiteralExpression -> when (expression.literal.type) {
                    TokenType.TRUE -> ONE
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
                    add(right, ONE)
                }
                is BinaryExpression -> {
                    val op = expression.operator.type
                    if (op !in OPERATORS) return null
                    val left = read(expression.left) ?: return null
                    val right = read(expression.right) ?: return null
                    when (op) {
                        TokenType.AND -> multiply(left, right)
                        TokenType.OR -> {
                            val sum = add(left, right) ?: return null
                            val product = multiply(left, right) ?: return null
                            add(sum, product)
                        }
                        TokenType.EQUAL -> {
                            val xor = add(left, right) ?: return null
                            add(xor, ONE)
                        }
                        else -> add(left, right) // XOR and boolean inequality
                    }
                }
                else -> null
            }
        }

        private fun add(left: Set<Int>, right: Set<Int>): Set<Int>? {
            val result = left.toMutableSet()
            for (term in right) {
                if (!toggleTerm(result, term)) return null
            }
            return result
        }

        private fun multiply(left: Set<Int>, right: Set<Int>): Set<Int>? {
            val result = mutableSetOf<Int>()
            for (a in left) for (b in right) {
                if (!toggleTerm(result, a or b)) return null // x*x = x
            }
            return result
        }

        // Adding a monomial modulo 2 cancels it if it is already present.
        private fun toggleTerm(terms: MutableSet<Int>, term: Int): Boolean {
            if (++work > MAX_WORK) return false
            if (!terms.add(term)) terms.remove(term)
            return terms.size <= MAX_TERMS
        }

        private fun emit(polynomial: Set<Int>): Expression {
            if (polynomial.isEmpty()) return literal(TokenType.FALSE)

            val leaves = variables.values.toList()
            return polynomial.sorted().map { mask -> emitMonomial(mask, leaves) }
                .reduce { left, right -> binary(left, TokenType.XOR, right) }
        }

        private fun emitMonomial(mask: Int, leaves: List<IdentifierExpression>): Expression {
            if (mask == 0) return literal(TokenType.TRUE)

            val factors: List<Expression> = leaves.filterIndexed { index, _ ->
                mask and (1 shl index) != 0
            }
            return factors.reduce { left, right -> binary(left, TokenType.AND, right) }
        }

        private fun token(type: TokenType) =
            Token(type, position?.line ?: 0, position?.col ?: 0, requireNotNull(type.representation))

        private fun literal(type: TokenType): Expression =
            LiteralExpression(position, token(type)).also { it.realType = BuiltInType.BOOLEAN }

        private fun binary(left: Expression, op: TokenType, right: Expression): Expression =
            BinaryExpression(position, left, token(op), right).also { it.realType = BuiltInType.BOOLEAN }
    }

    companion object {
        private const val MAX_VARIABLES = 8
        private const val MAX_NODES = 256
        private const val MAX_TERMS = 128
        private const val MAX_WORK = 4096
        private val ONE = setOf(0)
        private val OPERATORS = setOf(
            TokenType.AND, TokenType.OR, TokenType.XOR, TokenType.EQUAL, TokenType.NOTEQUAL
        )

        // A degree-k monomial has k leaves and k-1 ANDs. Joining n terms takes n-1 XORs.
        private fun cost(polynomial: Set<Int>): Int {
            if (polynomial.isEmpty()) return 1
            return polynomial.size - 1 + polynomial.sumOf { mask ->
                if (mask == 0) 1 else 2 * mask.countOneBits() - 1
            }
        }
    }
}
