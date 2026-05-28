package com.kingmang.ixion

import com.kingmang.ixion.api.Debugger.debug
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxApi.Companion.exit
import com.kingmang.ixion.api.IxionConstant
import com.kingmang.ixion.exception.IxException.CompilerError
import com.kingmang.ixion.runtime.IxionExitException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.system.exitProcess

class Ixion {
    private var entry: String? = null
    private var helpRequested = false
    private var compileOnly = false
    private var optimize = false
    private var target = CompilationTarget.JVM_BYTECODE

    private fun parseArguments(args: Array<String>) {
        for (arg in args) {
            when (arg) {
                "-h", "--help" -> helpRequested = true
                "--java" -> target = CompilationTarget.JAVA_SOURCE
                "--compile-only" -> compileOnly = true
                "--optimize" -> optimize = true
                else -> if (entry == null && !arg.startsWith("-")) {
                    entry = arg
                }
            }
        }
    }

    private fun printHelp() {
        println("Usage: ixion [OPTIONS] <entry-file>")
        println("Compile and run an ixion program.\n")
        println("Options:")
        println("  -h, --help        Display this help message")
        println("  --java            Generate Java source code instead of bytecode")
        println("  --compile-only    Only compile, do not run\n")
        println("  --optimize        Enable optimizer passes (constant folding)\n")
    }

    @Throws(IOException::class, InterruptedException::class)
    fun executeBytecode(className: String?) {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val inheritedClasspath = System.getProperty("java.class.path")
        val fullClasspath = IxionConstant.OUT_DIR + File.pathSeparator + inheritedClasspath

        val processBuilder = ProcessBuilder(
            javaBin,
            "-cp",
            fullClasspath,
            className
        )
        processBuilder.inheritIO()
        val process = processBuilder.start()

        val status = process.waitFor()
        if (status != 0) {
            System.err.println("Process finished with exit code $status")
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun compileJavaToBytecode(projectRoot: String, basePath: String?) {
        val javaFile = Path.of(projectRoot, IxionConstant.OUT_DIR, "$basePath.java").toString()
        val classpath = IxionConstant.OUT_DIR + File.pathSeparator + "target/classes"

        val command: MutableList<String?> = ArrayList()
        command.add("javac")
        command.add("-d")
        command.add(IxionConstant.OUT_DIR)
        command.add("-cp")
        command.add(classpath)
        command.add(javaFile)

        val processBuilder = ProcessBuilder(command)
        processBuilder.inheritIO()
        val process = processBuilder.start()

        val status = process.waitFor()
        if (status != 0) {
            throw IOException("Java compilation failed with exit code $status")
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun executeJava(className: String?) {
        val processBuilder = ProcessBuilder(
            "java",
            "-cp",
            IxionConstant.OUT_DIR + File.pathSeparator + "target/classes",
            className
        )
        processBuilder.inheritIO()
        val process = processBuilder.start()

        val status = process.waitFor()
        if (status != 0) System.err.println("Process finished with exit code $status")
    }

    @Throws(IOException::class, InterruptedException::class)
    fun compileAndRunJava(projectRoot: String, basePath: String?, className: String?) {
        debug("Compiling Java source code...")
        compileJavaToBytecode(projectRoot, basePath)

        if (!compileOnly) {
            debug("Running Java program...")
            executeJava(className)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun compileAllJavaFiles(directory: String) {
        val dir = File(directory)
        val javaFiles = dir.listFiles { _: File?, name: String? -> name!!.endsWith(".java") }

        if (javaFiles != null) {
            val command: MutableList<String?> = ArrayList()
            command.add("javac")
            command.add("-d")
            command.add(IxionConstant.OUT_DIR)
            command.add("-cp")
            command.add("${IxionConstant.OUT_DIR}${File.pathSeparator}target/classes")

            for (javaFile in javaFiles) {
                command.add(javaFile.absolutePath)
            }

            val processBuilder = ProcessBuilder(command)
            processBuilder.inheritIO()
            val process = processBuilder.start()

            val status = process.waitFor()
            if (status != 0) {
                throw IOException("Java compilation failed with exit code $status")
            }
        }
    }

    fun run() {
        if (helpRequested) {
            printHelp()
            return
        }

        if (entry == null) {
            printHelp()
            exit("Error: Entry file is required", 1)
        }

        val api = IxApi()
        val pwd = System.getProperty("user.dir")
        val moduleLocation = Path.of(pwd).toString()

        try {
            val stdDir = Path.of(moduleLocation, "std").toString()
            compileAllJavaFiles(stdDir)

            var classPath: String?
            var basePath: String?

            if (target == CompilationTarget.JAVA_SOURCE) {
                classPath = api.compileToJava(moduleLocation, entry, optimize)
                basePath = classPath.replace(".", "/")

                if (!compileOnly) {
                    compileAndRunJava(moduleLocation, basePath, classPath)
                } else {
                    debug(
                        "Java source generated: ${Path.of(moduleLocation, IxionConstant.OUT_DIR, "$basePath.java")}"
                    )
                }
            } else {
                classPath = api.compile(moduleLocation, entry, optimize)

                if (!compileOnly) {
                    executeBytecode(classPath)
                }
            }
        } catch (e: FileNotFoundException) {
            exit("File not found: ${e.message}", 2)
        } catch (e: CompilerError) {
            exit(e.message, 1)
        } catch (e: IOException) {
            exit(e, 3)
        } catch (e: InterruptedException) {
            exit(e, 3)
        }
    }

    enum class CompilationTarget {
        JVM_BYTECODE,
        JAVA_SOURCE
    }

    // for unit tests:
    @Throws(IOException::class, InterruptedException::class)
    fun getCompiledProgramOutput(entryFileName: String?): String {
        val output = StringBuilder()

        if (entryFileName.isNullOrEmpty()) {
            return "Error: Entry file name is required"
        }

        val api = IxApi()
        val moduleLocation = System.getProperty("user.dir")

        try {
            val classPath = api.compile(moduleLocation, entryFileName, optimize)
            output.append(executeBytecodeAndGetOutput(classPath))
        } catch (e: Exception) {
            output.append("Error: ").append(e.message)
        }

        return output.toString()
    }

    @Throws(Exception::class)
    private fun executeBytecodeAndGetOutput(className: String?): String? {
//        var classpath = new URL[]{new File(IxionConstant.OUT_DIR + File.pathSeparator + "target/classes").toURI().toURL()};
        val classpath: Array<URL?> = arrayOf(File(IxionConstant.OUT_DIR).toURI().toURL())
        URLClassLoader(classpath).use { loader ->
            var output: String? = ""
            val capture = CombinedOutputCapture()
            try {
                loader
                    .loadClass(className)
                    .getMethod("main", Array<String>::class.java)
                    .invoke(null, arrayOfNulls<String>(0) as Any)
            } catch (e: Exception) {
                System.err.println("Error:")
                e.printStackTrace(System.err)
            } finally {
                output = capture.output
                capture.close()
            }
            return output
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                val cli = Ixion()
                cli.parseArguments(args)
                cli.run()
            } catch (exit: IxionExitException) {
                System.err.println(exit.message)
                exitProcess(exit.code)
            }
        }
    }
}
