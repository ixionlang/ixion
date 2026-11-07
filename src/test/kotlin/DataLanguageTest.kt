import com.kingmang.ixion.CombinedOutputCapture
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.exception.IxException.CompilerError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.lang.reflect.Modifier.isFinal as javaModifierFinal
import java.lang.reflect.Modifier.isPrivate as javaModifierPrivate
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

class DataLanguageTest {
    @TempDir lateinit var temporary: Path

    /** Verifies that semantic analysis simplifies the checked AST only when optimization is enabled. */
    @Test fun boolean_algebra_optimization_is_controlled_by_optimize_flag() {
        for (optimize in listOf(false, true)) {
            val root = project("""
                def simplify(a: bool, b: bool): bool { return (a ^ b) ^ a }
                def main() {}
            """)
            val api = IxApi()
            val file = api.parse(root.toString(), "", "program.ix")
            api.analyze(optimize)
            val function = file.statements.first() as com.kingmang.ixion.ast.DefStatement
            val result = (function.body!!.statements.first() as com.kingmang.ixion.ast.ReturnStatement).expression!!
            if (optimize) {
                assertEquals("b", (result as com.kingmang.ixion.ast.IdentifierExpression).identifier.source)
            } else {
                assertTrue(result is com.kingmang.ixion.ast.BinaryExpression)
            }
        }
    }

    /** Checks JVM and Java results, short-circuit guards, and call counts with optimization on and off. */
    @Test fun boolean_algebra_optimization_preserves_both_backends_and_evaluation_order() {
        val calls = (0..15).joinToString("\n") { mask ->
            val args = (0..3).joinToString(", ") { bit -> (mask and (1 shl bit) != 0).toString() }
            "std::println(poly($args)) std::println(complement($args))"
        }
        val truthTable = (0..15).flatMap { mask ->
            val a = mask and 1 != 0
            val b = mask and 2 != 0
            val c = mask and 4 != 0
            val d = mask and 8 != 0
            listOf(((a && b) xor (c && d)).toString(), (!(a xor b)).toString())
        }.joinToString("\n")
        val source = """
            use <std>
            def poly(a: bool, b: bool, c: bool, d: bool): bool {
                return ((((a && b) ^ (c && d)) ^ a) ^ a)
            }
            def complement(a: bool, b: bool, c: bool, d: bool): bool {
                return !((((a ^ b) ^ c) ^ c))
            }
            def effect(): bool { std::println("effect") return true }
            def short(a: bool): bool { return a || effect() }
            def guarded(x: int): bool { return x != 0 && 10 / x > 2 }
            def main() {
                $calls
                std::println(short(true))
                std::println(short(false))
                std::println(effect() ^ effect())
                std::println(guarded(0))
            }
        """
        val expected = "$truthTable\ntrue\neffect\ntrue\neffect\neffect\nfalse\nfalse"
        for (optimize in listOf(false, true)) execute(source, expected, optimize = optimize)
    }

