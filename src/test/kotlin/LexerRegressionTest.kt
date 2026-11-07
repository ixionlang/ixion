import com.kingmang.ixion.CombinedOutputCapture
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.exception.IxException.CompilerError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Tag
import java.io.File
import java.net.URLClassLoader

class LexerRegressionTest {
    @Test
    fun string_and_list_indexing() {
        assertOutput("lexer_string_index_bug.ix", "a\nc\ny\n20\n3\na\n1\n[]")
    }

    @Test
    fun struct_fields_and_nested_receivers() {
        assertOutput("lexer_struct_field_bug.ix", "OK\n2\n2")
    }

    @Test
    @Tag("legacy-expression-assignment")
    fun postfix_values_and_loop_stack_balance() {
        assertOutput("lexer_postfix_loop_bug.ix", "2\n2\n0\n2.0\n2.0\n1.0\n7")
    }

    @Test
    fun repeated_nested_and_temporary_iterables() {
        assertOutput("lexer_list_iteration_bug.ix", "a\na\naa\nab\nba\nbb\na\nb\n0\n1\n2\n3")
    }

    @Test
    fun index_must_be_an_integer() {
        assertCompileError("lexer_index_type_invalid.ix", "Argument of type `BOOLEAN` cannot supply a parameter of type `int`.")
    }

    @Test
    fun target_must_support_indexing() {
        assertCompileError("lexer_index_target_invalid.ix", "Operator `[]` cannot be applied")
    }

    @Test
    fun lexer_demo() {
        val actual = runProgram("src/test/resources/lexer.ix")
        assertEquals("""
            Token[typo=2, name=let, start=0, end=3]
            Token[typo=1, name=x, start=4, end=5]
            Token[typo=6, name==, start=6, end=7]
            Token[typo=3, name=42, start=8, end=10]
            Token[typo=6, name=+, start=11, end=12]
            Token[typo=4, name=3.5, start=13, end=16]
            Token[typo=1, name=x, start=23, end=24]
            Token[typo=6, name=>=, start=25, end=27]
            Token[typo=3, name=2, start=28, end=29]
            Token[typo=9, name=, start=29, end=29]
        """.trimIndent(), actual)
    }

    @Test
    fun comparison_boundaries_used_by_lexer_eof_checks() {
        assertOutput("lexer_comparison_boundaries.ix", "false\ntrue\nfalse\ntrue\ntrue\ntrue\nfalse\nfalse\nfalse\ntrue")
    }

    @Test
    fun decoded_string_quotes_are_not_removed_again() {
        assertOutput("lexer_quote_literal.ix", "\"a\"\n\"")
    }

    private fun assertOutput(file: String, expected: String) {
        assertEquals(expected, runProgram("src/test/resources/$file"))
    }

    private fun runProgram(path: String): String {
        val name = IxApi().compile(System.getProperty("user.dir"), path)
        URLClassLoader(arrayOf(File(".out").toURI().toURL()), javaClass.classLoader).use { loader ->
            CombinedOutputCapture().use { capture ->
                // Invoke directly so verification errors and runtime exceptions fail the test.
                loader.loadClass(name).getMethod("main", Array<String>::class.java)
                    .invoke(null, emptyArray<String>() as Any)
                return capture.output!!.replace("\r\n", "\n").trim()
            }
        }
    }

    private fun assertCompileError(file: String, message: String) {
        CombinedOutputCapture().use { capture ->
            assertThrows(CompilerError::class.java) {
                IxApi().compile(System.getProperty("user.dir"), "src/test/resources/$file")
            }
            assertTrue(capture.output!!.contains(message), capture.output)
        }
    }
}
