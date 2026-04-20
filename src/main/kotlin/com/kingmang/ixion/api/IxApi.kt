package com.kingmang.ixion.api

import com.kingmang.ixion.api.Debugger.debug
import com.kingmang.ixion.ast.ExportStatement
import com.kingmang.ixion.ast.UseStatement
import com.kingmang.ixion.codegen.BytecodeGenerator
import com.kingmang.ixion.codegen.JavaCodegenVisitor
import com.kingmang.ixion.semantic.SemanticVisitor
import com.kingmang.ixion.exception.IxException
import com.kingmang.ixion.exception.IxException.CompilerError
import com.kingmang.ixion.exception.ModuleNotFoundException
import com.kingmang.ixion.modules.Modules
import com.kingmang.ixion.runtime.DefType
import com.kingmang.ixion.runtime.IxionExitException
import com.kingmang.ixion.typechecker.TypeCheckVisitor
import org.apache.commons.io.FilenameUtils
import org.javatuples.Pair
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.*


@JvmRecord
data class IxApi(
    @JvmField val errorData: MutableList<IxException.Data?>?,
    val compilationSet: MutableMap<String?, IxFile>?,
    @JvmField val developmentMode: Boolean
) {
    /**
     * Default constructor for IxApi
     */
    constructor() : this(ArrayList<IxException.Data?>(), HashMap(), true)

    /**
     * Compiles Ixion code to JVM bytecode
     * @param projectRoot The root directory of the project
     * @param filename The filename to compile
     * @return The full name of the generated class
     * @throws FileNotFoundException If the file is not found
     * @throws IxException.CompilerError If compilation errors occur
     */
    @Throws(FileNotFoundException::class, CompilerError::class)
    fun compile(projectRoot: String, filename: String?): String {
        val relativePath = FilenameUtils.getPath(filename)
        val name = FilenameUtils.getName(filename)

        val entry = parse(projectRoot, relativePath, name)
        IxException.killIfErrors(this, "Correct parser errors before continuing.")

        for (filePath in compilationSet!!.keys) {
            val source: IxFile = compilationSet[filePath]!!
            val semanticVisitor = SemanticVisitor(this, source.rootContext, source)
            source.acceptVisitor(semanticVisitor)

            for (stmt in source.statements) {
                if (stmt is ExportStatement) {
                    val stmt = stmt.stmt;
                    if (stmt is PublicAccess) {
                        val identifier = stmt.identifier()
                        val type = source.rootContext.getVariable(identifier)
                        if (type != null) {
                            source.exports[identifier] = type
                        }
                    }
                }
            }

            IxException.killIfErrors(this, "Correct syntax errors before type checking can continue.")
        }

        for (filePath in compilationSet.keys) {
            val source: IxFile = compilationSet[filePath]!!
            for (s in source.imports.keys) {
                val sourceFile = source.imports[s]
                val exportedMembers = sourceFile!!.exports
                for (exportedName in exportedMembers.keys) {
                    val exportedType = exportedMembers[exportedName]
                    val qualifiedName = sourceFile.name + "::" + exportedName
                    if (exportedType is DefType) {
                        exportedType.external = sourceFile
                    }
                    source.rootContext.addVariable(qualifiedName, exportedType)
                }
            }
        }

        for (filePath in compilationSet.keys) {
            val source: IxFile = compilationSet[filePath]!!
            val typeCheckVisitor = TypeCheckVisitor(this, source.rootContext, source)
            source.acceptVisitor(typeCheckVisitor)

            IxException.killIfErrors(this, "Correct type errors before compilation can continue.")
        }
        output(compilationSet)

        val base = entry.fullRelativePath

        return base.replace("/", ".")
    }

    /**
     * Outputs compiled bytecode to class files
     * @param compilationSet The set of files to output
     * @throws IxException.CompilerError If output errors occur
     */

    @Throws(CompilerError::class)
    fun output(compilationSet: MutableMap<String?, out IxFile>) {
        val bytecodeGenerator = BytecodeGenerator()
        for (key in compilationSet.keys) {
            val source: IxFile = compilationSet[key]!!
            val allByteUnits = bytecodeGenerator.generate(this, source)
            IxException.killIfErrors(this, "Correct build errors before compilation can complete.")

            val byteUnit = allByteUnits.first.toByteArray()
            val base = FilenameUtils.removeExtension(source.fullRelativePath)
            var fileName = Path.of(source.projectRoot, IxionConstant.OUT_DIR, "$base.class").toString()
            var tmp = File(fileName)
            tmp.getParentFile().mkdirs()
            var output: OutputStream?
            try {
                output = FileOutputStream(fileName)
                output.write(byteUnit)
                output.close()
            } catch (_: IOException) {
                exit("The above call to mkdirs() should have worked.", 9)
            }

            for (p in allByteUnits.second.entries) {
                val type = p.key
                val writer = p.value

                val innerName = source.fullRelativePath + "$" + type.name

                fileName = Path.of(source.projectRoot, IxionConstant.OUT_DIR, "$innerName.class").toString()
                tmp = File(fileName)
                tmp.getParentFile().mkdirs()
                try {
                    output = FileOutputStream(fileName)
                    output.write(writer.toByteArray())
                    output.close()
                } catch (_: IOException) {
                    exit("The above call to mkdirs() should have worked.", 9)
                }
            }
        }
    }



    /**
     * Compiles Ixion code to Java source code
     * @param projectRoot The root directory of the project
     * @param filename The filename to compile
     * @return The full name of the generated Java class
     * @throws FileNotFoundException If the file is not found
     * @throws IxException.CompilerError If compilation errors occur
     */
    @Throws(FileNotFoundException::class, CompilerError::class)
    fun compileToJava(projectRoot: String, filename: String?): String {
        val relativePath = FilenameUtils.getPath(filename)
        val name = FilenameUtils.getName(filename)

        val entry = parse(projectRoot, relativePath, name)
        IxException.killIfErrors(this, "Correct parser errors before continuing.")

        for (filePath in compilationSet!!.keys) {
            val source: IxFile = compilationSet[filePath]!!
            val semanticVisitor = SemanticVisitor(this, source.rootContext, source)
            source.acceptVisitor(semanticVisitor)

            for (stmt in source.statements) {
                if (stmt is ExportStatement) {
                    val stmt = stmt.stmt;
                    if (stmt is PublicAccess) {
                        val identifier = stmt.identifier()
                        val type = source.rootContext.getVariable(identifier)
                        if (type != null) {
                            source.exports[identifier] = type
                        }
                    }
                }
            }
            IxException.killIfErrors(this, "Correct syntax errors before type checking can continue.")
        }

        for (filePath in compilationSet.keys) {
            val source: IxFile = compilationSet[filePath]!!
            for (s in source.imports.keys) {
                val sourceFile = source.imports[s]
                val exportedMembers = sourceFile!!.exports
                for (exportedName in exportedMembers.keys) {
                    val exportedType = exportedMembers[exportedName]
                    val qualifiedName = sourceFile.name + "::" + exportedName
                    if (exportedType is DefType) {
                        exportedType.external = sourceFile
                    }
                    source.rootContext.addVariable(qualifiedName, exportedType)
                }
            }
        }

        for (filePath in compilationSet.keys) {
            val source: IxFile = compilationSet[filePath]!!
            val typeCheckVisitor = TypeCheckVisitor(this, source.rootContext, source)
            source.acceptVisitor(typeCheckVisitor)
            IxException.killIfErrors(this, "Correct type errors before compilation can continue.")
        }

        outputJava(compilationSet)

        val base = entry.fullRelativePath
        return base.replace("/", ".")
    }

    /**
     * Outputs compiled code to Java source files
     * @param compilationSet The set of files to output
     * @throws IxException.CompilerError If output errors occur
     */
    @Throws(CompilerError::class)
    fun outputJava(compilationSet: MutableMap<String?, out IxFile>) {
        for (key in compilationSet.keys) {
            val source: IxFile = compilationSet[key]!!

            val javaGenerator = JavaCodegenVisitor(this, source)
            source.acceptVisitor(javaGenerator)

            val javaCode = javaGenerator.generatedCode
            val base = FilenameUtils.removeExtension(source.fullRelativePath)
            val packageName = if (base.contains("/"))
                base.substring(0, base.lastIndexOf("/")).replace("/", ".")
            else
                ""

            val fullJavaFile = StringBuilder()

            if (!packageName.isEmpty()) {
                fullJavaFile.append("package ").append(packageName).append(";\n\n")
            }

            fullJavaFile.append("import java.util.*;\n")
            fullJavaFile.append("import java.lang.*;\n\n")

            val className = if (base.contains("/"))
                base.substring(base.lastIndexOf("/") + 1)
            else
                base
            fullJavaFile.append("public class ").append(className).append(" {\n")
            fullJavaFile.append(javaCode)
            fullJavaFile.append("}\n")

            val fileName = Path.of(source.projectRoot, IxionConstant.OUT_DIR, "$base.java").toString()
            val javaFile = File(fileName)
            javaFile.getParentFile().mkdirs()

            try {
                FileWriter(javaFile).use { writer ->
                    writer.write(fullJavaFile.toString())
                }
            } catch (e: IOException) {
                exit("Error writing Java file: " + e.message, 9)
            }

            generateStructJavaFiles(source, javaGenerator.structClasses)
        }
    }

    /**
     * Generates separate Java files for structures
     * @param source The source file containing structures
     * @param structClasses Map of structure names to their generated code
     */
    private fun generateStructJavaFiles(source: IxFile, structClasses: MutableMap<String?, String?>) {
        val basePackage = if (source.fullRelativePath.contains("/"))
            source.fullRelativePath.substring(0, source.fullRelativePath.lastIndexOf("/")).replace("/", ".")
        else
            ""

        for (entry in structClasses.entries) {
            val structName = entry.key
            val structCode = entry.value

            val fullStructFile = StringBuilder()

            if (!basePackage.isEmpty()) {
                fullStructFile.append("package ").append(basePackage).append(";\n\n")
            }

            fullStructFile.append("public class ").append(structName).append(" {\n")
            fullStructFile.append(structCode)
            fullStructFile.append("}\n")

            val fileName = Path.of(
                source.projectRoot, IxionConstant.OUT_DIR,
                basePackage.replace(".", "/"), "$structName.java"
            ).toString()
            val structFile = File(fileName)
            structFile.getParentFile().mkdirs()

            try {
                FileWriter(structFile).use { writer ->
                    writer.write(fullStructFile.toString())
                }
            } catch (e: IOException) {
                exit("Error writing struct Java file: " + e.message, 9)
            }
        }
    }

    /**
     * Universal compilation method with target selection
     * @param projectRoot The root directory of the project
     * @param filename The filename to compile
     * @param target The compilation target (JVM bytecode or Java source)
     * @return The full name of the generated class
     * @throws FileNotFoundException If the file is not found
     * @throws IxException.CompilerError If compilation errors occur
     */
    @Throws(FileNotFoundException::class, CompilerError::class)
    fun compile(projectRoot: String, filename: String?, target: CompilationTarget): String {
        return when (target) {
            CompilationTarget.JVM_BYTECODE -> compile(projectRoot, filename)
            CompilationTarget.JAVA_SOURCE -> compileToJava(projectRoot, filename)
        }
    }

    /**
     * Enum representing compilation targets
     */
    enum class CompilationTarget {
        JVM_BYTECODE,
        JAVA_SOURCE
    }

    /**
     * Parses an Ixion file and its imports
     * @param projectRoot The root directory of the project
     * @param relativePath The relative path of the file
     * @param name The name of the file
     * @return The parsed IxFile object
     * @throws FileNotFoundException If the file is not found
     */
    @Throws(FileNotFoundException::class)
    fun parse(projectRoot: String, relativePath: String, name: String?): IxFile {
        val source = IxFile(projectRoot, relativePath, name)
        debug("Parsing `" + source.file.getName() + "`")

        compilationSet!![FilenameUtils.separatorsToUnix(source.file.path)] = source

        val imports: MutableList<Pair<String?, String?>> = ArrayList<Pair<String?, String?>>()

        for (statement in source.statements) {
            if (statement is UseStatement) {
                val requestedUse = statement.stringLiteral.source
                debug("\tFound module `" + requestedUse + "`")

                val relative = FilenameUtils.getPath(requestedUse)
                val n = FilenameUtils.getName(requestedUse)
                val filePath =
                    Path.of(source.projectRoot, source.relativePath, relative, n + IxionConstant.EXT).toString()
                val normalizedPath = FilenameUtils.separatorsToUnix(Path.of(filePath).normalize().toString())
                if (File(normalizedPath).exists()) {
                    imports.add(Pair.with<String?, String?>(relative, normalizedPath))
                } else if (!Modules.modules.containsKey(n)) {
                    ModuleNotFoundException().send(this, source.file, statement, n)
                }
            }
        }

        for (i in imports) {
            val relative: String = i.getValue0()!!
            val normalizedPath = i.getValue1()
            val n = FilenameUtils.removeExtension(FilenameUtils.getName(normalizedPath))
            if (!compilationSet.containsKey(normalizedPath)) {
                debug("triggered parse, no key exists `" + normalizedPath + "`")
                val next: IxFile?
                try {
                    next = parse(projectRoot, Path.of(source.relativePath, relative).normalize().toString(), n)
                    source.addImport(normalizedPath, next)
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                    exit("Issues with building import tree.", 67)
                }
            } else {
                source.addImport(normalizedPath, compilationSet[normalizedPath])
            }
        }

        return source
    }




    companion object {
        /**
         * Extracts class name from IxFile
         * @param file The IxFile object
         * @return The class name
         */
        @JvmStatic
        fun getClassName(file: IxFile): String {
            var fileName = file.fullRelativePath
            val lastSlash = fileName.lastIndexOf('/')
            if (lastSlash != -1) {
                fileName = fileName.substring(lastSlash + 1)
            }
            val dotIndex = fileName.lastIndexOf('.')
            if (dotIndex != -1) {
                fileName = fileName.substring(0, dotIndex)
            }
            return fileName
        }

        /**
         * Exits the application with an error message and code
         * @param message The error message to display
         * @param code The exit code
         */
        @JvmStatic
        fun exit(message: String?, code: Int): Nothing {
            throw IxionExitException(message, code)
        }

        /**
         * Exits the application with a throwable cause and code
         * @param cause The cause
         * @param code The exit code
         */
        @JvmStatic
        fun exit(cause: Throwable?, code: Int): Nothing {
            throw IxionExitException(cause, code)
        }
    }
}
