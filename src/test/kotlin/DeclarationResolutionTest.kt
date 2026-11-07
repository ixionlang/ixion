import com.kingmang.ixion.CombinedOutputCapture
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.exception.IxException.CompilerError
import com.kingmang.ixion.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

class DeclarationResolutionTest {
    @TempDir
    lateinit var temporary: Path

    private fun project(source: String, modules: Map<String, String> = emptyMap()): Path {
        val root = Files.createTempDirectory(temporary, "project")
        Files.writeString(root.resolve("main.ix"), source.trimIndent())
        for ((name, text) in modules) {
            val file = root.resolve("$name.ix")
            Files.createDirectories(file.parent)
            Files.writeString(file, text.trimIndent())
        }
        return root
    }

    private fun analyze(source: String, modules: Map<String, String> = emptyMap()): Pair<IxApi, IxFile> {
        val root = project(source, modules)
        val api = IxApi()
        val file = api.parse(root.toString(), "", "main.ix")
        api.analyze()
        return api to file
    }

    private fun assertError(source: String, fragment: String, modules: Map<String, String> = emptyMap(), code: Int? = null) {
        val root = project(source, modules)
        val api = IxApi()
        api.parse(root.toString(), "", "main.ix")
        CombinedOutputCapture().use {
            val failure = assertThrows(CompilerError::class.java) { api.analyze() }
            val diagnostics = failure.errorData!!.filterNotNull()
            assertTrue(diagnostics.any { fragment in it.message.orEmpty() }, diagnostics.toString())
            if (code != null) assertTrue(diagnostics.any { it.code == code })
            assertTrue(diagnostics.all { it.line >= 1 && it.col >= 0 })
        }
    }

    @Test
    fun overloads_resolve_by_arity_and_argument_types_on_both_backends() {
        assertBackends("""
            use <std>
            def c(): int { return 1 }
            def c(a: int): int { return a + 1 }
            def c(a: string): string { return a }
            def main() {
                std::println(c())
                std::println(c(41))
                std::println(c("ok"))
            }
        """, "1\n42\nok")
    }

    @Test
    fun duplicate_overload_signatures_are_rejected() {
        assertError("""
            def c(a: int): int { return a }
            def c(value: int): int { return value }
        """, "duplicate function signature c(int)")
    }

    @Test
    fun aliases_resolve_forward_through_lists_and_flatten_nested_unions() {
        val (_, file) = analyze("""
            type Values = Item[]
            type Number = int | float
            type Input = Number | int | string
            type Item = int
            def consume(values: Values): Values { return values }
        """)
        assertEquals(ListType(BuiltInType.INT), file.rootContext.getType("Values"))
        assertEquals(setOf(BuiltInType.INT, BuiltInType.FLOAT, BuiltInType.STRING),
            (file.rootContext.getType("Input") as UnionType).types)
        assertEquals(ListType(BuiltInType.INT), file.rootContext.getVariableTyped<DefType>("consume")!!.returnType)
    }

    @Test
    fun forward_and_mutually_recursive_struct_fields_resolve_before_bodies() {
        val (_, file) = analyze("""
            def echo(value: First): First { return value }
            type First = struct { next: Second }
            type Second = struct { next: First }
        """)
        val first = file.rootContext.getType("First") as StructType
        val second = file.rootContext.getType("Second") as StructType
        assertSame(second, first.parameters.single().second)
        assertSame(first, second.parameters.single().second)
        assertSame(first, file.rootContext.getVariableTyped<DefType>("echo")!!.returnType)
    }

    @Test
    fun recursive_aliases_are_rejected_with_structured_diagnostics() {
        assertError("type A = A", "cyclic alias A -> A", code = 40)
        assertError("""
            type A = B[]
            type B = C
            type C = A | int
        """, "cyclic alias A -> B -> C -> A", code = 40)
        assertError("""
            use <dependency>
            pub type A = dependency::B
        """, "cyclic alias", mapOf("dependency" to "use <main>\npub type B = main::A"), code = 40)
    }

    @Test
    fun unresolved_aliases_and_signature_types_are_rejected_before_codegen() {
        assertError("type A = Missing[]", "Missing")
        assertError("def consume(value: Missing) {}", "Missing")
    }

    @Test
    fun type_and_value_names_are_independent() {
        analyze("""
            type Number = int
            def consume(Number: string): Number {
                let other: Number = 42
                return other
            }
        """)
    }

    @Test
    fun generic_parameters_are_scoped_and_return_types_resolve() {
        val (_, file) = analyze("""
            def first[T](value: T): T { return value }
            def second[T](value: T): T { return value }
            type Container = struct [T] { values: T[] }
        """)
        val first = file.rootContext.getVariableTyped<DefType>("first")!!
        val second = file.rootContext.getVariableTyped<DefType>("second")!!
        assertEquals("main", first.declarationId.module)
        assertEquals(com.kingmang.ixion.types.DeclarationKind.FUNCTION, first.declarationId.kind)
        assertEquals(first.parameters.single().second, first.returnType)
        assertNotEquals(first.returnType, second.returnType)
        assertTrue(((file.rootContext.getType("Container") as StructType).parameters.single().second as ListType).contentType is GenericType)
    }

    @Test
    fun duplicate_and_reserved_type_declarations_are_rejected() {
        assertError("type int = string", "reserved type int", code = 40)
        assertError("type A = int\ntype A = string", "duplicate or reserved type A", code = 40)
        assertError("type A = struct { x: int, x: string }", "duplicate field", code = 40)
        assertError("def same[T, T](value: T) {}", "duplicate or reserved type parameter", code = 40)
        assertError("use <a/model>\nuse <b/model>", "ambiguous module qualifier model",
            mapOf("a/model" to "pub type A = int", "b/model" to "pub type B = string"), code = 40)
    }

