import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.exception.IxException.CompilerError
import com.kingmang.ixion.modules.HttpServerModule
import com.kingmang.ixion.runtime.ixfunction.IxFunction1
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.tools.ToolProvider

/** Real loopback requests exercise the native ABI, Ixion router and both code generators. */
class HttpServerTest {
    @TempDir lateinit var temporary: Path

    @Test fun server_via_bytecode() = exercise(false)
    @Test fun server_via_java() = exercise(true)

    @Test fun configuration_and_bind_errors_are_results() {
        val unused = IxFunction1<com.kingmang.ixion.runtime.TupleValue, com.kingmang.ixion.runtime.TupleValue> {
            error("No request expected")
        }
        val invalid = HttpServerModule.serve("127.0.0.1", -1, 32, 1, 4, 0, unused)
        assertEquals("Err", invalid.variant)
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val result = HttpServerModule.serve("127.0.0.1", occupied.localPort, 32, 1, 4, 0, unused)
            assertEquals("Err", result.variant)
        }
    }

    @Test fun native_handler_signature_is_checked() {
        Files.writeString(temporary.resolve("bad.ix"), """
            use <http_server>
            def main() {
                http_server::serve("127.0.0.1", 8080, 32, 1, 4, 0, lambda (request) { return "wrong" })
            }
        """.trimIndent())
        assertThrows(CompilerError::class.java) { IxApi().compile(temporary.toString(), "bad.ix") }
    }

    private fun exercise(java: Boolean) {
        val root = Files.createDirectory(temporary.resolve(if (java) "java" else "asm"))
        Files.createDirectories(root.resolve("web"))
        Files.list(Path.of("web")).use { paths ->
            paths.filter { it.toString().endsWith(".ix") }.forEach { Files.copy(it, root.resolve("web").resolve(it.fileName)) }
        }
        Files.createDirectories(root.resolve("examples"))
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val source = Files.readString(Path.of("examples/server.ix"))
            .replace("use <std>", "use <std>\nuse <strings>")
            .replace("types::config(\"127.0.0.1\", 8080)",
                "types::ServerConfig(\"127.0.0.1\", $port, 32, 2, 4, 0)")
            .replace("request.body) })", "request.body) })," + extraRoutes)
        Files.writeString(root.resolve("examples/server.ix"), source)
        val api = IxApi()
        val entry = if (java) api.compileToJava(root.toString(), "examples/server.ix")
            else api.compile(root.toString(), "examples/server.ix")
        val output = root.resolve(".out")
        if (java) {
            val sources = Files.walk(output).use { paths -> paths.filter { it.toString().endsWith(".java") }.toList() }
            val args = listOf("-classpath", System.getProperty("java.class.path"), "-d", output.toString()) + sources.map { it.toString() }
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()))
        }
        URLClassLoader(arrayOf(output.toUri().toURL()), javaClass.classLoader).use { loader ->
            val failure = AtomicReference<Throwable>()
            val main = loader.loadClass(entry).getMethod("main", Array<String>::class.java)
            val thread = Thread({
                try { main.invoke(null, emptyArray<String>() as Any) } catch (error: Throwable) { failure.set(error) }
            }, "ixion-server-test")
            thread.isDaemon = true
            thread.start()
            try {
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build().use { client ->
                    val base = "http://127.0.0.1:$port"
                    awaitReady(client, base, thread, failure)
                    fun request(path: String, method: String = "GET", body: ByteArray? = null): HttpResponse<String> =
                        client.send(HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(5))
                            .method(method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
                            .build(), HttpResponse.BodyHandlers.ofString())

                    assertEquals("Hello, Ixion!", request("/").body())
                    assertEquals("ok", request("/health").body())
                    val user = request("/users/42?ignored=1")
                    assertEquals(200, user.statusCode())
                    assertEquals("application/json", user.headers().firstValue("Content-Type").orElseThrow())
                    assertEquals("""{"id":"42"}""", user.body())
                    assertEquals("""{"id":"current-user"}""", request("/users/me").body(), "Literal route precedes parameter route")
                    assertEquals("""{"id":"Привет"}""", request("/users/%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82").body())
                    assertEquals("""{"id":"a+b"}""", request("/users/a+b").body())
                    assertEquals("""{"id":"a\"b"}""", request("/users/a%22b").body())
                    assertEquals(404, request("/users/").statusCode())
                    assertEquals(404, request("/users/42/extra").statusCode())
                    assertEquals(404, request("/users/42/").statusCode())
                    assertEquals(404, request("/users/a%2Fb").statusCode())
                    val userHead = request("/users/42", "HEAD")
                    assertEquals(200, userHead.statusCode())
                    assertEquals("", userHead.body())
                    assertEquals("11", userHead.headers().firstValue("Content-Length").orElseThrow())
                    val userPost = request("/users/42", "POST")
                    assertEquals(405, userPost.statusCode())
                    assertEquals("GET, HEAD", userPost.headers().firstValue("Allow").orElseThrow())
                    assertEquals("""{"id":"12","post":"34"}""", request("/users/12/posts/34").body())
                    val validJson = listOf("null", "true", "false", "-0", "9007199254740993", "1.2e+30",
                        "\"hi\\n🌍\"", "[1,true,null]", """{"a":[1,{"x":"я"}]}""")
                    for (body in validJson) {
                        val json = request("/json/echo", "POST", body.toByteArray())
                        assertEquals(200, json.statusCode(), body)
                        assertEquals(body, json.body())
                        assertEquals("application/json", json.headers().firstValue("Content-Type").orElseThrow())
                    }
                    for (body in listOf("", "{", "[1,]", "01", "NaN", "true false", """{"x":1,"x":2}""")) {
                        val invalid = request("/json/echo", "POST", body.toByteArray())
                        assertEquals(400, invalid.statusCode(), body)
                        assertEquals("""{"error":"Invalid JSON"}""", invalid.body())
                    }
                    assertEquals("""[null,true,123,"hi",{},[]]""", request("/json/build").body())
                    assertEquals("[1,2]", request("/json/field", "POST", """{"value":[1,2]}""".toByteArray()).body())
                    assertEquals(404, request("/json/field", "POST", "{}".toByteArray()).statusCode())
                    assertEquals(500, request("/json/invalid").statusCode())
                    val echo = request("/echo", "POST", "Привет, Ixion! 🌍".toByteArray())
                    assertEquals(200, echo.statusCode())
                    assertEquals("Привет, Ixion! 🌍", echo.body())
                    assertEquals(echo.body().toByteArray().size.toString(), echo.headers().firstValue("Content-Length").orElseThrow())
                    assertEquals("text/plain; charset=UTF-8", echo.headers().firstValue("Content-Type").orElseThrow())
                    assertEquals(404, request("/health/extra").statusCode())
                    val disallowed = request("/echo")
                    assertEquals(405, disallowed.statusCode())
                    assertEquals("POST", disallowed.headers().firstValue("Allow").orElseThrow())
                    assertEquals("GET, HEAD", request("/health", "POST").headers().firstValue("Allow").orElseThrow())
                    val head = request("/", "HEAD")
                    assertEquals(200, head.statusCode())
                    assertEquals("", head.body())
                    assertEquals("13", head.headers().firstValue("Content-Length").orElseThrow())
                    assertEquals("explicit", request("/explicit", "HEAD").headers().firstValue("X-Handler").orElseThrow())
                    assertEquals(413, request("/echo", "POST", ByteArray(33) { 65 }).statusCode())
                    assertEquals(200, request("/echo", "POST", ByteArray(32) { 65 }).statusCode())
                    val chunked = client.send(HttpRequest.newBuilder(URI("$base/echo"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArray(33) { 65 }.inputStream() }).build(),
                        HttpResponse.BodyHandlers.ofString())
                    assertEquals(413, chunked.statusCode(), "Body limit also applies without Content-Length")
                    assertEquals(400, request("/echo", "POST", byteArrayOf(0xc3.toByte(), 0x28)).statusCode())
                    assertEquals(500, request("/panic").statusCode())
                    assertEquals(500, request("/bad-status").statusCode())
                    assertEquals(500, request("/bad-header").statusCode())
                    assertEquals("", request("/empty").body())
                    val noContent = request("/no-content")
                    assertEquals(204, noContent.statusCode())
                    assertEquals("", noContent.body())
                    val inspect = client.send(HttpRequest.newBuilder(URI("$base/inspect?tag=one&tag=two&greeting=hello+world&flag"))
                        .timeout(Duration.ofSeconds(5)).header("X-Test", "first").header("X-Test", "second").build(),
                        HttpResponse.BodyHandlers.ofString())
                    assertEquals(listOf("one", "two"), inspect.headers().allValues("X-tag"))
                    assertEquals("hello world", inspect.headers().firstValue("X-greeting").orElseThrow())
                    assertTrue(inspect.body().contains("first;second;"), inspect.body())
                    val pending = (1..12).map { client.sendAsync(HttpRequest.newBuilder(URI("$base/health"))
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()) }
                    CompletableFuture.allOf(*pending.toTypedArray()).get(10, TimeUnit.SECONDS)
                    pending.forEach { assertEquals("ok", it.join().body()) }
                    assertEquals("ok", request("/health").body(), "Server must survive failed requests")
                }
            } finally {
                thread.interrupt()
                thread.join(5000)
            }
            assertFalse(thread.isAlive, "serve must stop on interruption")
            assertNull(failure.get(), "Server thread failed: ${failure.get()}")
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress("127.0.0.1", port))
            }
        }
    }

    private fun awaitReady(client: HttpClient, base: String, thread: Thread, failure: AtomicReference<Throwable>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            assertTrue(thread.isAlive, "Server stopped before startup: ${failure.get()}")
            try {
                val response = client.send(HttpRequest.newBuilder(URI("$base/health")).timeout(Duration.ofMillis(500)).build(),
                    HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                return
            } catch (_: java.io.IOException) {
                Thread.sleep(20)
            }
        }
        fail<Unit>("Server did not start")
    }

    private val extraRoutes = """
        router::route("GET", "/users/:id/posts/:post", lambda (request) {
            return response::json(200, json::Value::Object(std::map(request.params, lambda (param) { return (param.name, json::Value::String(param.value)) })))
        }),
        router::route("GET", "/json/build", lambda (request) {
            return response::json(200, json::Value::Array([json::Value::Null, json::Value::Bool(true), json::Value::Number("123"), json::Value::String("hi"), json::Value::Object([]), json::Value::Array([])]))
        }),
        router::route("GET", "/json/invalid", lambda (request) { return response::json(200, json::Value::Number("NaN")) }),
        router::route("POST", "/json/field", lambda (request) {
            return case json::parse(request.body) {
                Result::Ok(value) => case json::get(value, "value") {
                    Option::Some(field) => response::json(200, field),
                    Option::None => response::json(404, json::Value::Null),
                },
                Result::Err(_) => response::text(400, "Invalid JSON"),
            }
        }),
        router::route("GET", "/panic", lambda (request) { std::panic("test failure") }),
        router::route("GET", "/bad-status", lambda (request) { return response::text(999, "bad") }),
        router::route("GET", "/bad-header", lambda (request) { return types::Response(200, [types::Header("X-Test", "bad\r\nInjected: yes")], "bad") }),
        router::route("GET", "/empty", lambda (request) { return response::text(200, "") }),
        router::route("GET", "/no-content", lambda (request) { return response::text(204, "must not be sent") }),
        router::route("GET", "/explicit", lambda (request) { return response::text(200, "GET") }),
        router::route("HEAD", "/explicit", lambda (request) { return types::Response(200, [types::Header("X-Handler", "explicit")], "HEAD") }),
        router::route("GET", "/inspect", lambda (request) {
            let headers = std::map(request.query, lambda (param) { return types::Header("X-" + param.name, param.value) })
            let selected = std::filter(request.headers, lambda (header) { return strings::equals(strings::toLowerCase(header.name), "x-test") })
            return types::Response(200, headers, std::fold(selected, "", lambda (acc, header) { return acc + header.value + ";" }))
        })
    """.trimIndent()
}
