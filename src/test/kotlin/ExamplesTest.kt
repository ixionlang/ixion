import com.kingmang.ixion.Ixion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals

class ExamplesTest {

    @Test
    fun loops() {
        ixAssert("loops.ix", """
            1
            2
            3
            4
            5
            ----
            i is 10
            i is 11
            i is 12
            i is 13
            i is 14
            i is 15
            i is 16
            i is 17
            i is 18
            i is 19
            i is 20

        """)
    }

    @Test
    fun simple_list() {
        ixAssert("simple_list.ix", """
            [1, 2, 3]
            [20]
        """)
    }

    @Test
    fun struct() {
        ixAssert("struct.ix", """
            value[left=left_value[arg=first], right=right_value[arg=second]]
        """)
    }

    @Test
    fun adt() {
        ixAssert("adt.ix", """
            value 10 is integer
            value 10.0 is float
        """)
    }

    @Test
    fun case() {
        ixAssert("case_test.ix", """
            30
        """)
    }

    @Test
    fun lambda() {
        ixAssert("lambda.ix", """
            42
            Hello, Ixion
            49
        """)
    }

    @Test
    fun module_qualifier() {
        ixAssert("module_qualifier.ix", """
            a+b%26c
        """)
    }

    private fun ixAssert(runPath: String, expected: String) {
        val ixion = Ixion()
        assertDoesNotThrow {
            val actual = ixion.getCompiledProgramOutput("/src/test/resources/$runPath")
            assertEquals(
                normalizeString(expected),
                normalizeString(actual),
                "Строки не совпадают после нормализации"
            )
        }
    }

    private fun normalizeString(str: String?): String {
        if (str == null) return ""
        val normalized = str
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val lines = normalized.split('\n')
        val result = StringBuilder()

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (index == lines.lastIndex && trimmed.isEmpty()) {
                continue
            }
            result.append(trimmed)
            if (index < lines.lastIndex) {
                result.append('\n')
            }
        }

        return result.toString().trim()
    }
}
