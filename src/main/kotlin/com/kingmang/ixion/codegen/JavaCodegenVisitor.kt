package com.kingmang.ixion.codegen

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.api.Context
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxApi.Companion.getClassName
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.*
import com.kingmang.ixion.typechecker.Coercions
import com.kingmang.ixion.typechecker.TypeTags
import com.kingmang.ixion.typechecker.TagExpression
import com.kingmang.ixion.api.IxionConstant.Mutability
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

    private var dataCounter = 0
    private fun sourceName(name: String): String = "ix$" + name
    private var caseDepth = 0
    private val boxedFunctions = hashSetOf<DefType>()
    private val renamed = hashMapOf<String, String>()
    private fun quoted(value: String): String = buildString {
        append('"')
        for (character in value) append(
            when (character) {
                '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
                '\b' -> "\\b"; '\u000c' -> "\\f"
                else -> if (character.code < 32) "\\" + character.code.toString(8)
                    .padStart(3, '0') else character.toString()
            }
        )
        append('"')
    }

    private fun syntacticExit(statement: Statement?): Boolean = when (statement) {
        is ReturnStatement -> true
        is BlockStatement -> statement.statements.any(::syntacticExit)
        is IfStatement -> syntacticExit(statement.trueBlock) && statement.falseStatement != null && syntacticExit(
            statement.falseStatement
        )

        else -> false
    }

    private fun closeNeverPath(body: BlockStatement) {
        if (com.kingmang.ixion.typechecker.ControlFlow.exits(body) && !syntacticExit(body))
            println("throw new IllegalStateException(\"Unreachable checked control flow\");")
    }

    private fun boxed(type: IxType): String = when (type) {
        BuiltInType.INT -> "Integer"; BuiltInType.FLOAT -> "Float"; BuiltInType.DOUBLE -> "Double"
        BuiltInType.BOOLEAN -> "Boolean"; BuiltInType.CHAR -> "Character"
        BuiltInType.VOID, UnitType -> "com.kingmang.ixion.runtime.UnitValue"
        NeverType -> "Object"
        else -> getJavaTypeName(type) ?: "Object"
    }

    private fun objectCast(type: IxType, value: String): String {
        val base = "((" + boxed(type) + ")(" + value + "))"
        return base + when (type) {
            BuiltInType.INT -> ".intValue()"; BuiltInType.FLOAT -> ".floatValue()"; BuiltInType.DOUBLE -> ".doubleValue()"
            BuiltInType.BOOLEAN -> ".booleanValue()"; BuiltInType.CHAR -> ".charValue()"; else -> ""
        }
    }

    private fun render(expression: Expression): String {
        val start = output.length
        expression.accept(this)
        val value = output.substring(start)
        output.setLength(start)
        return value
    }

    private fun tag(tag: TagExpression): String = when (tag) {
        is TagExpression.Text -> quoted(tag.value)
        is TagExpression.Parameter -> "__ixTypes[" + functionStack.peek()!!.generics.indexOf(tag.name)
            .also { require(it >= 0) } + "]"

        is TagExpression.Join -> tag.parts.joinToString(" + ", "(", ")") { tag(it) }
        is TagExpression.Union -> tag.alternatives.joinToString(
            ", ",
            "com.kingmang.ixion.runtime.RuntimeTypeTags.union(new String[]{",
            "})"
        ) { tag(it) }
    }

    private fun tags(types: List<IxType>): String =
        types.joinToString(", ", "new String[]{", "}") { tag(TypeTags.expression(it)) }

    private fun emitAdt(type: AdtType, variant: AdtVariant, arguments: List<Expression>) {
        print("new com.kingmang.ixion.runtime.AdtValue(" + tag(TypeTags.expression(type)) + ", " + quoted(variant.name) + ", new Object[]{")
        val fields = type.fields(variant)
        for (index in arguments.indices) {
            if (index > 0) print(", ")
            emitExpected(arguments[index], fields[index].second)
        }
        print("})")
    }

    private fun emitExpected(expression: Expression, expected: IxType) {
        val actual = expression.realType
        if (actual === NeverType) {
            print(objectCast(expected, render(expression)))
            return
        }
        if (expected is UnionType && actual is UnionType) {
            val mapping = Coercions.unionMapping(expected, actual)
            if (mapping.isNotEmpty()) {
                print("com.kingmang.ixion.runtime.UnionCoercions.convert(")
                expression.accept(this)
                print(", \"$mapping\")")
                return
            }
        }
        val target =
            if (expected is UnionType && actual !is UnionType) Coercions.unionTarget(expected, actual) else expected
        if (target is BuiltInType && actual is BuiltInType && target != actual && target.isNumeric && actual.isNumeric) {
            print("((" + getJavaTypeName(target) + ") (")
            expression.accept(this)
            print("))")
        } else expression.accept(this)
    }

    override fun visitUnit(expression: UnitExpression): Optional<String> {
        print("com.kingmang.ixion.runtime.UnitValue.INSTANCE")
        return Optional.empty()
    }

    override fun visitTuple(expression: TupleExpression): Optional<String> {
        print("new com.kingmang.ixion.runtime.TupleValue(" + tag(TypeTags.expression(expression.realType)) + ", new Object[]{")
        for ((index, value) in expression.elements.withIndex()) {
            if (index > 0) print(", "); value.accept(this)
        }
        print("})")
        return Optional.empty()
    }

    override fun visitTypeApplication(expression: TypeApplicationExpression): Optional<String> {
        val type = expression.realType as AdtType
        emitAdt(
            type,
            type.declaration.variants.first { it.name == expression.name.source.substringAfterLast("::") },
            emptyList()
        )
        return Optional.empty()
    }

    override fun visitUpdate(expression: UpdateExpression): Optional<String> {
        val type = expression.realType as StructType
        val raw = if (type is AppliedStructType) type.base else type
        val id = dataCounter++
        print("((java.util.function.Supplier<" + getJavaTypeName(raw) + ">) () -> {\n")
        indentLevel++
        indent(); print("final " + getJavaTypeName(raw) + " ixBase$id = "); expression.base.accept(this); print(";\n")
        val updates = hashMapOf<String, String>()
        for ((index, field) in expression.fields.withIndex()) {
            val expected = type.parameters.first { it.first == field.first.source }.second
            val name = "ixUpdate${id}_$index"
            indent(); print("final " + getJavaTypeName(expected) + " $name = "); emitExpected(
                field.second,
                expected
            ); print(";\n")
            updates[field.first.source] = name
        }
        indent(); print("return new " + getJavaTypeName(raw) + "(")
        print(raw.parameters.joinToString(", ") {
            updates[it.first] ?: objectCast(
                it.second,
                "ixBase$id.ixField(${raw.parameters.indexOf(it)})"
            )
        })
        if (raw.hasGenerics()) {
            if (raw.parameters.isNotEmpty()) print(", "); print(tag(TypeTags.expression(type)))
        }
        print(");\n")
        indentLevel--; indent(); print("}).get()")
        return Optional.empty()
    }

    override fun visitBlockExpression(expression: BlockExpression): Optional<String> {
        expression.block.accept(this)
        if (expression.realType !== NeverType) {
            indent(); print("return ")
            if (expression.result == null) visitUnit(UnitExpression(expression.position!!)) else emitExpected(
                expression.result,
                expression.realType
            )
            print(";\n")
        }
        return Optional.empty()
    }

    override fun visitCaseExpression(expression: CaseExpression): Optional<String> {
        val id = dataCounter++
        val previous = currentContext
        val previousRenamed = HashMap(renamed)
        print("((java.util.function.Supplier<" + boxed(expression.realType) + ">) () -> {\n")
        indentLevel++
        indent(); print("final Object ixSubject$id = "); expression.subject.accept(this); print(";\n")
        caseDepth++
        for (branch in expression.branches) {
            val plan = branch.plan!!
            indent(); print(
                "if (com.kingmang.ixion.runtime.MatchRuntime.matches(ixSubject$id, " + quoted(plan.encoded) + ", " + tags(
                    plan.tags
                ) + ")) {\n"
            )
            indentLevel++
            currentContext = branch.context
            for ((index, binding) in plan.bindings.withIndex()) {
                val name = "ixBinding${dataCounter++}_$index"
                renamed[binding.name] = name
                println(
                    "final " + getJavaTypeName(binding.type) + " $name = " + objectCast(
                        binding.type,
                        "com.kingmang.ixion.runtime.MatchRuntime.extract(ixSubject$id, " + quoted(binding.path) + ")"
                    ) + ";"
                )
            }
            if (branch.guard != null) {
                indent(); print("if ("); branch.guard.accept(this); print(") {\n"); indentLevel++
            }
            branch.body.block.accept(this)
            if (branch.body.realType !== NeverType) {
                indent(); print("return ")
                if (branch.body.result == null) visitUnit(UnitExpression(expression.position!!)) else emitExpected(
                    branch.body.result,
                    expression.realType
                )
                print(";\n")
            }
            if (branch.guard != null) {
                indentLevel--; println("}")
            }
            indentLevel--; println("}")
            renamed.clear(); renamed.putAll(previousRenamed)
        }
        println("throw new IllegalStateException(\"Unreachable: checked case has no matching branch\");")
        caseDepth--
        indentLevel--; indent(); print("}).get()")
        currentContext = previous
        return Optional.empty()
    }

    override fun visit(statement: Statement): Optional<String> {
        return statement.accept(this)
    }

    override fun visitAssignExpr(expression: AssignExpression): Optional<String> {
        if (expression.left is IdentifierExpression) {
            expression.left.accept(this)
            print(" = ")
            emitExpected(expression.right, expression.left.realType)
        } else if (expression.left is PropertyAccessExpression) {
            expression.left.accept(this)
            print(" = ")
            emitExpected(expression.right, expression.left.realType)
        }
        return Optional.empty()
    }

    override fun visitBad(expression: BadExpression): Optional<String> {
        print("/* ERROR: Bad expression */")
        return Optional.empty()
    }

    /** Emits a parenthesized binary expression so Java precedence preserves the checked AST. */
    override fun visitBinaryExpr(expression: BinaryExpression): Optional<String> {
        print("(")
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
        print(")")
        return Optional.empty()
    }

    override fun visitCall(expression: CallExpression): Optional<String> {
        val callee = expression.item.realType
        if (callee is ConstructorType) {
            emitAdt(expression.realType as AdtType, callee.variant, expression.arguments)
            return Optional.empty()
        }
        val declaredResult = when (callee) {
            is DefType -> callee.returnType; is LambdaType -> callee.returnType; else -> null
        }
        val unit = declaredResult === BuiltInType.VOID || declaredResult === UnitType
        val never = declaredResult === NeverType
        val erased = declaredResult is GenericType
        if (unit || never) print("((java.util.function.Supplier<" + (if (never) "Object" else boxed(UnitType)) + ">) () -> { ")
        if (erased) print("((" + boxed(expression.realType) + ")(")
        when (callee) {
            is DefType -> {
                val owner =
                    if (callee.glue) callee.owner!!.replace('/', '.') else callee.external?.fullRelativePath?.replace(
                        '/',
                        '.'
                    )
                if (owner != null && owner != source.fullRelativePath.replace('/', '.')) print("$owner.")
                print((if (callee.glue) (if (callee.isPrefixed) "_" else "") else "_") + callee.name + "(")
                for (index in expression.arguments.indices) {
                    if (index > 0) print(", ")
                    val substitutions =
                        callee.generics.mapIndexed { i, name -> GenericType(name, callee.declarationId, i) }
                            .associateWith { expression.specialization[it.key] ?: it }
                    emitExpected(
                        expression.arguments[index],
                        TypeSubstitution.apply(callee.parameters[index].second, substitutions)
                    )
                }
                if (callee.hasGenerics() && (!callee.glue || callee.reifiedNative)) {
                    if (expression.arguments.isNotEmpty()) print(", ")
                    print(tags(callee.generics.map { expression.specialization.getValue(it) }))
                }
                print(")")
            }

            is StructType -> {
                val type = expression.realType as StructType
                val raw = if (type is AppliedStructType) type.base else type
                print("new " + getJavaTypeName(raw) + "(")
                for (index in expression.arguments.indices) {
                    if (index > 0) print(", ")
                    emitExpected(expression.arguments[index], type.parameters[index].second)
                }
                if (raw.hasGenerics()) {
                    if (expression.arguments.isNotEmpty()) print(", "); print(tag(TypeTags.expression(type)))
                }
                print(")")
            }

            is LambdaType -> {
                val invocation =
                    render(expression.item) + ".apply(" + (if (callee.parameters.size > 4) "new Object[]{" else "") + expression.arguments.joinToString(
                        ", "
                    ) { render(it) } + (if (callee.parameters.size > 4) "}" else "") + ")"
                print(if (unit) invocation else objectCast(expression.realType, invocation))
            }

            else -> error("Invalid checked callee ${callee.name}")
        }
        if (erased) print("))")
        if (unit) print("; return com.kingmang.ixion.runtime.UnitValue.INSTANCE; }).get()")
        if (never) print("; throw new IllegalStateException(\"never returned\"); }).get()")
        return Optional.empty()
    }


    override fun visitEmpty(expression: EmptyExpression): Optional<String> {
        return Optional.empty()
    }

    override fun visitEmptyList(expression: EmptyListExpression): Optional<String> {
        print("new com.kingmang.ixion.runtime.CollectionUtil.IxListWrapper(" + tag(TypeTags.expression((expression.realType as ListType).contentType)) + ")")
        return Optional.empty()
    }

    override fun visitGroupingExpr(expression: GroupingExpression): Optional<String> {
        print("(")
        expression.expression.accept(this)
        print(")")
        return Optional.empty()
    }

    override fun visitIdentifierExpr(expression: IdentifierExpression): Optional<String> {
        val name = expression.identifier.source
        val declaration = currentContext!!.getVariable(name)
        if (declaration is ConstructorType && declaration.variant.fields.isEmpty()) emitAdt(
            expression.realType as AdtType,
            declaration.variant,
            emptyList()
        )
        else print(
            renamed[name]
                ?: (sourceName(name) + if (currentContext!!.getVariableMutability(name) == Mutability.MUTABLE) "[0]" else "")
        )
        return Optional.empty()
    }

    override fun visitIndexAccess(expression: IndexAccessExpression): Optional<String> {
        val base = render(expression.left)
        val index = render(expression.right)
        if (expression.left.realType === BuiltInType.STRING) print("String.valueOf($base.charAt($index))")
        else print("com.kingmang.ixion.modules.Prelude.get($base, $index)")
        return Optional.empty()
    }

    override fun visitLiteralExpr(expression: LiteralExpression): Optional<String> {
        if (expression.realType is BuiltInType) {
            when (expression.realType) {
                BuiltInType.STRING -> print(quoted(expression.literal.source))
                BuiltInType.CHAR -> {
                    val escapedChar: String = getEscapedChar(expression)
                    print("'$escapedChar'")
                }

                BuiltInType.FLOAT -> print(expression.literal.source.removeSuffix("f") + "f")
                BuiltInType.DOUBLE -> print(expression.literal.source.removeSuffix("d") + "d")
                else -> print(expression.literal.source)
            }
        } else {
            when (expression.literal.type) {
                TokenType.CHAR -> {
                    val escapedChar: String = getEscapedChar(expression)
                    print("'$escapedChar'")
                }

                TokenType.STRING -> print(quoted(expression.literal.source))
                else -> print(expression.literal.source)
            }
        }
        return Optional.empty()
    }

    override fun visitLiteralList(expression: LiteralListExpression): Optional<String> {
        val content = (expression.realType as ListType).contentType
        print("new com.kingmang.ixion.runtime.CollectionUtil.IxListWrapper(new java.util.ArrayList<>(java.util.Arrays.asList(")
        for (index in expression.entries.indices) {
            if (index > 0) print(", "); emitExpected(expression.entries[index], content)
        }
        print(")), " + tag(TypeTags.expression(content)) + ")")
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
        var value = render(expression.expression)
        for (index in expression.identifiers.indices) {
            val type = expression.typeChain[index] as StructType
            val raw = if (type is AppliedStructType) type.base else type
            val name = expression.identifiers[index].identifier.source
            value = objectCast(
                expression.typeChain[index + 1]!!,
                "($value).ixField(${raw.parameters.indexOfFirst { it.first == name }})"
            )
        }
        print(value)
        return Optional.empty()
    }

    override fun visitLambda(expression: LambdaExpression): Optional<String> {
        val type = expression.realType as LambdaType
        val previous = currentContext
        val previousRenamed = HashMap(renamed)
        val previousDepth = caseDepth
        val outer = functionStack.peek()!!
        val lambdaId = dataCounter++
        val function = DefType("lambda$" + lambdaId, type.parameters, outer.generics.toMutableList())
        val argumentPrefix = "ixArgument${lambdaId}_"
        val argumentArray = "ixArguments$lambdaId"
        function.returnType = type.returnType
        val iface = getJavaTypeName(type)
        print("((java.util.function.Supplier<$iface>) () -> {\n")
        indentLevel++
        for ((index, capture) in expression.captures.withIndex()) {
            val name = "ixCapture${dataCounter++}_$index"
            val reference = IdentifierExpression(
                expression.position,
                com.kingmang.ixion.lexer.Token(
                    TokenType.IDENTIFIER,
                    expression.position!!.line,
                    expression.position.col,
                    capture.first
                )
            )
            reference.realType = capture.second
            val value = render(reference)
            println("final " + getJavaTypeName(capture.second) + " $name = $value;")
            renamed[capture.first] = name
        }
        indent(); print("return ($iface) com.kingmang.ixion.runtime.FunctionTags.register(($iface) (")
        if (type.parameters.size > 4) print("Object[] $argumentArray")
        else print(type.parameters.indices.joinToString(", ") { "Object $argumentPrefix$it" })
        print(") -> {\n"); indentLevel++
        currentContext = expression.body.context
        caseDepth = 0
        functionStack.push(function); boxedFunctions.add(function)
        for ((index, parameter) in type.parameters.withIndex()) {
            val name = "ixParameter${dataCounter++}_$index"
            renamed[parameter.first] = name
            val value = if (type.parameters.size > 4) "$argumentArray[$index]" else "$argumentPrefix$index"
            println(
                "final " + getJavaTypeName(parameter.second) + " $name = " + objectCast(
                    parameter.second,
                    value
                ) + ";"
            )
        }
        println("try {"); indentLevel++
        expression.body.accept(this)
        closeNeverPath(expression.body)
        indentLevel--; println("} catch (com.kingmang.ixion.runtime.CaseReturn ixReturn) {"); indentLevel++
        println("return ixReturn.getValue();")
        indentLevel--; println("}")
        indentLevel--; indent(); print("}, " + tag(TypeTags.expression(type)) + ");\n")
        boxedFunctions.remove(function); functionStack.pop()
        currentContext = previous
        caseDepth = previousDepth
        renamed.clear(); renamed.putAll(previousRenamed)
        indentLevel--; indent(); print("}).get()")
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
        val previous = currentContext
        currentContext = statement.context
        println("{"); indentLevel++
        statement.statements.filterNotNull().forEach { it.accept(this) }
        indentLevel--; println("}")
        currentContext = previous
        return Optional.empty()
    }

    override fun visitEnum(statement: EnumStatement): Optional<String> {
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
        val previous = currentContext
        val id = dataCounter++
        val type = statement.block.context.getVariable(statement.name.source)!!
        val iterator = statement.expression.realType is ExternalType
        if (iterator) {
            indent(); print("final java.util.Iterator<?> ixIterator$id = "); statement.expression.accept(this); print(";\n")
            println("while (ixIterator$id.hasNext()) {")
        } else {
            indent(); print("for (Object ixItem$id : ")
            if (statement.expression.realType === BuiltInType.STRING) print("com.kingmang.ixion.runtime.DataIterables.characters(")
            statement.expression.accept(this)
            if (statement.expression.realType === BuiltInType.STRING) print(")")
            print(") {\n")
        }
        indentLevel++
        currentContext = statement.block.context
        println(
            "final " + getJavaTypeName(type) + " " + sourceName(statement.name.source) + " = " + objectCast(
                type,
                if (iterator) "ixIterator$id.next()" else "ixItem$id"
            ) + ";"
        )
        statement.block.accept(this)
        indentLevel--; println("}")
        currentContext = previous
        return Optional.empty()
    }

    override fun visitFunctionStmt(statement: DefStatement): Optional<String> {
        val function = statement.functionType!!
        functionStack.push(function)
        val previous = currentContext
        val previousDepth = caseDepth
        caseDepth = 0
        val unit =
            function.returnType === BuiltInType.VOID || function.returnType === UnitType || function.returnType === NeverType
        val resultType = if (unit) "void" else getJavaTypeName(function.returnType)
        indent(); print("public static ")
        if (function.name == "main") print("void main(String[] args)")
        else {
            print("$resultType _${function.name}(")
            print(function.parameters.joinToString(", ") { getJavaTypeName(it.second) + " " + sourceName(it.first) })
            if (function.hasGenerics()) {
                if (function.parameters.isNotEmpty()) print(", "); print("String[] __ixTypes")
            }
            print(")")
        }
        print(" {\n"); indentLevel++
        println("try {"); indentLevel++
        currentContext = statement.body!!.context
        statement.body.accept(this)
        closeNeverPath(statement.body)
        indentLevel--; println("} catch (com.kingmang.ixion.runtime.CaseReturn ixReturn) {"); indentLevel++
        if (unit) println("return;") else println(
            "return " + objectCast(
                function.returnType,
                "ixReturn.getValue()"
            ) + ";"
        )
        indentLevel--; println("}")
        indentLevel--; println("}")
        currentContext = previous
        caseDepth = previousDepth
        functionStack.pop()
        return Optional.empty()
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
                "com.kingmang.ixion.runtime.CollectionUtil.IxListWrapper"
            }

            else -> getJavaTypeName(type)
        }
    }

    override fun visitIf(statement: IfStatement): Optional<String> {
        indent(); print("if ("); statement.condition.accept(this); print(") ")
        statement.trueBlock.accept(this)
        if (statement.falseStatement != null) {
            indent(); print("else "); statement.falseStatement.accept(this)
        }
        return Optional.empty()
    }

    override fun visitUse(statement: UseStatement): Optional<String> {
        return Optional.empty()
    }


    override fun visitParameterStmt(statement: ParameterStatement): Optional<String> {
        return Optional.empty()
    }

    override fun visitReturnStmt(statement: ReturnStatement): Optional<String> {
        val function = functionStack.peek()!!
        val value = statement.expression
        val unit = function.returnType === BuiltInType.VOID || function.returnType === UnitType
        indent()
        if (caseDepth > 0) {
            print("throw new com.kingmang.ixion.runtime.CaseReturn(")
            if (value == null || value is EmptyExpression) print("com.kingmang.ixion.runtime.UnitValue.INSTANCE") else emitExpected(
                value,
                function.returnType
            )
            print(");\n")
        } else if (unit && function in boxedFunctions) {
            print("return ")
            if (value == null || value is EmptyExpression) print("com.kingmang.ixion.runtime.UnitValue.INSTANCE") else value.accept(
                this
            )
            print(";\n")
        } else if (unit) {
            if (value != null && value !is EmptyExpression && value !is UnitExpression) {
                value.accept(this); print(";\n"); indent()
            }
            print("return;\n")
        } else {
            print("return "); emitExpected(value!!, function.returnType); print(";\n")
        }
        return Optional.empty()
    }

    override fun visitStruct(statement: StructStatement): Optional<String> {
        val structType = currentContext!!.getVariableTyped<StructType>(statement.name.source)

        println("public static final class " + sourceName(structType!!.name) + " implements com.kingmang.ixion.runtime.IxDataValue {")

        indentLevel++
        for (pair in structType.parameters) {
            val fieldName = sourceName(pair.first)
            val fieldType = pair.second
            val javaType = getJavaTypeName(fieldType)
            println("private final $javaType $fieldName;")
        }

        println("")
        if (structType.hasGenerics()) println("private final String __ixType;")
        print("public " + sourceName(structType.name) + "(")

        for (i in structType.parameters.indices) {
            val param: Pair<String?, IxType?> = structType.parameters[i]
            if (i > 0) print(", ")
            val javaType = getJavaTypeName(param.second)
            print(javaType + " " + sourceName(param.first!!))
        }
        if (structType.hasGenerics()) {
            if (structType.parameters.isNotEmpty()) print(", "); print("String ixType")
        }
        println(") {")

        indentLevel++
        if (structType.hasGenerics()) println("this.__ixType = ixType;")
        for (param in structType.parameters) {
            println("this." + sourceName(param.first) + " = " + sourceName(param.first) + ";")
        }
        indentLevel--
        println("}")

        println("")
        println(
            "public String ixType() { return " + (if (structType.hasGenerics()) "__ixType" else quoted(
                TypeTags.constant(
                    structType
                )
            )) + "; }"
        )
        println("public Object ixField(int index) {")
        indentLevel++
        for ((index, field) in structType.parameters.withIndex()) println(
            "if (index == $index) return ${
                sourceName(
                    field.first
                )
            };"
        )
        println("throw new IndexOutOfBoundsException();")
        indentLevel--; println("}")
        println("@Override")
        println("public String toString() {")
        indentLevel++
        print("return \"" + structType.name + "{\" + ")
        for (i in structType.parameters.indices) {
            val param: kotlin.Pair<String, IxType?> = structType.parameters[i]
            val fieldName = param.first
            if (i > 0) print(" + \", \" + ")
            print("\"$fieldName=\" + " + sourceName(fieldName!!))
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
        val type = currentContext!!.getVariable(statement.identifier())!!
        val javaType = getJavaTypeName(type)
        val mutable = statement.mutable
        indent(); print("final $javaType" + if (mutable) "[] " else " ")
        print(sourceName(statement.identifier()) + " = ")
        if (mutable) print("new $javaType[]{")
        emitExpected(statement.expression, type)
        if (mutable) print("}")
        print(";\n")
        return Optional.empty()
    }

    override fun visitWhile(statement: WhileStatement): Optional<String> {
        indent()
        print("while (Boolean.TRUE.equals(")
        statement.condition.accept(this)
        println(")) {")

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
                "com.kingmang.ixion.runtime.CollectionUtil.IxListWrapper"
            }

            UnitType -> "com.kingmang.ixion.runtime.UnitValue"
            NeverType -> "Object"
            is GenericType -> "Object"
            is AdtType -> "com.kingmang.ixion.runtime.AdtValue"
            is TupleType -> "com.kingmang.ixion.runtime.TupleValue"
            is AliasType -> getJavaTypeName(type.body)
            is LambdaType -> type.functionalInterface.name
            is UnionType -> "Object"
            is StructType -> if (type.declarationId.module == source.fullRelativePath) sourceName(type.name)
            else type.declarationId.module.replace('/', '.') + "." + sourceName(type.name)

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
