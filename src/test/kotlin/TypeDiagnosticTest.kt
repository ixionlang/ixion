import com.kingmang.ixion.CombinedOutputCapture
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.exception.IxException.CompilerError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TypeDiagnosticTest {
    @TempDir
    lateinit var temporary: Path

    private fun assertDiagnostic(source: String, expected: String) {
        Files.writeString(temporary.resolve("test.ix"), source.trimIndent())
        val api = IxApi()
        api.parse(temporary.toString(), "", "test.ix")
        CombinedOutputCapture().use {
            val failure = assertThrows(CompilerError::class.java) { api.analyze() }
            val messages = failure.errorData!!.filterNotNull().map { it.message.orEmpty() }
            assertTrue(messages.any { expected in it }, messages.toString())
            assertTrue(messages.none { Regex("\\{\\d+}").containsMatchIn(it) }, messages.toString())
        }
    }

    @Test
    fun binary_operator_errors_include_both_operand_types() {
        assertDiagnostic("""
            def increment[T](value: T): T {
                return value + 1
            }
        """, "Operator `+` cannot be applied to types `T` and `INT`")
        assertDiagnostic("def f(value: any) { let result = value + 1 }",
            "Operator `+` cannot be applied to types `ANY` and `INT`")
        assertDiagnostic("def f() { let result = \"a\" < \"b\" }",
            "Operator `<` cannot be applied to types `STRING` and `STRING`")
        assertDiagnostic("def f() { let result = true < false }",
            "Operator `<` cannot be applied to types `BOOLEAN` and `BOOLEAN`")
        assertDiagnostic("def f() { let result = 1 && 2 }",
            "Operator `&&` cannot be applied to types `INT` and `INT`")
    }

    @Test
    fun assignment_errors_include_declared_and_actual_types() {
        assertDiagnostic("def f() { let mut value = 1 value = \"wrong\" }",
            "Variable `value` of type `INT` cannot accept assignment of type `STRING`.")
    }

    @Test
    fun return_errors_include_declared_and_actual_types() {
        assertDiagnostic("def f(): int { return \"wrong\" }",
            "Function `f` has return type of `INT`. Cannot have another return statement with type `STRING`.")
        assertDiagnostic("def f(): int {}",
            "Function `f` has return type of `INT`. Cannot have another return statement with type `unit`.")
    }
}
