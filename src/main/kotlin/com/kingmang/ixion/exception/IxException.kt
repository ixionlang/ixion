package com.kingmang.ixion.exception

import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.parser.Node
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.text.MessageFormat
import java.util.function.Consumer
import java.util.function.IntFunction
import java.util.stream.Collectors
import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.log10

abstract class IxException protected constructor(
    val code: Int,
    private val templateString: String,
    private val suggestion: String?
) {
    fun send(ixApi: IxApi, file: File, node: Node, vararg varargs: String?) {
        try {
            val pos = node.position!!
            var line = pos.line - 2
            if (line < 0) line = 0
            val limit = 3

            val lines = Files.lines(file.toPath())
            val selection = lines.skip(line.toLong()).limit(limit.toLong()).toList()

            val startLine = line
            val endLine = line + limit
            val padding = ceil(log10((endLine + 1).toDouble())).toInt() + 1
            val result = IntStream.range(0, limit)
                .mapToObj(IntFunction mapToObj@{ i: Int ->
                    if (i < selection.size) {
                        val lineNumber = (i + startLine + 1).toString()
                        val paddedLineNumber: String = leftPad("$lineNumber:", padding)
                        var s: String? = "$BLUE_START$paddedLineNumber$RESET "
                        s += selection[i]
                        if (i + startLine + 1 == pos.line)
                            s += "\n" + RED_START + "^".repeat(pos.col + padding) + RESET
                        return@mapToObj s
                    } else {
                        return@mapToObj (i + startLine + 1).toString() + "|"
                    }
                })
                .collect(Collectors.joining("\n"))

            val buffer = StringBuilder()
            buffer.append(RED_START)
                .append("[")
                .append(javaClass.getSimpleName())
                .append("] in ")
                .append(String.format(file?.getName() + "[%d:%d]\n", pos.line, pos.col))
                .append(RESET)
                .append(MessageFormat.format(templateString, *varargs)).append("\n")
                .append(result).append("\n")
            if (suggestion != null) {
                buffer.append(suggestion)
            }

            if (ixApi.developmentMode) {
                val stackTrace = Thread.currentThread().stackTrace[2]
                val sourceLineNumber = stackTrace.lineNumber
                val sourceLocation = "${stackTrace.className}:${stackTrace.methodName}"
                val logSource = "\nLogged from $sourceLocation@$sourceLineNumber\n"
                buffer.append(logSource)
            }

            ixApi.errorData!!.add(Data(code, buffer.toString(), line, pos.col))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    @JvmRecord
    data class Data(val code: Int, val message: String?, val line: Int, val col: Int)

    class CompilerError(errorMessage: String?, val errorData: MutableList<Data?>?) : Exception(errorMessage)
    companion object {
        private const val RED_START = "\u001B[31m"
        private const val BLUE_START = "\u001B[34m"
        private const val RESET = "\u001B[0m"

        @Throws(CompilerError::class)
        fun killIfErrors(ixApi: IxApi, message: String?) {
            if (!ixApi.errorData!!.isEmpty()) {
                ixApi.errorData.forEach(Consumer { e: Data? -> println(e!!.message) })
                throw CompilerError(message, ixApi.errorData)
            }
        }

        // Вспомогательный метод для выравнивания строки слева
        private fun leftPad(str: String, size: Int): String {
            if (str.length >= size) {
                return str
            }
            return " ".repeat(size - str.length) + str
        }
    }
}