    @Test
    fun imported_nominal_identity_is_preserved_and_aliases_can_resolve_forward() {
        val modules = mapOf(
            "left" to "pub type User = struct { id: int }\npub type Users = User[]\npub def identity[T](v: T): T { return v }",
            "right" to "pub type User = struct { id: int }\npub def identity[T](v: T): T { return v }"
        )
        val (_, file) = analyze("""
            use <right>
            use <left>
            type Users = left::Users
            type DirectUsers = left::User[]
            def leftUser(value: left::User): left::User { return value }
        """, modules)
        assertNotEquals(file.rootContext.getType("left::User"), file.rootContext.getType("right::User"))
        assertNotEquals(file.rootContext.getVariableTyped<DefType>("left::identity")!!.returnType,
            file.rootContext.getVariableTyped<DefType>("right::identity")!!.returnType)
        assertEquals(ListType(file.rootContext.getType("left::User")!!), file.rootContext.getType("Users"))
        assertEquals(file.rootContext.getType("Users"), file.rootContext.getType("DirectUsers"))
        assertError("""
            use <left>
            use <right>
            def wrong(value: right::User): left::User { return value }
        """, "ReturnTypeMismatchException", modules)
    }

    @Test
    fun private_types_cannot_leak_through_exported_aliases_or_nested_fields() {
        assertError("""
            type Private = struct { id: int }
            pub def leak(value: Private) {}
        """, "exposes private type", code = 40)
        assertError("""
            type Private = struct { id: int }
            pub type Public = struct { values: Private[] }
        """, "exposes private type", code = 40)
        assertError("""
            type Private = struct { id: int }
            pub type Alias = Private
        """, "exposes private type", code = 40)
    }

    @Test
    fun any_and_list_widening_do_not_bypass_static_checks() {
        assertError("def narrow(value: any): int { return value }", "ReturnTypeMismatchException")
        assertError("def wrong() { let original = [1, 2] let values: any[] = original }", "BadAssignmentException")
        assertError("def wrong() { let original = [1, 2] let values: float[] = original }", "BadAssignmentException")
        assertError("def wrong(value: any): int[] { return value }", "ReturnTypeMismatchException")
    }

    @Test
    fun forward_types_and_calls_execute_on_jvm_and_generated_java() {
        val source = """
            use <std>
            def main() { std::println(read(Box(42))) }
            def read(value: Alias): int { return value.value }
            type Alias = Box
            type Box = struct { value: int }
        """
        assertBackends(source, "42")
    }

    @Test
    fun directed_numeric_and_any_coercions_execute_at_all_typed_boundaries() {
        assertBackends("""
            use <std>
            type Box = struct { value: double }
            def one(): int { return 1 }
            def widen(value: double): double { return value }
            def asAny(value: int): any { return value }
            def main() {
                let mut number: double = one()
                number = 5
                std::println(number)
                std::println(widen(2))
                let anything: any = 7
                std::println(anything)
                std::println(asAny(8))
                std::println(Box(9).value)
                let literal: double = 3.14
                std::println(literal)
            }
        """, "5.0\n2.0\n7\n8\n9.0\n3.140000104904175")
    }

    @Test
    fun union_widening_converts_the_selected_alternative_and_preserves_exact_matches() {
        assertBackends("""
            use <std>
            type Source = int | string
            type Target = float | string
            def make(): Target { return 2 }
            def show(value: Target) {
                case value {
                    float number => std::println(number)
                    string text => std::println(text)
                }
            }
            def main() {
                let first: Target = 3
                show(first)
                show(make())
                show(6)
                let mut value: Target = "waiting"
                value = 4
                show(value)
                let source: Source = 5
                let target: Target = source
                show(target)
                let text: Source = "unchanged"
                show(text)
            }
        """, "3.0\n2.0\n6.0\n4.0\n5.0\nunchanged")
    }

    @Test
    fun imported_structures_and_forward_aliases_execute_on_both_backends() {
        assertBackends("""
            use <std>
            use <domain/models>
            def main() {
                let value: models::Alias = models::Box(42)
                std::println(models::read(value))
            }
        """, "42", mapOf("domain/models" to """
            pub type Alias = Box
            pub def read(value: Alias): int { return value.value }
            pub type Box = struct { value: int }
        """))
    }

    private fun assertBackends(source: String, expected: String, modules: Map<String, String> = emptyMap()) {
        for (java in listOf(false, true)) {
            val root = project(source, modules)
            val api = IxApi()
            val className = if (java) api.compileToJava(root.toString(), "main.ix")
                else api.compile(root.toString(), "main.ix")
            val output = root.resolve(".out")
            if (java) {
                val compiler = ToolProvider.getSystemJavaCompiler()
                assertNotNull(compiler, "Tests require JDK 21, including javac")
                val sources = Files.walk(output).use { stream -> stream.filter { it.toString().endsWith(".java") }.toList() }
                val args = listOf("-classpath", System.getProperty("java.class.path"), "-d", output.toString()) + sources.map { it.toString() }
                assertEquals(0, compiler.run(null, null, null, *args.toTypedArray()))
            }
            URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
                CombinedOutputCapture().use { capture ->
                    loader.loadClass(className).getMethod("main", Array<String>::class.java)
                        .invoke(null, emptyArray<String>() as Any)
                    assertEquals(expected, capture.output!!.trim(), if (java) "Java backend" else "ASM backend")
                }
            }
        }
    }
}