    private fun project(source: String, modules: Map<String, String> = emptyMap()): Path {
        val root = Files.createTempDirectory(temporary, "data")
        Files.writeString(root.resolve("program.ix"), source.trimIndent())
        modules.forEach { (name, text) -> root.resolve("$name.ix").also { Files.createDirectories(it.parent); Files.writeString(it, text.trimIndent()) } }
        return root
    }
    private fun compile(root: Path, java: Boolean, optimize: Boolean = false): String {
        val api = IxApi()
        val name = if (java) api.compileToJava(root.toString(), "program.ix", optimize) else api.compile(root.toString(), "program.ix", optimize)
        val output = root.resolve(".out")
        if (java) {
            val sources = Files.walk(output).use { stream -> stream.filter { it.toString().endsWith(".java") }.toList() }
            val args = listOf("-classpath", System.getProperty("java.class.path"), "-d", output.toString()) + sources.map { it.toString() }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()), "Generated Java must compile")
        }
        return name
    }
    private fun execute(source: String, expected: String, modules: Map<String, String> = emptyMap(), optimize: Boolean = false) {
        for (java in listOf(false, true)) {
            val root = project(source, modules)
            val name = compile(root, java, optimize)
            val output = root.resolve(".out")
            URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
                CombinedOutputCapture().use { capture ->
                    loader.loadClass(name).getMethod("main", Array<String>::class.java).invoke(null, emptyArray<String>() as Any)
                    assertEquals(expected.trim(), capture.output!!.trim(), if (java) "Java" else "ASM")
                }
            }
        }
    }
    private fun reject(source: String, category: String) {
        val root = project(source)
        val api = IxApi()
        api.parse(root.toString(), "", "program.ix")
        CombinedOutputCapture().use {
            val error = assertThrows(CompilerError::class.java) { api.analyze() }
            assertTrue(error.errorData!!.filterNotNull().any { category in it.message.orEmpty() }, error.errorData.toString())
            assertTrue(error.errorData.filterNotNull().all { it.line > 0 })
        }
    }
    @Test fun recursive_adt_and_nested_case() {
        execute(Files.readString(Path.of("src/test/resources/conformance/valid/recursive-adt.ix")), "3")
    }
    @Test fun option_result_generic_constructors_and_nested_patterns() {
        execute("""
            use <std>
            def describe(value: Result[Option[int], string]): int {
                return case value {
                    Result::Ok(Option::Some(number)) => number,
                    Result::Ok(Option::None) => 0,
                    Result::Err(_) => -1,
                }
            }
            def main() {
                let first: Result[Option[int], string] = Result::Ok(Option::Some(42))
                std::println(describe(first))
                std::println(describe(Result::Ok(Option::None)))
                std::println(describe(Result::Err("failed")))
                let absent = Option[int]::None
                std::println(case absent { Option::Some(x) => x, Option::None => 7, })
            }
        """, "42\n0\n-1\n7")
    }
    @Test fun guard_order_single_subject_evaluation_and_early_return() {
        execute("""
            use <std>
            def subject(): Option[int] { std::println("subject") return Option::Some(3) }
            def guard(value: int): bool { std::println(value) return false }
            def compute(): int {
                let ignored = case subject() {
                    Option::Some(value) if guard(value) => 1,
                    Option::Some(value) => { return value + 10 },
                    Option::None => 0,
                }
                return ignored
            }
            def main() { std::println(compute()) }
        """, "subject\n3\n13")
    }
    @Test fun generic_functions_reify_adt_parameters() {
        execute("""
            use <std>
            def wrap[T](value: T): Option[T] { return Option::Some(value) }
            def unwrap[T](value: Option[T], fallback: T): T {
                return case value { Option::Some(x) => x, Option::None => fallback, }
            }
            def main() {
                std::println(unwrap(wrap(42), 0))
                std::println(unwrap(wrap("ok"), "fallback"))
                std::println(unwrap(Option[int]::None, 7))
            }
        """, "42\nok\n7")
    }
    @Test fun tuples_structs_updates_and_list_patterns() {
        execute("""
            use <std>
            type Point = struct { x: int, y: int }
            type Box[T] = struct { value: T }
            type Pair[A, B] = (A, B)
            def head(values: int[]): int {
                return case values { [] => 0, [first, ..rest] => first + std::len(rest), }
            }
            def main() {
                let point = Point(1, 2)
                let changed = point with { x = 10 }
                std::println(case changed { Point { x: x, y: y } => x + y, })
                std::println(point.x)
                let box = Box[int](42)
                std::println(case box { Box { value: value } => value, })
                std::println(box.value)
                let pair: Pair[int, string] = (7, "seven")
                std::println(case pair { (number, _) => number, })
                std::println(head([]))
                std::println(head([5, 6, 7]))
                std::println(case () { () => 8, })
            }
        """, "12\n1\n42\n42\n7\n0\n7\n8")
    }
    @Test fun reified_generic_types_in_union_and_any_patterns() {
        execute("""
            use <std>
            def describe(value: Option[int] | Option[string]): string {
                return case value { Option[int] number => "integer", Option[string] text => "text", }
            }
            def inspect(value: any): int {
                return case value { Option[int] number => 1, _ => 0, }
            }
            def main() {
                std::println(describe(Option::Some(42)))
                std::println(describe(Option::Some("ok")))
                std::println(inspect(Option::Some(1)))
                std::println(inspect(Option::Some("s")))
            }
        """, "integer\ntext\n1\n0")
        reject("def f(value: any): int { return case value { int number => 1, } }", "match.non_exhaustive")
    }
    @Test fun mutable_outer_variables_and_short_circuit_keep_case_evaluation_order() {
        execute("""
            use <std>
            def effect(): bool { std::println("unexpected") return true }
            def main() {
                let mut total = 1
                let flag = false && case true { true => effect(), false => false, }
                let result = case true { true => { total = 5 total }, false => 0, }
                std::println(total)
                std::println(result)
                std::println(flag)
            }
        """, "5\n5\nfalse")
    }
    @Test fun recursive_generic_functions_do_not_generate_infinite_specializations() {
        execute("""
            use <std>
            def grow[T](value: T, depth: int): int {
                if depth == 0 { return 42 }
                return grow(Option::Some(value), depth - 1)
            }
            def main() { std::println(grow(0, 4)) }
        """, "42")
    }
    @Test fun exported_recursive_adt_and_aliases() {
        execute("""
            use <std>
            use <domain/tree>
            def size(value: tree::Tree[int]): int {
                return case value {
                    tree::Tree::Leaf(_) => 1,
                    tree::Tree::Branch(left, right) => size(left) + size(right),
                }
            }
            def main() {
                let tree: tree::Tree[int] = tree::Tree::Branch(tree::Tree::Leaf(1), tree::Tree::Leaf(2))
                std::println(size(tree))
            }
        """, "2", mapOf("domain/tree" to """
            pub type Tree[T] = enum { Leaf(value: T), Branch(left: Tree[T], right: Tree[T]), }
        """))
    }
    @Test fun function_types_lambdas_capture_snapshots_and_support_arbitrary_arity() {
        execute("""
            use <std>
            type Handler = (int) => int
            def call(f: Handler): int { return f(3) }
            def main() {
                let mut offset = 1
                let captured: () => int = lambda () { return offset }
                offset = 9
                std::println(captured())
                let double: Handler = lambda (number) { return number * 2 }
                std::println(call(double))
                let many: (int, int, int, int, int) => int = lambda (a, b, c, d, e) { return a + b + c + d + e }
                std::println(many(1, 2, 3, 4, 5))
                let tagged: any = double
                std::println(case tagged { Handler function => function(4), _ => 0, })
            }
        """, "1\n6\n15\n8")
    }
    @Test fun lambdas_capture_reified_types_and_case_returns_stay_in_the_lambda() {
        execute("""
            use <std>
            def make[T](value: T): () => Option[T] {
                return lambda (): Option[T] { return Option::Some(value) }
            }
            def main() {
                let create = make(42)
                let read: (Option[int]) => int = lambda (value) {
                    return case value {
                        Option::Some(number) => { return number },
                        Option::None => 0,
                    }
                }
                std::println(read(create()))
            }
        """, "42")
    }
    @Test fun negative_recursion_and_invalid_type_arity_are_diagnosed() {
        reject("type Bad = enum { Wrap(consume: (Bad) => int), }", "negative recursive occurrence")
        reject("type Negative[T] = struct { consume: (T) => int } type Bad = enum { Wrap(value: Negative[Bad]), }", "negative recursive occurrence")
        reject("def f(value: Option[int, string]) {}", "expects 1 arguments")
        reject("def f(value: Option) {}", "expects 1 arguments")
        reject("def f() { let mut n = 1 let bad = lambda (): int { n = 2 return n } }", "mutation.immutable_binding")
    }
    @Test fun optimized_data_expressions_preserve_checked_match_plans() {
        execute("""
            use <std>
            def id[T](value: T): T { return value }
            def main() {
                let value: Option[int] = Option::Some(id(1 + 2))
                std::println(case value { Option::Some(number) => number, Option::None => 0, })
            }
        """, "3", optimize = true)
    }

    @Test fun generic_widening_happens_before_erasure_and_function_updates() {
        execute("""
            use <std>
            type Box[T] = struct { value: T }
            def id[T](value: T): T { return value }
            def first[T](left: T, right: T): T { return left }
            def wrap[T](value: T): Option[T] { return Option::Some(value) }
            def main() {
                std::println(id[float](1))
                std::println(first(1, 2.0f))
                let box = Box[float](3)
                std::println(box.value)
                let after = box with { value = 4 }
                std::println(after.value)
                let option = wrap[float](5)
                std::println(case option { Option::Some(value) => value, Option::None => 0.0f, })
            }
        """, "1.0\n1.0\n3.0\n4.0\n5.0")
    }
    @Test fun unit_is_a_data_value_and_never_contributes_no_result() {
        execute("""
            use <std>
            def nothing(): unit { return () }
            def fail(): never { std::panic("not reached") }
            def choose(value: bool): int {
                return case value { true => 42, false => fail(), }
            }
            def main() {
                let value: Option[unit] = Option::Some(nothing())
                std::println(case value { Option::Some(()) => "unit", Option::None => "none", })
                let callback: () => unit = lambda () { return () }
                std::println(callback())
                std::println(choose(true))
            }
        """, "unit\n()\n42")
        reject("use <std> def main() { std::panic(1) }", "call.argument_type")
    }
    @Test fun let_declarations_and_local_reassignment() {
        execute("""
            use <std>
            def main() {
                let step = 1 + 2
                let mut total: int = 2 + 3
                total += step
                total++
                std::println(total)
            }
        """, "9", optimize = true)
        reject("def main() { let value = 1 value = 2 }", "MutabilityException")
        reject("let mut value = 1", "mutation.module_var")
        reject("pub let mut value = 1", "mutation.module_var")
        reject("def main() { const value = 1 }", "name.unknown: const")
        reject("def main() { var value = 1 }", "name.unknown: var")
    }

    @Test fun immutable_product_and_append_conformance() {
        execute(Files.readString(Path.of("src/test/resources/conformance/valid/immutable-update.ix")), "1\n10")
        execute(Files.readString(Path.of("src/test/resources/conformance/valid/list-aliasing.ix")), "2\n3")
        reject(Files.readString(Path.of("src/test/resources/conformance/invalid/field-assignment.ix")), "mutation.field")
        reject("use <std> def main() { let values = [1] std::push(values, 2) }", "list.immutable")
    }

    @Test fun generic_union_tags_are_canonical_inside_adt_values() {
        execute("""
            use <std>
            def make[T](value: T): Option[T | string] { return Option::Some(value) }
            def main() {
                let value = make(42)
                std::println(case value {
                    Option::Some(int number) => number,
                    Option::Some(string text) => 0,
                    Option::None => -1,
                })
            }
        """, "42")
        reject("def unsafe[T](value: T | int): int { return case value { T x => 1, int x => 2, } }", "pattern.overlapping_generic_union")
    }

    @Test fun nested_blocks_do_not_leak_bindings_and_patterns_can_shadow_outer_values() {
        execute("""
            use <std>
            def main() {
                let mut number = 7
                let result = case Option::Some(42) {
                    Option::Some(number) => {
                        if number > 0 { let local = 1 std::println(local) }
                        else { let local = 2 std::println(local) }
                        number
                    },
                    Option::None => 0,
                }
                std::println(result)
                std::println(number)
                let one: (int,) = (8,)
                std::println(case one { (value,) => value, })
            }
        """, "1\n42\n7\n8")
        reject("def main() { if true { let inner = 1 } let outside = inner }", "name.unknown")
    }
    @Test fun functional_updates_evaluate_base_and_fields_once_in_source_order() {
        execute("""
            use <std>
            type Point = struct { x: int, y: int }
            def base(): Point { std::println("base") return Point(1, 2) }
            def field(value: int): int { std::println(value) return value }
            def main() {
                let result = base() with { y = field(20), x = field(10) }
                std::println(case result { Point { x: x, y: y } => x + y, })
            }
        """, "base\n20\n10\n30")
    }

    @Test fun literal_and_structural_type_patterns_cover_all_supported_domains() {
        execute("""
            use <std>
            type Direction = enum { North, South }
            type Number = int | never
            def value(number: Number): int { return number }
            def main() {
                std::println(case Direction::North { Direction::North => 1, Direction::South => 2, })
                std::println(case 'x' { 'x' => 3, _ => 0, })
                std::println(case "a\nb" { "a\nb" => 4, _ => 0, })
                let numbers: any = [1, 2]
                std::println(case numbers { int[] values => std::len(values), _ => 0, })
                let function: any = lambda (number: int): int { return number + 1 }
                std::println(case function { (int) => int call => call(4), _ => 0, })
                std::println(value(7))
                let negative = (-8,)
                std::println(case negative { (number,) => number, })
            }
        """, "1\n3\n4\n2\n5\n7\n-8")
    }

    @Test fun return_expression_may_start_on_the_next_line() {
        execute("""
            use <std>
            def number(value: bool): int {
                return
                    case value { true => 42, false => 0, }
            }
            def main() { std::println(number(true)) }
        """, "42")
    }

    @Test fun exhaustive_case_statements_return_from_the_enclosing_function() {
        execute("""
            use <std>
            def number(value: Option[int]): int {
                case value { Option::Some(number) => { return number }, Option::None => { return 0 }, }
            }
            def main() {
                let callback: (Option[int]) => int = lambda (value) {
                    case value { Option::Some(number) => { return number }, Option::None => { return 0 }, }
                }
                let unit: (unit) => unit = lambda (value) { return value }
                std::println(number(Option::Some(42)))
                std::println(callback(Option[int]::None))
                std::println(unit(()))
            }
        """, "42\n0\n()")
    }
    @Test fun loops_inside_case_blocks_keep_types_and_effect_order() {
        execute("""
            use <std>
            def main() {
                let mut total = 0
                let value = case true {
                    true => { while false { total = 100 } for item : [1, 2, 3] { total += item } total },
                    false => 0,
                }
                std::println(value)
                for character : "ab" {
                    std::println(case character { 'a' => 1, _ => 2, })
                }
            }
        """, "6\n1\n2")
    }

    @Test fun both_cli_targets_compile_and_execute_the_new_data_language() {
        val examples = mapOf(
            "recursive-adt.ix" to "3",
            "functional-lists.ix" to "10\n4\nabsent\n104\n[1, 2, 3]"
        )
        for ((fixture, expected) in examples) for (target in listOf(emptyList(), listOf("--java"))) {
            val source = Files.readString(Path.of("src/test/resources/conformance/valid/$fixture"))
            val root = project(source)
            val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "com.kingmang.ixion.Ixion") + target + "program.ix"
            val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "CLI did not terminate")
                val output = process.inputStream.bufferedReader().readText().replace("\r\n", "\n")
                assertEquals(0, process.exitValue(), output)
                assertTrue(output.contains(expected), output)
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
    }

    @Test fun safe_list_indexing_and_updates_cover_all_boundaries() {
        execute("""
            use <std>
            def read(value: Option[int]): int {
                return case value { Option::Some(x) => x, Option::None => -1, }
            }
            def main() {
                let xs = [10, 20]
                std::println(read(xs[0]))
                std::println(read(xs[1]))
                std::println(read(xs[-1]))
                std::println(read(xs[2]))
                std::println(read(xs[2147483647]))
                std::println(read(std::get(xs, 0)))
                let empty: int[] = []
                std::println(read(empty[0]))
                std::println(case std::updated(xs, 1, 42) {
                    Option::Some(ys) => case ys[1] { Option::Some(x) => x, Option::None => -1, },
                    Option::None => -1,
                })
                std::println(case std::updated(xs, -1, 0) { Option::None => true, _ => false, })
                std::println(case std::updated(xs, 2, 0) { Option::None => true, _ => false, })
                std::println(xs)
            }
        """, "10\n20\n-1\n-1\n-1\n10\n-1\n42\ntrue\ntrue\n[10, 20]")
    }

    @Test fun functional_list_api_infers_callback_types_and_preserves_sources() {
        execute("""
            use <std>
            def main() {
                let xs = [1, 2, 3]
                let joined = std::concat(xs, [4])
                let mapped = std::map(joined, lambda (x) { return x * 2 })
                let filtered = std::filter(mapped, lambda (x) { return x > 4 })
                let total = std::fold(filtered, 10, lambda (sum, x) { return sum + x })
                std::println(xs)
                std::println(joined)
                std::println(mapped)
                std::println(filtered)
                std::println(total)
                std::println(std::len(filtered))
                std::println(std::append(xs, 5))
                std::println(xs)
            }
        """, "[1, 2, 3]\n[1, 2, 3, 4]\n[2, 4, 6, 8]\n[6, 8]\n24\n2\n[1, 2, 3, 5]\n[1, 2, 3]")
    }

    @Test fun callbacks_run_once_per_element_in_order_and_empty_lists_do_not_call_them() {
        execute("""
            use <std>
            def main() {
                let xs = [1, 2, 3]
                std::println(std::map(xs, lambda (x) { std::println(x) return x + 1 }))
                std::println(std::filter(xs, lambda (x) { std::println(x) return x > 1 }))
                std::println(std::fold(xs, 0, lambda (sum, x) { std::println(x) return sum * 10 + x }))
                let empty: int[] = []
                std::println(std::map(empty, lambda (x) { std::println("unexpected") return x }))
                std::println(std::filter(empty, lambda (x) { std::println("unexpected") return true }))
                std::println(std::fold(empty, 42, lambda (sum, x) { std::println("unexpected") return sum + x }))
                std::println(std::concat(empty, xs))
                std::println(std::concat(xs, empty))
            }
        """, "1\n2\n3\n[2, 3, 4]\n1\n2\n3\n[2, 3]\n1\n2\n3\n123\n[]\n[]\n42\n[1, 2, 3]\n[1, 2, 3]")
    }

    @Test fun generic_list_results_keep_reified_tags_including_empty_and_nested_lists() {
        execute("""
            use <std>
            def wrap[T](values: T[]): Option[T][] {
                return std::map(values, lambda (x) { return Option::Some(x) })
            }
            def first[T](values: T[]): Option[T] { return values[0] }
            def main() {
                let xs = wrap([7])
                std::println(case first(xs) { Option::Some(Option::Some(x)) => x, _ => 0, })
                let empty: int[] = []
                let strings = std::map(empty, lambda (x) { return "text" })
                let opaque: any = strings
                std::println(case opaque { string[] s => std::len(s), _ => -1, })
                let absent: any = strings[0]
                std::println(case absent { Option[string] value => true, _ => false, })
                let nested = std::map([1, 2], lambda (x) { return [x] })
                std::println(case nested[1] { Option::Some([x]) => x, _ => 0, })
                let widened = std::map[int, float]([1], lambda (x) { return x })
                std::println(case widened[0] { Option::Some(x) => x, Option::None => 0.0f, })
                let changed: any = std::updated(widened, 0, 3)
                std::println(case changed { Option[float[]] value => true, _ => false, })
            }
        """, "7\n0\ntrue\n2\n1.0\ntrue")
    }

    @Test fun iteration_rest_and_closures_observe_immutable_list_snapshots() {
        execute("""
            use <std>
            def main() {
                let mut xs = [1, 2]
                let saved = xs
                let read = lambda () { return xs }
                for x : xs {
                    xs = std::append(xs, x + 10)
                    std::println(x)
                }
                std::println(xs)
                std::println(saved)
                std::println(read())
                case xs { [head, ..tail] => { std::println(std::append(tail, 99)) std::println(tail) }, [] => (), }
                std::println(xs)
            }
        """, "1\n2\n[1, 2, 11, 12]\n[1, 2]\n[1, 2]\n[2, 11, 12, 99]\n[2, 11, 12]\n[1, 2, 11, 12]")
    }

    @Test fun safe_index_and_update_arguments_are_evaluated_once_in_source_order() {
        execute("""
            use <std>
            def source(): int[] { std::println("source") return [4] }
            def index(): int { std::println("index") return 0 }
            def value(): int { std::println("value") return 8 }
            def main() {
                std::println(case source()[index()] { Option::Some(x) => x, Option::None => -1, })
                std::println(case std::updated(source(), index(), value()) { Option::Some(xs) => std::len(xs), _ => 0, })
            }
        """, "source\nindex\n4\nsource\nindex\nvalue\n1")
    }

    @Test fun contextual_empty_lists_and_function_values_work_with_the_functional_api() {
        execute("""
            use <std>
            def main() {
                let joined: int[] = std::concat([], [])
                let absent: Option[int] = std::get([], 0)
                let indexed: Option[int] = [][0]
                let mapped = std::map([], lambda (x: int) { return x + 1 })
                let filtered = std::filter([], lambda (x: int) { return x > 0 })
                let total = std::fold([], 7, lambda (sum: int, x: int) { return sum + x })
                let mapper: (int) => int = lambda (x) { return x * 3 }
                std::println(std::map([1, 2], mapper))
                std::println(std::len[int]([]) + std::len(joined) + std::len(mapped) + std::len(filtered))
                std::println(case absent { Option::None => case indexed { Option::None => total, _ => 0, }, _ => 0, })
                let callbacks = [mapper]
                std::println(case callbacks[0] { Option::Some(f) => f(4), _ => 0, })
                let units = std::map([1], lambda (x) { return () })
                std::println(case units[0] { Option::Some(()) => true, Option::None => false, })
            }
        """, "[3, 6]\n0\n7\n12\ntrue", optimize = true)
    }

    @Test fun list_api_rejects_mutation_implicit_unwrapping_and_invariant_mismatches() {
        reject("def main() { let mut xs = [1] xs[0] = 2 }", "mutation.index")
        reject("use <std> def main() { let xs = [1] std::pop(xs) }", "list.immutable")
        reject("def value(): int { return [1][0] }", "ReturnTypeMismatchException")
        reject("use <std> def main() { let xs = [1] let ys = [1.0f] std::concat(xs, ys) }", "call.argument_type")
        reject("use <std> def main() { std::filter([1], lambda (x) { return x }) }", "ReturnTypeMismatchException")
        reject("use <std> def main() { std::map([1], lambda (x: string) { return x }) }", "call.argument_type")
        reject("use <std> def main() { std::get([1], true) }", "call.argument_type")
        reject("use <std> def main() { std::len(42) }", "type.cannot_infer")
        reject("use <std> def main() { std::concat([], []) }", "type.empty_list")
    }

    @Test fun assignment_branches_have_unit_results_and_cannot_be_used_as_values() {
        execute("""
            use <std>
            def main() {
                let mut number = 1
                let first = case true { true => { number = 2 }, false => (), }
                let second = case true { _ => number = 3, }
                std::println(first)
                std::println(second)
                std::println(number)
            }
        """, "()\n()\n3")
        reject("def main() { let mut number = 1 let bad = (number = 2) }", "assignment.expression")
    }

    @Test fun generated_products_are_final_with_private_final_fields() {
        val source = "type Point = struct { x: int, y: int } def create(): Point { return Point(1, 2) } def main() {}"
        for (java in listOf(false, true)) {
            val root = project(source)
            val name = compile(root, java)
            URLClassLoader(arrayOf(root.resolve(".out").toUri().toURL()), javaClass.classLoader).use { loader ->
                val module = loader.loadClass(name)
                val value = module.getMethod("_create").invoke(null)
                val product = value.javaClass
                assertTrue(javaModifierFinal(product.modifiers))
                assertEquals(module, product.enclosingClass)
                assertNull(product.enclosingMethod)
                assertEquals(2, product.declaredFields.size)
                assertTrue(product.declaredFields.all { javaModifierPrivate(it.modifiers) && javaModifierFinal(it.modifiers) })
                assertEquals(1, (value as com.kingmang.ixion.runtime.IxDataValue).ixField(0))
            }
        }
    }

    @Test fun empty_types_have_legal_layouts_and_do_not_add_missing_patterns() {
        execute("""
            use <std>
            type Empty = struct { impossible: never }
            def eliminate(value: never): int { return case value {} }
            def inspect(value: Option[never]): int { return case value { Option::None => 42, } }
            def main() { std::println(inspect(Option[never]::None)) }
        """, "42")
        reject("def f(value: Option[never]): int { return case value { Option::Some(x) => 1, Option::None => 0, } }", "match.unreachable")
    }

    @Test fun all_product_combinations_and_list_shapes_must_be_covered() {
        reject("def f(value: (bool, bool)): int { return case value { (true, _) => 1, (false, true) => 2, } }", "match.non_exhaustive")
        reject("def f(value: int[]): int { return case value { [] => 0, [first] => first, } }", "match.non_exhaustive")
        reject("def f(value: Option[int]): int { return case value { Option::Some(_) => 1, Option::Some(x) if x > 0 => 2, Option::None => 0, } }", "match.unreachable")
        reject("def f(value: bool): int { if value { return 1 } }", "ReturnTypeMismatchException")
    }

    @Test fun nested_bool_coverage_and_guards_are_checked() {
        reject(Files.readString(Path.of("src/test/resources/conformance/invalid/incomplete-pattern.ix")), "match.non_exhaustive")
        reject("""
            def f(value: Option[int]): int {
                return case value { Option::Some(x) if true => x, Option::None => 0, }
            }
        """, "match.non_exhaustive")
        reject("def f(value: bool): int { return case value { _ => 1, true => 2, } }", "match.unreachable")
        reject("def f(value: Option[int]): int { return case value { Option::Some(x) if 1 => x, Option::None => 0, } }", "match.guard_type")
    }
    @Test fun invalid_variants_payloads_and_bindings_fail_before_codegen() {
        reject("def main() { let x: Option[int] = Option::Some(\"bad\") }", "constructor.field_type")
        reject("def main() { let x = Option::None }", "type.cannot_infer")
        reject("def main() { let x = Option::Some(1, 2) }", "constructor.arity")
        reject("def f(value: Option[int]): int { return case value { Option::Unknown => 0, _ => 1, } }", "pattern.unknown_variant")
        reject("type Pair = enum { Both(a: int, b: int) } def f(value: Pair): int { return case value { Pair::Both(x, x) => x, } }", "pattern.duplicate_binding")
    }
}
