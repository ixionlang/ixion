package com.kingmang.ixion.codegen

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.api.Context
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxApi.Companion.getClassName
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.*
import java.util.*
import kotlin.math.max

class JavaCodegenVisitor(private val ixApi: IxApi, private val source: IxFile) : Visitor<Optional<String>> {
    private var currentContext: Context?
    private val output = StringBuilder()
    private var indentLevel = 0
    private val functionStack = Stack<DefType?>()
    val structClasses: MutableMap<String?, String?> = HashMap<String?, String?>()
    private val localMaps: MutableMap<DefType?, MutableMap<String?, Int?>?> =
        HashMap<DefType?, MutableMap<String?, Int?>?>()

    init {
        this.currentContext = source.rootContext
    }

    private fun indent() {
        output.append("    ".repeat(max(0, indentLevel)))
    }

    private fun println(line: String?) {
        indent()
        output.append(line).append("\n")
    }

    private fun print(text: String?) {
        output.append(text)
    }

    val generatedCode: String
        get() = output.toString()

    override fun visit(statement: Statement): Optional<String> {
        return statement.accept(this)
    }

    override fun visitAssignExpr(expression: AssignExpression): Optional<String> {
        if (expression.left is IdentifierExpression) {
            print(expression.left.identifier.source + " = ")
            expression.right.accept(this)
        } else if (expression.left is PropertyAccessExpression) {
            expression.left.accept(this)
            print(" = ")
            expression.right.accept(this)
        }
        return Optional.empty()
    }

    override fun visitBad(expression: BadExpression): Optional<String> {
        print("/* ERROR: Bad expression */")
        return Optional.empty()
    }

    override fun visitBinaryExpr(expression: BinaryExpression): Optional<String> {
        expression.left.accept(this)

        val operator = when (expression.operator.type) {
            TokenType.AND -> " && "
            TokenType.OR -> " || "
            TokenType.EQUAL -> " == "
            TokenType.NOTEQUAL -> " != "
            TokenType.LT -> " < "
            TokenType.GT -> " > "
            TokenType.LE -> " <= "
            TokenType.GE -> " >= "
            TokenType.ADD -> " + "
            TokenType.SUB -> " - "
            TokenType.MUL -> " * "
            TokenType.DIV -> " / "
            TokenType.MOD -> " % "
            TokenType.XOR -> " ^ "
            else -> " " + expression.operator.source + " "
        }

        print(operator)
        expression.right.accept(this)
        return Optional.empty()
    }

    override fun visitCall(expression: CallExpression): Optional<String> {
        if (expression.item is IdentifierExpression) {
            expression.item.realType = currentContext!!.getVariable(expression.item.identifier.source)!!
        }

        when (expression.item.realType) {
            is DefType -> {
                val callType = expression.item.realType as DefType
                if (callType.glue) {
                    val owner = callType.owner!!.replace('/', '.')
                    var name: String = callType.name
                    if (callType.isPrefixed) name = "_$name"

                    print("$owner.$name(")
                } else {
                    val name = "_" + callType.name

                    if (callType.external != null && callType.external != source) {
                        val className = getClassName(callType.external!!)
                        print("$className.$name(")
                    } else {
                        print("$name(")
                    }
                }

                for (i in expression.arguments.indices) {
                    if (i > 0) print(", ")
                    expression.arguments[i].accept(this)
                }
                print(")")
            }

            is StructType -> {
                print("new " + (expression.item.realType as StructType).name + "(")
                for (i in expression.arguments.indices) {
                    if (i > 0) print(", ")
                    expression.arguments[i].accept(this)
                }
                print(")")
            }

            else -> {
                expression.item.accept(this)
                print("(")
                for (i in expression.arguments.indices) {
                    if (i > 0) print(", ")
                    expression.arguments[i].accept(this)
                }
                print(")")
            }
        }

        return Optional.empty()
    }


