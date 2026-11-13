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
class BooleanAlgebraVisitor : AstRewriteVisitor() {
    /**
     * Returns a smaller equivalent boolean tree, considering both ANF and its negated complement.
     * Returns [expression] unchanged if conversion is unsafe, exceeds a limit, or saves no nodes.
     */
    override fun transformExpression(expression: Expression): Expression {
        if (expression.realType != BuiltInType.BOOLEAN ||
            expression !is BinaryExpression && expression !is PrefixExpression) return expression
        val conversion = Conversion()
        val polynomial = conversion.read(expression) ?: return expression
        // Also consider a complemented polynomial: !a is cheaper than true ^ a.
        val complement = polynomial.toMutableSet().also { toggle(it, 0) }
        val directCost = cost(polynomial)
        val negatedCost = 1 + cost(complement)
        if (minOf(directCost, negatedCost) >= conversion.nodes) return expression

        return if (negatedCost < directCost) {
            PrefixExpression(expression.position, token(TokenType.NOT, expression),
                conversion.emit(complement, expression)).also { it.realType = BuiltInType.BOOLEAN }
        } else conversion.emit(polynomial, expression)
    }

    private class Conversion {
        private val variables = linkedMapOf<String, IdentifierExpression>()
        var nodes = 0
            private set
        private var work = 0

        /**
         * Converts supported boolean syntax into monomial masks with coefficient 1.
         * Each mask contains the bits of its variables; mask 0 is constant 1 and an empty
         * set is constant 0. Returns null for unsafe syntax or an exhausted conversion budget.
         */
        fun read(expression: Expression): Set<Int>? {
            nodes++
            if (nodes > MAX_NODES || expression.realType != BuiltInType.BOOLEAN) return null
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
                        TokenType.OR -> {
                            val sum = add(left, right) ?: return null
                            val product = multiply(left, right) ?: return null
                            add(sum, product)
                        }
                        TokenType.EQUAL -> {
                            val xor = add(left, right) ?: return null
                            add(xor, setOf(0))
                        }
                        else -> add(left, right) // XOR and boolean inequality
                    }
                }
                else -> null
            }
        }

        /** Adds polynomials modulo 2, returning null if the work or term limit is exceeded. */
        private fun add(left: Set<Int>, right: Set<Int>): Set<Int>? {
            val result = left.toMutableSet()
            for (term in right) {
                work++
                if (work > MAX_WORK) return null
                toggle(result, term)
                if (result.size > MAX_TERMS) return null
            }
            return result
        }

        /**
         * Multiplies polynomials using mask union for x*x = x and cancellation modulo 2.
         * Returns null if the work or intermediate term limit is exceeded.
         */
        private fun multiply(left: Set<Int>, right: Set<Int>): Set<Int>? {
            val result = mutableSetOf<Int>()
            for (a in left) for (b in right) {
                work++
                if (work > MAX_WORK) return null
                toggle(result, a or b) // x*x = x
                if (result.size > MAX_TERMS) return null
            }
            return result
        }

        /**
         * Builds a deterministic XOR of AND terms, reusing the recorded variable leaves.
         * New nodes inherit [original]'s position and boolean type; an empty polynomial is false.
         */
        fun emit(polynomial: Set<Int>, original: Expression): Expression {
            val leaves = variables.values.toList()
            val terms = polynomial.sorted().map { mask -> emitMonomial(mask, leaves, original) }
            return if (terms.isEmpty()) literal(false, original)
            else terms.reduce { left, right -> binary(left, TokenType.XOR, right, original) }
        }

        private fun emitMonomial(mask: Int, leaves: List<IdentifierExpression>, original: Expression): Expression {
            if (mask == 0) return literal(true, original)

            val factors: List<Expression> = leaves.indices
                .filter { index -> mask and (1 shl index) != 0 }
                .map { index -> leaves[index] }
            return factors.reduce { left, right -> binary(left, TokenType.AND, right, original) }
        }
    }

    companion object {
        private const val MAX_VARIABLES = 8
        private const val MAX_NODES = 256
        private const val MAX_TERMS = 128
        private const val MAX_WORK = 4096
        private val OPERATORS = setOf(TokenType.AND, TokenType.OR, TokenType.XOR,
            TokenType.EQUAL, TokenType.NOTEQUAL)

        /** Toggles a monomial's coefficient modulo 2 by adding or removing its mask. */
        private fun toggle(terms: MutableSet<Int>, term: Int) {
            if (!terms.add(term)) terms.remove(term)
        }

        /**
         * Counts the nodes emit() would build without allocating an AST.
         * A degree-k monomial has k leaves and k-1 ANDs; n terms need n-1 XORs.
         * Both constants, including the empty polynomial (false), occupy one node.
         */
        private fun cost(polynomial: Set<Int>): Int {
            if (polynomial.isEmpty()) return 1
            return polynomial.size - 1 + polynomial.sumOf { mask ->
                if (mask == 0) 1 else 2 * mask.countOneBits() - 1
            }
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
