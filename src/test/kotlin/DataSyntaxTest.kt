import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.runtime.IxionExitException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DataSyntaxTest {
    @TempDir lateinit var temporary: Path
    private fun parse(source: String): List<Statement> {
        Files.writeString(temporary.resolve("syntax.ix"), source.trimIndent())
        return IxApi().parse(temporary.toString(), "", "syntax.ix").statements
    }
    @Test fun ordered_patterns_and_specialization_have_distinct_syntax_nodes() {
        val nodes = parse("""
            type Tree[T] = enum { Empty, Leaf(value: T), }
            type Vector[T] = T[][]
            def main() {
                let specialized = identity[int](1)
                let indexed = values[index]
                let called = (values)[index](1)
                let matched = case value {
                    Tree::Leaf(x) if x > 0 => x,
                    Tree::Leaf(x) => -x,
                    Tree::Empty => 0,
                }
            }
        """)
        val tree = nodes[0] as EnumStatement
        assertEquals(listOf("T"), tree.generics.map { it.source })
        assertEquals(listOf("Empty", "Leaf"), tree.variants.map { it.name.source })
        val vector = (nodes[1] as TypeAliasStatement).typeStmt
        assertEquals(TypeForm.LIST, vector.form)
        assertEquals(TypeForm.LIST, vector.components.single().form)
        val statements = (nodes[2] as DefStatement).body!!.statements.filterIsInstance<VariableStatement>()
        assertTrue((statements[0].expression as CallExpression).item is TypeApplicationExpression)
        assertTrue(statements[1].expression is IndexAccessExpression)
        assertTrue((statements[2].expression as CallExpression).item is IndexAccessExpression)
        val match = statements[3].expression as CaseExpression
        assertEquals(3, match.branches.size)
        assertNotNull(match.branches[0].guard)
        assertNull(match.branches[1].guard)
        assertEquals("Leaf", (match.branches[0].pattern as ConstructorPattern).variant.source)
        assertEquals("Leaf", (match.branches[1].pattern as ConstructorPattern).variant.source)
        assertTrue(match.branches.all { it.pattern.end.line >= it.pattern.position.line })
        assertTrue(match.branches[0].pattern.position.line < match.branches[1].pattern.position.line)
    }
    @Test fun malformed_variants_rest_patterns_and_empty_returns_have_parser_diagnostics() {
        for ((source, message) in listOf(
            "type Empty = enum {}" to "at least one variant",
            "type A = enum { None(), }" to "without parentheses",
            "def f() { return }" to "use return ()",
            "def f() { case value { [head, ..tail, other] => 0, } }" to "rest pattern must be last"
        )) {
            val error = assertThrows(IxionExitException::class.java) { parse(source) }
            assertTrue(error.message!!.contains(message, ignoreCase = true), error.message)
        }
    }
}