    override fun visitEmpty(expression: EmptyExpression): Optional<String> {
        return Optional.empty()
    }

    override fun visitEmptyList(expression: EmptyListExpression): Optional<String> {
        print("new java.util.ArrayList<>()")
        return Optional.empty()
    }

    override fun visitGroupingExpr(expression: GroupingExpression): Optional<String> {
        print("(")
        expression.expression.accept(this)
        print(")")
        return Optional.empty()
    }

    override fun visitIdentifierExpr(expression: IdentifierExpression): Optional<String> {
        print(expression.identifier.source)
        return Optional.empty()
    }

    override fun visitIndexAccess(expression: IndexAccessExpression): Optional<String> {
        expression.left.accept(this)
        print(".get(")
        expression.right.accept(this)
        print(")")
        return Optional.empty()
    }

    override fun visitLiteralExpr(expression: LiteralExpression): Optional<String> {
        if (expression.realType is BuiltInType) {
            when (expression.realType) {
                BuiltInType.STRING -> print("\"" + expression.literal.source.replace("\"", "\\\"") + "\"")
                BuiltInType.CHAR -> {
                    val escapedChar: String = getEscapedChar(expression)
                    print("'$escapedChar'")
                }

                else -> print(expression.literal.source)
            }
        } else {
            when (expression.literal.type) {
                TokenType.CHAR -> {
                    val escapedChar: String = getEscapedChar(expression)
                    print("'$escapedChar'")
                }

                TokenType.STRING -> print("\"" + expression.literal.source.replace("\"", "\\\"") + "\"")
                else -> print(expression.literal.source)
            }
        }
        return Optional.empty()
    }

    override fun visitLiteralList(expression: LiteralListExpression): Optional<String> {
        if (expression.entries.isEmpty()) {
            print("new java.util.ArrayList<>()")
        } else {
            val elementType: IxType = expression.entries.first().realType
            val wrapperType = getWrapperTypeName(elementType)

            print("java.util.Arrays.<$wrapperType>asList(")
            for (i in expression.entries.indices) {
                if (i > 0) print(", ")
                expression.entries[i].accept(this)
            }
            print(")")
        }
        return Optional.empty()
    }

    override fun visitModuleAccess(expression: ModuleAccessExpression): Optional<String> {
        expression.foreign.accept(this)
        print(".")
        expression.identifier.accept(this)
        return Optional.empty()
    }

    override fun visitPostfixExpr(expression: PostfixExpression): Optional<String> {
        expression.expression.accept(this)
        print(expression.operator.source)
        return Optional.empty()
    }

    override fun visitPrefix(expression: PrefixExpression): Optional<String> {
        print(expression.operator.source)
        expression.right.accept(this)
        return Optional.empty()
    }

    override fun visitPropertyAccess(expression: PropertyAccessExpression): Optional<String> {
        expression.expression.accept(this)
        for (identifier in expression.identifiers) {
            print("." + identifier.identifier.source)
        }
        return Optional.empty()
    }

    override fun visitLambda(expression: LambdaExpression): Optional<String> {
        return Optional.empty()
    }

    override fun visitEnumAccess(expression: EnumAccessExpression): Optional<String> {
        val enumName = (expression.enumType as IdentifierExpression).identifier.source
        val enumValue = expression.enumValue.identifier.source
        print("$enumName.$enumValue")
        return Optional.empty()
    }

    override fun visitTypeAlias(statement: TypeAliasStatement): Optional<String> {
        return Optional.empty()
    }

    override fun visitBlockStmt(statement: BlockStatement): Optional<String> {
        println("{")
        indentLevel++
        for (stmt in statement.statements) {
            stmt!!.accept(this)
        }
        indentLevel--
        println("}")
        return Optional.empty()
    }

    override fun visitEnum(statement: EnumStatement): Optional<String> {
        indent()
        print("enum " + statement.name.source + " {")
        for (i in statement.values!!.indices) {
            if (i > 0) print(", ")
            print(statement.values[i]!!.source)
        }
        println("}")
        return Optional.empty()
    }

