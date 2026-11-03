import com.kingmang.ixion.api.Context
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.Position
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.optimizer.BooleanAlgebraVisitor
import com.kingmang.ixion.optimizer.ConstantFoldingVisitor
import com.kingmang.ixion.runtime.BuiltInType
import com.kingmang.ixion.runtime.DefType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OptimizationVisitorsTest {
    private val position = Position(3, 7)

    /** Builds a checked boolean variable at a known source position. */
    private fun variable(name: String): Expression =
        IdentifierExpression(position, Token(TokenType.IDENTIFIER, 3, 7, name))
            .also { it.realType = BuiltInType.BOOLEAN }

    /** Builds a checked binary expression for isolation and traversal tests. */
    private fun binary(left: Expression, operator: TokenType, right: Expression): Expression =
        BinaryExpression(position, left, Token(operator, 3, 7, operator.name), right)
            .also { it.realType = left.realType }

    /** Creates a typed literal independently of the parser and semantic pipeline. */
    private fun literal(type: TokenType, source: String, checkedType: BuiltInType): Expression =
        LiteralExpression(position, Token(type, 3, 7, source)).also { it.realType = checkedType }

    /** Ensures neither pass performs the other's rules and constant folding still handles boolean XOR. */
    @Test fun visitors_apply_independent_optimization_rules() {
        val a = variable("a")
        val cancellation = binary(a, TokenType.XOR, a)
        assertTrue(ConstantFoldingVisitor().rewriteExpression(cancellation) is BinaryExpression)
        val simplified = BooleanAlgebraVisitor().rewriteExpression(cancellation) as LiteralExpression
        assertEquals(TokenType.FALSE, simplified.literal.type)
        assertEquals(position, simplified.position)

        val sum = binary(literal(TokenType.INT, "2", BuiltInType.INT), TokenType.ADD,
            literal(TokenType.INT, "3", BuiltInType.INT))
        assertTrue(BooleanAlgebraVisitor().rewriteExpression(sum) is BinaryExpression)
        val folded = ConstantFoldingVisitor().rewriteExpression(sum) as LiteralExpression
        assertEquals("5", folded.literal.source)
        assertEquals(BuiltInType.INT, folded.realType)
        assertEquals(position, folded.position)

        val xor = binary(literal(TokenType.TRUE, "true", BuiltInType.BOOLEAN), TokenType.XOR,
            literal(TokenType.FALSE, "false", BuiltInType.BOOLEAN))
        assertEquals(TokenType.TRUE,
            (ConstantFoldingVisitor().rewriteExpression(xor) as LiteralExpression).literal.type)
    }

    /** Checks that the boolean pass handles grouping and visits call arguments without changing call metadata. */
    @Test fun boolean_visitor_rewrites_nested_arguments_and_preserves_call_annotations() {
        val a = variable("a")
        val b = variable("b")
        val grouped = GroupingExpression(position, binary(a, TokenType.XOR, b))
            .also { it.realType = BuiltInType.BOOLEAN }
        val call = CallExpression(position, variable("effect"),
            mutableListOf(binary(grouped, TokenType.XOR, a)))
        call.foreign = Token(TokenType.IDENTIFIER, 3, 7, "foreign")
        call.specialization = mapOf("T" to BuiltInType.BOOLEAN)
        call.realType = BuiltInType.BOOLEAN

        val rewritten = BooleanAlgebraVisitor().rewriteExpression(call) as CallExpression
        assertSame(b, rewritten.arguments.single())
        assertSame(call.item, rewritten.item)
        assertSame(call.foreign, rewritten.foreign)
        assertSame(call.specialization, rewritten.specialization)
        assertSame(call.realType, rewritten.realType)
        assertEquals(call.position, rewritten.position)
    }

    /** Verifies both passes retain function signatures, block contexts, and checked match nodes. */
    @Test fun shared_traversal_preserves_checked_metadata_across_passes() {
        val context = Context()
        val a = variable("a")
        val cancellation = binary(a, TokenType.XOR, a)
        val case = CaseExpression(position, cancellation, emptyList()).also { it.realType = BuiltInType.BOOLEAN }
        val body = BlockStatement(position, mutableListOf(ExpressionStatement(position, case),
            ReturnStatement(position, cancellation), null), context)
        val function = DefStatement(position, Token(TokenType.IDENTIFIER, 3, 7, "test"),
            mutableListOf(), null, body, mutableListOf())
        function.functionType = DefType("test", mutableListOf())

        val folded = ConstantFoldingVisitor().rewriteStatement(function) as DefStatement
        val rewritten = BooleanAlgebraVisitor().rewriteStatement(folded) as DefStatement
        for (result in listOf(folded, rewritten)) {
            assertSame(function.functionType, result.functionType)
            assertSame(context, result.body!!.context)
            assertSame(case, (result.body.statements[0] as ExpressionStatement).expression)
            assertSame(cancellation, case.subject)
            assertNull(result.body.statements[2])
        }
        assertTrue((folded.body!!.statements[1] as ReturnStatement).expression is BinaryExpression)
        assertEquals(TokenType.FALSE,
            ((rewritten.body!!.statements[1] as ReturnStatement).expression as LiteralExpression).literal.type)
    }
}
