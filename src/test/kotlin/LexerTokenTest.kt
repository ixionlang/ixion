import com.kingmang.ixion.api.IxApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.lang.reflect.Method
import java.net.URLClassLoader

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LexerTokenTest {
    private lateinit var loader: URLClassLoader
    private lateinit var nextToken: Method

    @BeforeAll
    fun compileLexer() {
        val name = IxApi().compile(System.getProperty("user.dir"), "src/test/resources/lexer.ix")
        loader = URLClassLoader(arrayOf(File(".out").toURI().toURL()), javaClass.classLoader)
        nextToken = loader.loadClass(name).getMethod("_next_token", String::class.java, Int::class.javaPrimitiveType)
    }

    @AfterAll
    fun closeLoader() {
        if (::loader.isInitialized) loader.close()
    }

    @Test
    fun empty_input_and_trivia_reach_eof() {
        assertEquals(listOf(Token(9, "", 0, 0)), scan(""))
        assertEquals(listOf(Token(9, "", 4, 4)), scan(" \t\r\n"))
    }

    @Test
    fun keywords_identifiers_and_decimal_boundaries() {
        assertEquals(
            listOf(2 to "let", 2 to "mut", 1 to "variable_1", 2 to "lambda", 1 to "A2", 3 to "0", 3 to "12", 4 to "3.5", 3 to "7", 7 to ".", 9 to ""),
            scan("let mut variable_1 lambda A2 0 12 3.5 7.").map { it.kind to it.text }
        )
    }

    @Test
    fun operators_use_longest_match() {
        assertEquals(
            listOf(3 to "1", 6 to "..", 3 to "2", 7 to "...", 6 to "==", 6 to "=", 6 to "**", 6 to "*", 6 to "=>", 6 to "::", 6 to ">=", 7 to "(", 7 to ")", 9 to ""),
            scan("1..2 ... == = ** * => :: >= ()").map { it.kind to it.text }
        )
    }

    @Test
    fun comments_include_empty_blocks_and_eof() {
        val source = "/**/let/* text\nmore */x// end"
        val tokens = scan(source)
        assertEquals(listOf(2 to "let", 1 to "x", 9 to ""), tokens.map { it.kind to it.text })
        assertEquals(4, tokens[0].start)
        assertEquals(source.length, tokens.last().end)
        assertEquals(listOf(Token(9, "", 5, 5)), scan("//end"))
    }

    @Test
    fun strings_decode_escapes_and_preserve_source_offsets() {
        val source = "\"hi\\n\\t\\r\\\"\\\\\""
        assertEquals(listOf(Token(5, "hi\n\t\r\"\\", 0, source.length), Token(9, "", source.length, source.length)), scan(source))
        assertEquals(listOf(Token(5, "", 0, 2), Token(9, "", 2, 2)), scan("\"\""))
    }

    @Test
    fun malformed_input_produces_error_tokens() {
        for ((source, message) in listOf(
            "/*" to "Unterminated comment",
            "\"abc" to "Unterminated string",
            "\"a\nb\"" to "Newline in string",
            "\"\\q\"" to "Invalid string escape",
            "@" to "Unexpected character: @"
        )) {
            val token = scan(source).last()
            assertEquals(8, token.kind, source)
            assertEquals(message, token.text, source)
            assertEquals(0, token.start, source)
        }
    }

    private data class Token(val kind: Int, val text: String, val start: Int, val end: Int)

    private fun scan(source: String): List<Token> {
        val result = mutableListOf<Token>()
        var cursor = 0
        repeat(source.length + 2) {
            val value = nextToken.invoke(null, source, cursor)
            val product = value as com.kingmang.ixion.runtime.IxDataValue
            val token = Token(product.ixField(0) as Int, product.ixField(1) as String,
                product.ixField(2) as Int, product.ixField(3) as Int)
            assertEquals(cursor, token.start)
            assertTrue(token.end in cursor..source.length)
            if (token.kind != 0) result.add(token)
            if (token.kind == 8 || token.kind == 9) return result
            assertTrue(token.end > cursor, "Lexer must advance: $token")
            cursor = token.end
        }
        error("Lexer did not terminate")
    }
}