    override fun visitExport(statement: ExportStatement): Optional<String> {
        return statement.stmt.accept(this)
    }

    override fun visitExpressionStmt(statement: ExpressionStatement): Optional<String> {
        indent()
        statement.expression.accept(this)
        println(";")
        return Optional.empty()
    }

    override fun visitFor(statement: ForStatement): Optional<String> {
        indent()

        var elementType: IxType = BuiltInType.ANY
        val realType = statement.expression.realType
        if (realType is ListType) {
            elementType = realType.contentType
        } else if (statement.expression is IdentifierExpression) {
            val varType = currentContext!!.getVariable(statement.expression.identifier.source)
            if (varType is ListType) {
                elementType = varType.contentType
            }
        }

        val javaElementType = getJavaTypeName(elementType)

        if (elementType is BuiltInType && elementType.isNumeric) {
            val wrapperType = getWrapperTypeName(elementType)
            print("for (java.util.Iterator<$wrapperType> iter = ")
            statement.expression.accept(this)
            println(".iterator(); iter.hasNext(); ) {")

            indentLevel++
            indent()
            print(javaElementType + " " + statement.name.source + " = ")
            when (elementType) {
                BuiltInType.INT -> print("iter.next().intValue()")
                BuiltInType.FLOAT -> print("iter.next().floatValue()")
                BuiltInType.DOUBLE -> print("iter.next().doubleValue()")
                BuiltInType.BOOLEAN -> print("iter.next().booleanValue()")
                else -> print("iter.next()")
            }
            println(";")
        } else {
            print("for (" + javaElementType + " " + statement.name.source + " : ")
            statement.expression.accept(this)
            println(") {")
            indentLevel++
        }

        currentContext = statement.block.context
        statement.block.accept(this)
        indentLevel--
        println("}")
        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    override fun visitFunctionStmt(statement: DefStatement): Optional<String> {
        val funcType = currentContext!!.getVariableTyped<DefType>(statement.name.source)
        functionStack.push(funcType)
        localMaps[funcType] = HashMap<String?, Int?>()

        indent()
        print("public static ")

        if (funcType!!.name == "main") {
            print("void main(String[] args)")
        } else {
            val returnType = getJavaTypeName(funcType.returnType)
            val functionName = if (funcType.glue) funcType.name else "_" + funcType.name

            print("$returnType $functionName(")

            for (i in funcType.parameters.indices) {
                val param: kotlin.Pair<String, IxType> = funcType.parameters[i]
                if (i > 0) print(", ")
                val paramType = getJavaTypeName(param.second)
                print(paramType + " " + param.first)
            }
            print(")")
        }
        println(" {")

        indentLevel++
        currentContext = statement.body!!.context
        statement.body.accept(this)

        if ((funcType.name != "main") && !hasReturnStatement(statement.body) && (funcType.returnType != BuiltInType.VOID)) {
            indent()
            val defaultValue = getDefaultValue(funcType.returnType)
            println("return $defaultValue;")
        }

        indentLevel--
        println("}")

        functionStack.pop()
        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    private fun hasReturnStatement(body: BlockStatement): Boolean {
        for (stmt in body.statements) {
            if (stmt is ReturnStatement) {
                return true
            } else if (stmt is BlockStatement) {
                if (hasReturnStatement(stmt)) return true
            } else if (stmt is IfStatement) {
                val trueBranch = hasReturnStatement(stmt.trueBlock)
                val falseBranch = hasReturnStatementInStatement(stmt.falseStatement)
                if (trueBranch && falseBranch) return true
            }
        }
        return false
    }

    private fun hasReturnStatementInStatement(stmt: Statement?): Boolean {
        if (stmt == null) return false
        if (stmt is ReturnStatement) return true
        if (stmt is BlockStatement) return hasReturnStatement(stmt)
        if (stmt is IfStatement) {
            val trueBranch = hasReturnStatement(stmt.trueBlock)
            val falseBranch = hasReturnStatementInStatement(stmt.falseStatement)
            return trueBranch && falseBranch
        }
        return false
    }

    private fun getWrapperTypeName(type: IxType): String? {
        return when (type) {
            is BuiltInType -> {
                when (type) {
                    BuiltInType.CHAR -> "Character"
                    BuiltInType.INT -> "Integer"
                    BuiltInType.FLOAT -> "Float"
                    BuiltInType.DOUBLE -> "Double"
                    BuiltInType.BOOLEAN -> "Boolean"
                    BuiltInType.STRING -> "String"
                    BuiltInType.VOID -> "Void"
                    BuiltInType.ANY -> "Object"
                }
            }

            is ListType -> {
                val elementType = getWrapperTypeName(type.contentType)
                "java.util.List<$elementType>"
            }

            else -> getJavaTypeName(type)
        }
    }

    override fun visitIf(statement: IfStatement): Optional<String> {
        indent()
        print("if (")
        statement.condition.accept(this)
        println(") {")

        indentLevel++
        currentContext = statement.trueBlock.context
        statement.trueBlock.accept(this)
        indentLevel--

        if (statement.falseStatement != null) {
            println("} else {")
            indentLevel++
            statement.falseStatement.accept(this)
            indentLevel--
        }
        println("}")

        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    override fun visitUse(statement: UseStatement): Optional<String> {
        return Optional.empty()
    }

    override fun visitMatch(statement: CaseStatement): Optional<String> {
        for (entry in statement.cases.entries) {
            val typeStmt: TypeStatement = entry.key
            val pair: kotlin.Pair<String, BlockStatement> = entry.value
            val scopedName = pair.first
            val block = pair.second

            indent()
            print("if (")
            statement.expression.accept(this)
            print(" instanceof ")

            val actualType = statement.types[typeStmt]
            var javaTypeName = getJavaTypeName(actualType)

            val tempVarName = "temp_$scopedName"
            if (actualType is BuiltInType) {
                when (actualType) {
                    BuiltInType.INT -> javaTypeName = "Integer"
                    BuiltInType.FLOAT -> javaTypeName = "Float"
                    BuiltInType.DOUBLE -> javaTypeName = "Double"
                    BuiltInType.BOOLEAN -> javaTypeName = "Boolean"
                    else -> {}
                }
            }

            print("$javaTypeName $tempVarName")
            println(") {")

            indentLevel++

            if (actualType is BuiltInType && actualType != BuiltInType.STRING) {
                indent()
                print(getJavaTypeName(actualType) + " " + scopedName + " = ")
                print(tempVarName)

                when (actualType) {
                    BuiltInType.INT -> print(".intValue()")
                    BuiltInType.FLOAT -> print(".floatValue()")
                    BuiltInType.DOUBLE -> print(".doubleValue()")
                    BuiltInType.BOOLEAN -> print(".booleanValue()")
                    else -> {}
                }
                println(";")
            } else {
                indent()
                print(getJavaTypeName(actualType) + " " + scopedName + " = ")
                print("($javaTypeName) $tempVarName")
                println(";")
            }

            currentContext = block.context
            block.accept(this)
            indentLevel--

            println("}")
        }

        return Optional.empty()
    }

    override fun visitParameterStmt(statement: ParameterStatement): Optional<String> {
        return Optional.empty()
    }

    override fun visitReturnStmt(statement: ReturnStatement): Optional<String> {
        indent()
        print("return")
        if (statement.expression !is EmptyExpression) {
            print(" ")
            statement.expression!!.accept(this)
        }
        println(";")
        return Optional.empty()
    }

    override fun visitStruct(statement: StructStatement): Optional<String> {
        val structType = currentContext!!.getVariableTyped<StructType>(statement.name.source)

        println("public static class " + structType!!.name + " {")

        indentLevel++
        for (pair in structType.parameters) {
            val fieldName = pair.first
            val fieldType = pair.second
            val javaType = getJavaTypeName(fieldType)
            println("public $javaType $fieldName;")
        }

        println("")
        print("public " + structType.name + "(")

        for (i in structType.parameters.indices) {
            val param: Pair<String?, IxType?> = structType.parameters[i]
            if (i > 0) print(", ")
            val javaType = getJavaTypeName(param.second)
            print(javaType + " " + param.first)
        }
        println(") {")

        indentLevel++
        for (param in structType.parameters) {
            println("this." + param.first + " = " + param.first + ";")
        }
        indentLevel--
        println("}")

        println("")
        println("@Override")
        println("public String toString() {")
        indentLevel++
        print("return \"" + structType.name + "{\" + ")
        for (i in structType.parameters.indices) {
            val param: kotlin.Pair<String, IxType?> = structType.parameters[i]
            val fieldName = param.first
            if (i > 0) print(" + \", \" + ")
            print("\"$fieldName=\" + $fieldName")
        }
        println(" + \"}\";")
        indentLevel--
        println("}")
        indentLevel--
        println("}")

        return Optional.empty()
    }

    override fun visitTypeAlias(statement: TypeStatement): Optional<String> {
        return Optional.empty()
    }

    override fun visitUnionType(statement: UnionTypeStatement): Optional<String> {
        return Optional.empty()
    }

    override fun visitVariable(statement: VariableStatement): Optional<String> {
        indent()
        val type = currentContext!!.getVariable(statement.identifier())
        val javaType = getJavaTypeName(type)
        print(javaType + " " + statement.identifier() + " = ")
        statement.expression.accept(this)
        println(";")
        return Optional.empty()
    }

    override fun visitWhile(statement: WhileStatement): Optional<String> {
        indent()
        print("while (")
        statement.condition.accept(this)
        println(") {")

        indentLevel++
        currentContext = statement.block.context
        statement.block.accept(this)
        indentLevel--

        println("}")
        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    private fun getJavaTypeName(type: IxType?): String? {
        return when (type) {
            null -> "Object"

            is BuiltInType -> {
                when (type) {
                    BuiltInType.CHAR -> "char"
                    BuiltInType.INT -> "int"
                    BuiltInType.FLOAT -> "float"
                    BuiltInType.DOUBLE -> "double"
                    BuiltInType.BOOLEAN -> "boolean"
                    BuiltInType.STRING -> "String"
                    BuiltInType.VOID -> "void"
                    BuiltInType.ANY -> "Object"
                }
            }

            is ListType -> {
                val elementType = getWrapperTypeName(type.contentType)
                "java.util.List<$elementType>"
            }

            is UnionType -> "Object"
            is StructType -> type.name
            else -> type.name
        }
    }

    private fun getDefaultValue(type: IxType?): String {
        return when (type) {
            is BuiltInType -> {
                when (type) {
                    BuiltInType.CHAR -> "\\u0000"
                    BuiltInType.INT -> "0"
                    BuiltInType.FLOAT -> "0.0f"
                    BuiltInType.DOUBLE -> "0.0"
                    BuiltInType.BOOLEAN -> "false"
                    BuiltInType.STRING, BuiltInType.ANY -> "null"
                    BuiltInType.VOID -> ""
                }
            }

            is ListType -> "null"
            is UnionType -> "null"
            is StructType -> "null"
            else -> "null"
        }
    }

    companion object {
        private fun getEscapedChar(expr: LiteralExpression): String {
            return when (val charValue: Char = expr.literal.source.first()) {
                '\n' -> "\\n"
                '\t' -> "\\t"
                '\r' -> "\\r"
                '\b' -> "\\b"
                '\u000c' -> "\\f"
                '\'' -> "\\'"
                '\\' -> "\\\\"
                '\u0000' -> "\\0"
                else -> java.lang.String.valueOf(charValue)
            }
        }
    }
}