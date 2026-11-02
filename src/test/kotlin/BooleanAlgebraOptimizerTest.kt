import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.optimizer.BooleanAlgebraOptimizer
import com.kingmang.ixion.runtime.BuiltInType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BooleanAlgebraOptimizerTest {
    private val optimizer = BooleanAlgebraOptimizer()
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

    @Test
    fun cancels_terms_and_eliminates_variables() {
        val a = variable("a")
        val b = variable("b")
        assertSame(b, optimizer.optimize(xor(xor(a, b), a)))
        assertSame(a, optimizer.optimize(xor(and(a, b), and(a, not(b)))))
        assertSame(a, optimizer.optimize(or(and(a, b), and(a, not(b)))))
        val parity = optimizer.optimize(xor(or(a, b), and(a, b))) as BinaryExpression
        assertEquals(TokenType.XOR, parity.operator.type)
        assertSame(a, parity.left)
        assertSame(b, parity.right)
        val zero = optimizer.optimize(xor(a, a)) as LiteralExpression
        assertEquals(TokenType.FALSE, zero.literal.type)
        val one = optimizer.optimize(or(a, not(a))) as LiteralExpression
        assertEquals(TokenType.TRUE, one.literal.type)
        assertSame(a, optimizer.optimize(and(a, a)))
        assertSame(a, optimizer.optimize(not(not(a))))
        assertTrue(optimizer.optimize(xor(literal(true), a)) is PrefixExpression)
    }

    @Test
    fun keeps_compact_or_and_rejects_unsafe_atoms() {
        val a = variable("a")
        val b = variable("b")
        val compact = or(a, b)
        assertSame(compact, optimizer.optimize(compact))
        // Even if ANF would cancel these atoms, calls and guarded comparisons must survive.
        val call = CallExpression(null, variable("effect"), mutableListOf())
            .also { it.realType = BuiltInType.BOOLEAN }
        val comparison = binary(variable("x"), TokenType.GT, variable("y"))
        val index = IndexAccessExpression(null, variable("values"), literal(false))
            .also { it.realType = BuiltInType.BOOLEAN }
        for (unsafe in listOf(call, comparison, index)) {
            val expression = xor(unsafe, unsafe)
            assertSame(expression, optimizer.optimize(expression))
        }
        val numeric = xor(a, b).also { it.realType = BuiltInType.INT }
        assertSame(numeric, optimizer.optimize(numeric))
    }

    @Test
    fun expansion_and_traversal_limits_keep_original_expression() {
        val tooManyVariables = (0..8).map { variable("v$it") }.reduce(::xor)
        assertSame(tooManyVariables, optimizer.optimize(tooManyVariables))
        val expanding = (0..7).map { variable("v$it") }.reduce(::or)
        assertSame(expanding, optimizer.optimize(expanding))
        val dense = (0..6).map { variable("v$it") }.reduce(::or)
        val expensiveProduct = and(dense, dense)
        assertSame(expensiveProduct, optimizer.optimize(expensiveProduct))
        var deep = variable("a")
        repeat(300) { deep = not(deep) }
        assertSame(deep, optimizer.optimize(deep))
        // A bounded failure must not affect the next expression.
        val b = variable("b")
        assertSame(b, optimizer.optimize(xor(xor(variable("a"), b), variable("a"))))
    }

    @Test
    fun generated_expressions_preserve_every_truth_table_entry() {
        val random = Random(7391)
        val operators = listOf(TokenType.AND, TokenType.OR, TokenType.XOR, TokenType.EQUAL, TokenType.NOTEQUAL)
        fun expression(depth: Int): Expression {
            if (depth == 0) return if (random.nextInt(5) == 0) literal(random.nextBoolean())
            else variable(listOf("a", "b", "c")[random.nextInt(3)])
            return if (random.nextInt(4) == 0) not(expression(depth - 1))
            else binary(expression(depth - 1), operators[random.nextInt(operators.size)], expression(depth - 1))
        }
        repeat(400) {
            val original = expression(4)
            val optimized = optimizer.optimize(original)
            for (inputs in 0..7) {
                assertEquals(evaluate(original, inputs), evaluate(optimized, inputs), "sample $it, inputs $inputs")
            }
        }
    }

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
