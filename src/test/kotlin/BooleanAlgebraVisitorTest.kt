import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.optimizer.BooleanAlgebraVisitor
import com.kingmang.ixion.runtime.BuiltInType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BooleanAlgebraVisitorTest {
    private val visitor = BooleanAlgebraVisitor()
    private fun token(type: TokenType, source: String = type.name) = Token(type, 1, 1, source)

    private fun variable(name: String): Expression =
        IdentifierExpression(null, token(TokenType.IDENTIFIER, name)).also { it.realType = BuiltInType.BOOLEAN }

    private fun literal(value: Boolean): Expression =
        LiteralExpression(null, token(if (value) TokenType.TRUE else TokenType.FALSE, value.toString()))
            .also { it.realType = BuiltInType.BOOLEAN }

    private fun not(right: Expression): Expression =
        PrefixExpression(null, token(TokenType.NOT, "!"), right).also { it.realType = BuiltInType.BOOLEAN }

    private fun binary(left: Expression, op: TokenType, right: Expression): Expression =
        BinaryExpression(null, left, token(op), right).also { it.realType = BuiltInType.BOOLEAN }

    private fun and(a: Expression, b: Expression) = binary(a, TokenType.AND, b)
    private fun or(a: Expression, b: Expression) = binary(a, TokenType.OR, b)
    private fun xor(a: Expression, b: Expression) = binary(a, TokenType.XOR, b)

    /** Checks cancellation, idempotence, constants, and complemented output against known identities. */
    @Test
    fun cancels_terms_and_eliminates_variables() {
        val a = variable("a")
        val b = variable("b")
        assertSame(b, visitor.rewriteExpression(xor(xor(a, b), a)))
        assertSame(a, visitor.rewriteExpression(xor(and(a, b), and(a, not(b)))))
        assertSame(a, visitor.rewriteExpression(or(and(a, b), and(a, not(b)))))
        val parity = visitor.rewriteExpression(xor(or(a, b), and(a, b))) as BinaryExpression
        assertEquals(TokenType.XOR, parity.operator.type)
        assertSame(a, parity.left)
        assertSame(b, parity.right)
        val zero = visitor.rewriteExpression(xor(a, a)) as LiteralExpression
        assertEquals(TokenType.FALSE, zero.literal.type)
        val one = visitor.rewriteExpression(or(a, not(a))) as LiteralExpression
        assertEquals(TokenType.TRUE, one.literal.type)
        assertSame(a, visitor.rewriteExpression(and(a, a)))
        assertSame(a, visitor.rewriteExpression(not(not(a))))
        assertTrue(visitor.rewriteExpression(xor(literal(true), a)) is PrefixExpression)
    }

    /** Checks that traversal preserves compact expressions, unsafe atoms, and non-boolean operations. */
    @Test
    fun keeps_compact_or_and_rejects_unsafe_atoms() {
        val a = variable("a")
        val b = variable("b")
        val compact = or(a, b)
        assertTreeEquals(compact, visitor.rewriteExpression(compact))
        // Even if ANF would cancel these atoms, calls and guarded comparisons must survive.
        val call = CallExpression(null, variable("effect"), mutableListOf())
            .also { it.realType = BuiltInType.BOOLEAN }
        val comparison = binary(variable("x"), TokenType.GT, variable("y"))
        val index = IndexAccessExpression(null, variable("values"), literal(false))
            .also { it.realType = BuiltInType.BOOLEAN }
        for (unsafe in listOf(call, comparison, index)) {
            val expression = xor(unsafe, unsafe)
            assertTreeEquals(expression, visitor.rewriteExpression(expression))
        }
        val numeric = xor(a, b).also { it.realType = BuiltInType.INT }
        assertTreeEquals(numeric, visitor.rewriteExpression(numeric))
    }

    @Test
    fun selects_smaller_output_and_keeps_original_on_equal_cost() {
        val a = variable("a")
        val b = variable("b")
        val product = and(a, b)
        val negated = visitor.rewriteExpression(xor(literal(true), product)) as PrefixExpression
        assertEquals(TokenType.NOT, negated.operator.type)
        val negatedProduct = negated.right as BinaryExpression
        assertEquals(TokenType.AND, negatedProduct.operator.type)
        assertSame(a, negatedProduct.left)
        assertSame(b, negatedProduct.right)

        // Boolean inequality and XOR both occupy three nodes: retain the original operator.
        val inequality = binary(a, TokenType.NOTEQUAL, b)
        assertTreeEquals(inequality, visitor.rewriteExpression(inequality))
        // The complement of false is true, but !true costs more than the false literal.
        val zero = visitor.rewriteExpression(xor(product, product)) as LiteralExpression
        assertEquals(TokenType.FALSE, zero.literal.type)
    }

    /** Exercises conversion limits, bottom-up traversal, and isolation between conversions. */
    @Test
    fun conversion_limits_preserve_structure_and_traversal_simplifies_children() {
        val tooManyVariables = (0..8).map { variable("v$it") }.reduce(::xor)
        assertTreeEquals(tooManyVariables, visitor.rewriteExpression(tooManyVariables))
        val expanding = (0..7).map { variable("v$it") }.reduce(::or)
        assertTreeEquals(expanding, visitor.rewriteExpression(expanding))
        val dense = (0..6).map { variable("v$it") }.reduce(::or)
        val expensiveProduct = and(dense, dense)
        assertTreeEquals(expensiveProduct, visitor.rewriteExpression(expensiveProduct))
        val a = variable("a")
        var deep = a
        repeat(300) { deep = not(deep) }
        assertSame(a, visitor.rewriteExpression(deep))
        // A bounded failure must not affect the next expression.
        val b = variable("b")
        assertSame(b, visitor.rewriteExpression(xor(xor(variable("a"), b), variable("a"))))
    }

    /** Compares original and optimized trees on all eight inputs for 400 reproducible samples. */
    @Test
    fun generated_expressions_preserve_every_truth_table_entry() {
        val random = Random(7391)
        val operators = listOf(TokenType.AND, TokenType.OR, TokenType.XOR, TokenType.EQUAL, TokenType.NOTEQUAL)
        /** Generates a bounded tree using boolean variables, constants, and supported operators. */
        fun expression(depth: Int): Expression {
            if (depth == 0) return if (random.nextInt(5) == 0) literal(random.nextBoolean())
            else variable(listOf("a", "b", "c")[random.nextInt(3)])
            return if (random.nextInt(4) == 0) not(expression(depth - 1))
            else binary(expression(depth - 1), operators[random.nextInt(operators.size)], expression(depth - 1))
        }
        repeat(400) {
            val original = expression(4)
            val optimized = visitor.rewriteExpression(original)
            assertTrue(nodeCount(optimized) <= nodeCount(original), "sample $it must not grow")
            for (inputs in 0..7) {
                assertEquals(evaluate(original, inputs), evaluate(optimized, inputs), "sample $it, inputs $inputs")
            }
        }
    }

    private fun nodeCount(expression: Expression): Int = when (expression) {
        is BinaryExpression -> 1 + nodeCount(expression.left) + nodeCount(expression.right)
        is PrefixExpression -> 1 + nodeCount(expression.right)
        else -> 1
    }

    /** Compares preserved syntax recursively, allowing traversal to reconstruct annotated parent nodes. */
    private fun assertTreeEquals(expected: Expression, actual: Expression) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.realType, actual.realType)
        assertEquals(expected.position, actual.position)
        when (expected) {
            is BinaryExpression -> {
                actual as BinaryExpression
                assertEquals(expected.operator, actual.operator)
                assertTreeEquals(expected.left, actual.left)
                assertTreeEquals(expected.right, actual.right)
            }
            is CallExpression -> {
                actual as CallExpression
                assertTreeEquals(expected.item, actual.item)
                assertEquals(expected.arguments.size, actual.arguments.size)
                expected.arguments.zip(actual.arguments).forEach { (left, right) -> assertTreeEquals(left, right) }
            }
            is IndexAccessExpression -> {
                actual as IndexAccessExpression
                assertTreeEquals(expected.left, actual.left)
                assertTreeEquals(expected.right, actual.right)
            }
            else -> assertSame(expected, actual)
        }
    }

    /** Evaluates a checked tree with a, b, and c supplied by the lowest three bits of [inputs]. */
    private fun evaluate(expression: Expression, inputs: Int): Boolean {
        assertEquals(BuiltInType.BOOLEAN, expression.realType)
        return when (expression) {
            is LiteralExpression -> expression.literal.type == TokenType.TRUE
            is IdentifierExpression -> inputs and (1 shl listOf(
                "a",
                "b",
                "c"
            ).indexOf(expression.identifier.source)) != 0

            is PrefixExpression -> !evaluate(expression.right, inputs)
            is BinaryExpression -> {
                val left = evaluate(expression.left, inputs)
                val right = evaluate(expression.right, inputs)
                when (expression.operator.type) {
                    TokenType.AND -> left && right
                    TokenType.OR -> left || right
                    TokenType.XOR, TokenType.NOTEQUAL -> left xor right
                    TokenType.EQUAL -> left == right
                    else -> error("Unexpected operator")
                }
            }

            else -> error("Unexpected expression")
        }
    }
}
