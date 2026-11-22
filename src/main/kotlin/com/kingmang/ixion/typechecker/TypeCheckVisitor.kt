package com.kingmang.ixion.typechecker

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.matching.*
import com.kingmang.ixion.parser.Node
import com.kingmang.ixion.api.Context
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxApi.Companion.exit
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.exception.*
import com.kingmang.ixion.lexer.Position
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.*
import com.kingmang.ixion.runtime.BuiltInType.Companion.widen
import com.kingmang.ixion.runtime.CollectionUtil.Companion.zip
import com.kingmang.ixion.runtime.ixfunction.IxFunction0
import com.kingmang.ixion.runtime.ixfunction.IxFunction1
import com.kingmang.ixion.runtime.ixfunction.IxFunction2
import com.kingmang.ixion.runtime.ixfunction.IxFunction3
import com.kingmang.ixion.runtime.ixfunction.IxFunction4
import com.kingmang.ixion.typechecker.TypeResolver.typesMatch
import java.io.File
import java.lang.String
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Collectors
import kotlin.Any
import kotlin.IllegalStateException
import kotlin.Pair

/**
 * Visitor for type checking and validation of AST nodes
 * Ensures type safety and resolves type information throughout the program
 */
class TypeCheckVisitor(private val ixApi: IxApi, private val rootContext: Context, ixFile: IxFile) :
    Visitor<Optional<IxType>> {
    private val file: File = ixFile.file
    private val functionStack = Stack<DefType>()
    private val inferredLambdaReturns = IdentityHashMap<DefType, MutableList<IxType>>()
    private var currentContext: Context

    /**
     * @param ixApi The API instance for error reporting
     * @param rootContext The root context for variable resolution
     * @param ixFile The source file being type checked
     */
    init {
        this.currentContext = this.rootContext
    }

    private var expectedType: IxType? = null

    private class CheckFailure : RuntimeException(null, null, false, false)

    private fun fail(node: Node, message: kotlin.String): Nothing {
        FeatureException().send(ixApi, file, node, message)
        throw CheckFailure()
    }

    private fun check(expression: Expression, expected: IxType? = null): IxType {
        if (expression is AssignExpression) fail(
            expression,
            "assignment.expression: assignment is a statement and has no result"
        )
        val previous = expectedType
        expectedType = expected
        return try {
            expression.accept(this).orElseGet { UnknownType() }
        } catch (_: CheckFailure) {
            UnknownType()
        } finally {
            expectedType = previous
        }
    }

    private fun resolve(node: TypeStatement): IxType = TypeSyntax.resolve(
        node,
        { name, syntax -> currentContext.getType(name) ?: fail(syntax, "type.unknown: $name") },
        { message -> fail(node, "type.invalid: $message") })

    private fun result(expression: Expression, type: IxType): Optional<IxType> {
        expression.realType = type
        return Optional.of(type)
    }

    private fun substitute(type: IxType, parameters: List<GenericType>, arguments: Map<kotlin.String, IxType>): IxType =
        TypeSubstitution.apply(type, parameters.associateWith { arguments[it.key] ?: it })

    private fun infer(
        template: IxType,
        actual: IxType,
        parameters: List<GenericType>,
        arguments: MutableMap<kotlin.String, IxType>,
        fixed: Set<kotlin.String> = emptySet()
    ) {
        when {
            template is GenericType && template in parameters -> {
                val old = arguments[template.key]
                if (old == null) arguments[template.key] = actual
                else if (template.key !in fixed && SemanticTypes.of(old) != SemanticTypes.of(actual)) arguments[template.key] =
                    common(listOf(old, actual))
            }

            template is AdtType && actual is AdtType && template.declaration.id == actual.declaration.id ->
                template.arguments.zip(actual.arguments)
                    .forEach { infer(it.first, it.second, parameters, arguments, fixed) }

            template is AppliedStructType && actual is AppliedStructType && template.declarationId == actual.declarationId ->
                template.arguments.zip(actual.arguments)
                    .forEach { infer(it.first, it.second, parameters, arguments, fixed) }

            template is ListType && actual is ListType -> infer(
                template.contentType,
                actual.contentType,
                parameters,
                arguments,
                fixed
            )

            template is TupleType && actual is TupleType -> template.elements.zip(actual.elements)
                .forEach { infer(it.first, it.second, parameters, arguments, fixed) }

            template is LambdaType && actual is LambdaType -> {
                template.parameters.zip(actual.parameters)
                    .forEach { infer(it.first.second, it.second.second, parameters, arguments, fixed) }
                infer(template.returnType, actual.returnType, parameters, arguments, fixed)
            }
        }
    }

    private fun containsParameter(type: IxType, parameters: List<GenericType>): Boolean = when (type) {
        is GenericType -> type in parameters
        is AdtType -> type.arguments.any { containsParameter(it, parameters) }
        is AppliedStructType -> type.arguments.any { containsParameter(it, parameters) }
        is ListType -> containsParameter(type.contentType, parameters)
        is TupleType -> type.elements.any { containsParameter(it, parameters) }
        is LambdaType -> type.parameters.any {
            containsParameter(
                it.second,
                parameters
            )
        } || containsParameter(type.returnType, parameters)

        is UnionType -> type.types.any { containsParameter(it, parameters) }
        else -> false
    }

    private fun contextual(expression: Expression): Boolean = when (expression) {
        is LiteralListExpression -> expression.entries.isEmpty()
        is LambdaExpression -> expression.parameters.any { it.type == null }
        is IdentifierExpression -> (currentContext.getVariable(expression.identifier.source) as? ConstructorType)?.variant?.fields?.isEmpty() == true
        else -> false
    }

    private fun common(types: List<IxType>): IxType {
        val values = types.filter { it !== NeverType }.distinctBy { SemanticTypes.of(it) }
        if (values.isEmpty()) return NeverType
        if (values.size == 1) return if (values.single() === BuiltInType.VOID) UnitType else values.single()
        if (values.all { it is BuiltInType && it.isNumeric }) return values.reduce { a, b ->
            widen(
                a as BuiltInType,
                b as BuiltInType
            )!!
        }
        return UnionType.of(values)
    }

    override fun visitUnit(expression: UnitExpression): Optional<IxType> = result(expression, UnitType)
    override fun visitTuple(expression: TupleExpression): Optional<IxType> {
        val expected = expectedType as? TupleType
        return result(
            expression,
            TupleType(expression.elements.mapIndexed { i, item -> check(item, expected?.elements?.getOrNull(i)) })
        )
    }

    private fun appliedReference(expression: TypeApplicationExpression): IxType {
        val variable = currentContext.getVariable(expression.name.source)
        val overloads = currentContext.getFunctions(expression.name.source)
        val base = variable ?: overloads.singleOrNull() ?: if (overloads.isNotEmpty()) {
            val arity = expression.types.size
            val matching = overloads.filter { it.generics.size == arity }
            if (matching.size == 1) matching.single() else fail(
                expression,
                if (matching.isEmpty()) "type.no_overload: ${expression.name.source}"
                else "type.ambiguous_overload: ${expression.name.source}"
            )
        } else fail(
            expression,
            "name.unknown: ${expression.name.source}"
        )
        val args = expression.types.map(::resolve)
        return when (base) {
            is ConstructorType -> {
                if (base.owner.parameters.size != args.size) fail(expression, "type.arity: ${base.name}")
                ConstructorType(base.owner, base.variant, args)
            }

            is DefType -> {
                if (base.generics.size != args.size) fail(expression, "type.arity: ${base.name}")
                expression.resolvedTypes = args
                base
            }

            is StructType -> {
                if (base.generics.size != args.size) fail(expression, "type.arity: ${base.name}")
                AppliedStructType(base, args)
            }

            else -> fail(expression, "type.not_generic: ${expression.name.source}")
        }
    }

    override fun visitTypeApplication(expression: TypeApplicationExpression): Optional<IxType> {
        val type = appliedReference(expression)
        return if (type is ConstructorType) {
            if (type.variant.fields.isNotEmpty()) fail(expression, "constructor.requires_arguments: ${type.name}")
            result(expression, construct(expression, type, emptyList()))
        } else result(expression, type)
    }

    private fun construct(node: Expression, constructor: ConstructorType, args: List<Expression>): AdtType {
        if (args.size != constructor.variant.fields.size) fail(
            node,
            "constructor.arity: ${constructor.name} expects ${constructor.variant.fields.size} fields"
        )
        val parameters = constructor.owner.parameters
        val arguments = linkedMapOf<kotlin.String, IxType>()
        constructor.arguments?.let { parameters.zip(it).forEach { (p, t) -> arguments[p.key] = t } }
        val expected = expectedType as? AdtType
        if (expected != null && expected.declaration.id == constructor.owner.id)
            parameters.zip(expected.arguments).forEach { (p, t) -> arguments.putIfAbsent(p.key, t) }
        val fixed = arguments.keys.toSet()
        for (i in args.indices.sortedBy { if (contextual(args[it])) 1 else 0 }) {
            val template = constructor.variant.fields[i].second
            val anticipated = substitute(template, parameters, arguments)
            val actual = check(args[i], anticipated.takeUnless { containsParameter(it, parameters) })
            infer(template, actual, parameters, arguments, fixed)
        }
        if (parameters.any {
                arguments[it.key] == null || arguments[it.key] is UnknownType || containsParameter(
                    arguments.getValue(it.key),
                    parameters
                )
            }) fail(node, "type.cannot_infer: ${constructor.name}; provide type arguments or an expected type")
        val type = constructor.owner.applied(parameters.map { arguments.getValue(it.key) })
        for ((i, field) in type.fields(constructor.variant).withIndex()) {
            val actual = check(args[i], field.second)
            if (!typesMatch(field.second, actual)) fail(
                args[i],
                "constructor.field_type: expected ${field.second}, got $actual"
            )
        }
        return type
    }

    override fun visitUpdate(expression: UpdateExpression): Optional<IxType> {
        val type = check(expression.base) as? StructType ?: fail(expression, "update.expected_struct")
        val names = hashSetOf<kotlin.String>()
        for ((name, value) in expression.fields) {
            if (!names.add(name.source)) fail(expression, "update.duplicate_field: ${name.source}")
            val field = type.parameters.find { it.first == name.source } ?: fail(
                expression,
                "update.unknown_field: ${name.source}"
            )
            if (!typesMatch(field.second, check(value, field.second))) fail(value, "update.field_type: ${name.source}")
        }
        return result(expression, type)
    }

    override fun visitBlockExpression(expression: BlockExpression): Optional<IxType> {
        val previous = currentContext
        currentContext = expression.block.context
        try {
            expression.block.accept(this)
            val type = if (ControlFlow.exits(expression.block)) NeverType else expression.result?.let {
                check(
                    it,
                    expectedType
                )
            } ?: UnitType
            return result(expression, type)
        } finally {
            currentContext = previous
        }
    }

    override fun visitCaseExpression(expression: CaseExpression): Optional<IxType> {
        val expected = expectedType
        val subject = check(expression.subject)
        val rows = arrayListOf<List<CoverPattern>>()
        val results = arrayListOf<IxType>()
        val previous = currentContext
        if (expression.branches.isEmpty() && subject !== NeverType) fail(
            expression,
            "match.empty: only never permits an empty case"
        )
        try {
            for (branch in expression.branches) {
                currentContext = previous
                val plan = PatternChecker(::resolve, { previous.getType(it) }) { p, message ->
                    fail(
                        p,
                        message
                    )
                }.check(branch.pattern, subject)
                branch.plan = plan
                val witness = PatternCoverage().witness(rows, listOf(plan.coverage), listOf(subject))
                if (witness == null) fail(
                    branch.pattern,
                    "match.unreachable: branch is covered by preceding unguarded patterns"
                )
                for (binding in plan.bindings) branch.context.setVariableType(binding.name, binding.type)
                currentContext = branch.context
                if (branch.guard != null && check(branch.guard) !== BuiltInType.BOOLEAN) fail(
                    branch.guard,
                    "match.guard_type: expected bool"
                )
                val type = check(branch.body, expected)
                if (expected != null && type !is UnknownType && !typesMatch(expected, type)) fail(
                    branch.body,
                    "match.branch_type: expected $expected, got $type"
                )
                results.add(type)
                if (branch.guard == null) rows.add(listOf(plan.coverage))
            }
            val missing = PatternCoverage().witness(rows, listOf(CoverPattern()), listOf(subject))
            if (missing != null) fail(expression, "match.non_exhaustive: missing ${missing.single()}")
        } catch (limit: IllegalStateException) {
            fail(expression, limit.message ?: "match.analysis_limit")
        } finally {
            currentContext = previous
        }
        return result(
            expression, if (expected != null && results.any { it !== NeverType }) {
                if (expected === BuiltInType.VOID) UnitType else expected
            } else common(results)
        )
    }

    override fun visit(statement: Statement): Optional<IxType> {
        return try {
            statement.accept(this)
        } catch (_: CheckFailure) {
            Optional.empty()
        }
    }

    /**
     * @param statement Type alias statement to process
     * @return Empty optional as type aliases don't produce values
     */
    override fun visitTypeAlias(statement: TypeAliasStatement): Optional<IxType> {
        // Aliases are fully resolved before any body is checked.
        return Optional.empty()
    }

    /**
     * @param expression Assignment expression to type check
     * @return Empty optional as assignments don't produce values
     */
    override fun visitAssignExpr(expression: AssignExpression): Optional<IxType> {
        check(expression.left)
        check(expression.right, expression.left.realType)

        when (expression.left) {
            is IdentifierExpression -> {
                if (currentContext.getVariableMutability(expression.left.identifier.source) == com.kingmang.ixion.api.IxionConstant.Mutability.IMMUTABLE)
                    fail(expression.left, "mutation.immutable_binding: ${expression.left.identifier.source}")
                if (!typesMatch(expression.left.realType, expression.right.realType)) {
                    BadAssignmentException().send(
                        ixApi, file, expression, expression.left.identifier.source,
                        expression.left.realType.name, expression.right.realType.name
                    )
                }
            }

            is PropertyAccessExpression -> {
                fail(expression.left, "mutation.field: use a functional update with with")
            }

            is IndexAccessExpression -> fail(expression.left, "mutation.index: use std::updated and handle Option")
            else -> fail(expression.left, "mutation.target: expected a mutable local binding")
        }

        return Optional.empty()
    }

    override fun visitBad(expression: BadExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * @param expression Binary expression to type check
     * @return Optional containing the result type of the binary operation
     */
    override fun visitBinaryExpr(expression: BinaryExpression): Optional<IxType> {
        val t1 = Optional.of(check(expression.left))
        val t2 = Optional.of(check(expression.right))

        if (t1.isEmpty || t2.isEmpty) {
            ImplementationException().send(ixApi, file, expression, "Types in binary expression not determined.")
            return Optional.empty()
        }

        if (t1.get() === BuiltInType.ANY || t2.get() === BuiltInType.ANY) {
            CannotApplyOperatorException().send(
                ixApi, file, expression, expression.operator.source, t1.get().name, t2.get().name
            )
            return Optional.empty()
        }

        var totalType: IxType? = t1.get()

        when (expression.operator.type) {
            TokenType.ADD, TokenType.SUB, TokenType.MUL, TokenType.DIV, TokenType.MOD -> {
                if (t1.get() is BuiltInType && t2.get() is BuiltInType) {
                    totalType = widen(t1.get() as BuiltInType, t2.get() as BuiltInType)
                } else {
                    CannotApplyOperatorException().send(
                        ixApi, file, expression, expression.operator.source, t1.get().name, t2.get().name
                    )
                }
            }

            TokenType.EQUAL, TokenType.NOTEQUAL, TokenType.LT, TokenType.GT, TokenType.LE, TokenType.GE -> {
                if (t1.get() !is BuiltInType || t2.get() !is BuiltInType) fail(
                    expression,
                    "operator.data_comparison: structural equality is not implemented yet"
                )
                if (t1.get() is BuiltInType && t2.get() is BuiltInType) {
                    if (expression.operator.type != TokenType.EQUAL && expression.operator.type != TokenType.NOTEQUAL) {
                        val bt1 = t1.get() as BuiltInType
                        val bt2 = t2.get() as BuiltInType
                        if (bt1 == BuiltInType.STRING || bt2 == BuiltInType.STRING) {
                            CannotApplyOperatorException().send(
                                ixApi, file, expression, expression.operator.source, t1.get().name, t2.get().name
                            )
                        } else if (bt1 == BuiltInType.BOOLEAN || bt2 == BuiltInType.BOOLEAN) {
                            CannotApplyOperatorException().send(
                                ixApi, file, expression, expression.operator.source, t1.get().name, t2.get().name
                            )
                        }
                    }
                    totalType = BuiltInType.BOOLEAN
                }
            }

            TokenType.AND, TokenType.OR, TokenType.XOR -> {
                if (t1.get() is BuiltInType && t2.get() is BuiltInType) {
                    if (t1.get() != BuiltInType.BOOLEAN || t2.get() != BuiltInType.BOOLEAN) {
                        CannotApplyOperatorException().send(
                            ixApi, file, expression, expression.operator.source, t1.get().name, t2.get().name
                        )
                    }
                    totalType = BuiltInType.BOOLEAN
                }
            }

            else -> {}
        }

        expression.left.realType = t1.get()
        expression.right.realType = t2.get()

        expression.realType = totalType!!

        return Optional.of(totalType)
    }

    /**
     * @param statement Block statement to type check
     * @return Empty optional as blocks don't produce values
     */
    override fun visitBlockStmt(statement: BlockStatement): Optional<IxType> {
        val previous = currentContext
        currentContext = statement.context
        try {
            for (stmt in statement.statements.filterNotNull()) {
                try {
                    stmt.accept(this)
                } catch (_: CheckFailure) {
                }
            }
        } finally {
            currentContext = previous
        }
        return Optional.empty()
    }

    /**
     * @param expression Function call expression to type check
     * @return Optional containing the return type of the function call
     */
    override fun visitCall(expression: CallExpression): Optional<IxType> {
        var callee = when (val item = expression.item) {
            is IdentifierExpression -> {
                val value = currentContext.getVariable(item.identifier.source)
                if (value != null) value else {
                    val candidates = currentContext.getFunctions(item.identifier.source)
                    if (candidates.isEmpty()) fail(item, "name.unknown: ${item.identifier.source}")
                    else if (candidates.size == 1) candidates.single()
                    else {
                        val actual = expression.arguments.map {
                            if (it is LambdaExpression) UnknownType() else check(it)
                        }
                        val viable = candidates.filter { candidate ->
                            if (candidate.parameters.size != actual.size) return@filter false
                            val generic = candidate.generics.mapIndexed { i, name ->
                                GenericType(name, candidate.declarationId, i)
                            }
                            val inferred = linkedMapOf<kotlin.String, IxType>()
                            candidate.parameters.indices.forEach { i ->
                                if (actual[i] !is UnknownType) infer(candidate.parameters[i].second, actual[i], generic, inferred)
                            }
                            candidate.parameters.indices.all { i ->
                                actual[i] is UnknownType || typesMatch(
                                    substitute(candidate.parameters[i].second, generic, inferred), actual[i]
                                )
                            }
                        }
                        if (viable.size != 1) fail(
                            expression,
                            if (viable.isEmpty()) "call.no_overload: ${item.identifier.source}"
                            else "call.ambiguous_overload: ${item.identifier.source}"
                        )
                        viable.single()
                    }
                }
            }

            is TypeApplicationExpression -> appliedReference(item)
            else -> check(item)
        }
        // The native ABI takes Object, but only lists and the string overload are
        // language-level inputs. Explicit len[T] always selects the list signature.
        if (callee is DefType && callee.owner == "com/kingmang/ixion/modules/Prelude" &&
            callee.name == "len" && expression.item !is TypeApplicationExpression && expression.arguments.size == 1 &&
            check(expression.arguments.single()) === BuiltInType.STRING
        ) {
            val original = callee
            callee = DefType("len", arrayListOf("text" to BuiltInType.STRING), original.declarationId).also {
                it.returnType = BuiltInType.INT
                it.glue = true
                it.owner = original.owner
                it.jvmDescriptor = original.jvmDescriptor
            }
        }
        expression.item.realType = callee
        if (callee is ConstructorType) return result(expression, construct(expression, callee, expression.arguments))
        if (callee is DefType && callee.owner == "com/kingmang/ixion/modules/Prelude" && callee.name in setOf(
                "push",
                "pop"
            )
        )
            fail(expression, "list.immutable: use std::append or pattern matching instead of ${callee.name}")
        val parameters: List<Pair<kotlin.String, IxType>>
        val returnType: IxType
        val generic: List<GenericType>
        when (callee) {
            is DefType -> {
                parameters = callee.parameters
                returnType = callee.returnType
                generic = callee.generics.mapIndexed { i, name -> GenericType(name, callee.declarationId, i) }
            }

            is StructType -> {
                parameters = callee.parameters
                returnType = callee
                generic = callee.generics.mapIndexed { i, name -> GenericType(name, callee.declarationId, i) }
            }

            is LambdaType -> {
                parameters = callee.parameters; returnType = callee.returnType; generic = emptyList()
            }

            else -> fail(expression, "call.not_callable: ${callee.name}")
        }
        if (parameters.size != expression.arguments.size) fail(
            expression,
            "call.arity: expected ${parameters.size} arguments"
        )
        val arguments = linkedMapOf<kotlin.String, IxType>()
        (expression.item as? TypeApplicationExpression)?.resolvedTypes?.let {
            generic.zip(it).forEach { (p, t) -> arguments[p.key] = t }
        }
        expectedType?.takeIf { SemanticTypes.of(it) != com.kingmang.ixion.types.PrimitiveType.ANY }
            ?.let { infer(returnType, it, generic, arguments) }
        val fixed = arguments.keys.toSet()
        for (i in parameters.indices.sortedBy { if (contextual(expression.arguments[it])) 1 else 0 }) {
            val anticipated = substitute(parameters[i].second, generic, arguments)
            val argument = expression.arguments[i]
            val expectation = if (argument is LambdaExpression && anticipated is LambdaType &&
                anticipated.parameters.none { containsParameter(it.second, generic) }
            ) {
                if (containsParameter(anticipated.returnType, generic))
                    anticipated.copy(returnType = UnknownType("<lambda-result>")) else anticipated
            } else anticipated.takeUnless { containsParameter(it, generic) }
            val type = check(argument, expectation)
            infer(parameters[i].second, type, generic, arguments, fixed)
        }
        if (generic.any { arguments[it.key] == null || arguments[it.key] is UnknownType }) fail(
            expression,
            "type.cannot_infer: ${callee.name}"
        )
        expression.specialization = arguments.toMap()
        for (i in parameters.indices) {
            val expected = substitute(parameters[i].second, generic, arguments)
            val actual = check(expression.arguments[i], expected)
            if (!typesMatch(expected, actual)) fail(
                expression.arguments[i],
                "call.argument_type: expected $expected, got $actual"
            )
        }
        val resolved = if (callee is StructType && callee !is DefType && generic.isNotEmpty())
            AppliedStructType(callee, generic.map { arguments.getValue(it.key) }) else substitute(
            returnType,
            generic,
            arguments
        )
        return result(expression, if (resolved === BuiltInType.VOID) UnitType else resolved)
    }

    override fun visitEmpty(expression: EmptyExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * @param expression Empty list expression to type check
     * @return Optional containing the type of the empty list
     */
    override fun visitEmptyList(expression: EmptyListExpression): Optional<IxType> {
        return Optional.of(expression.realType)
    }

    override fun visitEnum(statement: EnumStatement): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * @param statement Export statement to process
     * @return Empty optional as exports don't produce values
     */
    override fun visitExport(statement: ExportStatement): Optional<IxType> {
        statement.stmt.accept(this)
        return Optional.empty()
    }

    /**
     * @param statement Expression statement to type check
     * @return Optional containing the type of the expression
     */
    override fun visitExpressionStmt(statement: ExpressionStatement): Optional<IxType> {
        if (statement.expression is AssignExpression) statement.expression.accept(this) else check(statement.expression)
        return Optional.of(statement.expression.realType)
    }

    /**
     * @param statement For loop statement to type check
     * @return Empty optional as loops don't produce values
     */
    override fun visitFor(statement: ForStatement): Optional<IxType> {
        currentContext = statement.block.context

        val b: Optional<IxType> = statement.expression.accept(this)
        if (b.isPresent) {
            when (b.get()) {
                BuiltInType.STRING -> currentContext.setVariableType(statement.name.source, BuiltInType.CHAR)
                is ExternalType -> {
                    if ((b.get() as ExternalType).foundClass!!.getName() == "java.util.Iterator") {
                        currentContext.setVariableType(statement.name.source, BuiltInType.INT)
                    }
                }

                is ListType -> {
                    currentContext.setVariableType(statement.name.source, (b.get() as ListType).contentType)
                }

                else -> NotIterableException().send(ixApi, file, statement.expression, b.get().name)
            }
        }

        statement.block.accept(this)

        this.popContext()
        return Optional.empty()
    }

    /**
     * @param statement Function statement to type check
     * @return Empty optional as function definitions don't produce values
     */
    override fun visitFunctionStmt(statement: DefStatement): Optional<IxType> {
        val funcType = statement.functionType
        if (funcType != null) {
            functionStack.add(funcType)
            val childEnvironment = statement.body!!.context

            currentContext = childEnvironment

            statement.body.accept(this)

            if (!ControlFlow.exits(statement.body)) {
                if (SemanticTypes.of(funcType.returnType) != com.kingmang.ixion.types.PrimitiveType.UNIT) {
                    ReturnTypeMismatchException().send(
                        ixApi, file, statement, funcType.name, funcType.returnType.name, UnitType.name
                    )
                }
                val returnStmt = ReturnStatement(
                    Position(0, 0),
                    EmptyExpression(Position(0, 0))
                )
                statement.body.statements.add(returnStmt)
            }

            this.popContext()
            this.functionStack.pop()
        }
        return Optional.empty()
    }

    /**
     * @param expression Grouping expression to type check
     * @return Optional containing the type of the grouped expression
     */
    override fun visitGroupingExpr(expression: GroupingExpression): Optional<IxType> {
        return result(expression, check(expression.expression, expectedType))
    }

    /**
     * @param expression Identifier expression to resolve
     * @return Optional containing the type of the identifier
     */
    override fun visitIdentifierExpr(expression: IdentifierExpression): Optional<IxType> {
        val variable = currentContext.getVariable(expression.identifier.source)
        val functions = currentContext.getFunctions(expression.identifier.source)
        val type = variable ?: functions.singleOrNull() ?: fail(
            expression,
            if (functions.isEmpty()) "name.unknown: ${expression.identifier.source}"
            else "call.ambiguous_overload: ${expression.identifier.source}"
        )
        return if (type is ConstructorType) {
            if (type.variant.fields.isNotEmpty()) fail(expression, "constructor.requires_arguments: ${type.name}")
            result(expression, construct(expression, type, emptyList()))
        } else result(expression, type)
    }

    /**
     * @param statement If statement to type check
     * @return Empty optional as if statements don't produce values
     */
    override fun visitIf(statement: IfStatement): Optional<IxType> {
        if (check(statement.condition) !== BuiltInType.BOOLEAN) fail(statement.condition, "condition.expected_bool")
        statement.trueBlock.accept(this)
        statement.falseStatement?.accept(this)
        return Optional.empty()
    }

    override fun visitUse(statement: UseStatement): Optional<IxType> {
        return Optional.empty()
    }

    override fun visitIndexAccess(expression: IndexAccessExpression): Optional<IxType> {
        val expectedOption = (expectedType as? AdtType)?.takeIf { it.declaration.id == PreludeTypes.option.id }
        val source = check(expression.left, expectedOption?.arguments?.single()?.let(::ListType))
        val index = check(expression.right, BuiltInType.INT)
        if (index != BuiltInType.INT) {
            ParameterTypeMismatchException().send(ixApi, file, expression.right, index.name, "int")
        }
        val elementType = when (source) {
            BuiltInType.STRING -> BuiltInType.STRING
            is ListType -> PreludeTypes.option(source.contentType)
            else -> {
                CannotApplyOperatorException().send(ixApi, file, expression, "[]", source.name, index.name)
                return Optional.empty()
            }
        }
        return result(expression, elementType)
    }

    /**
     * @param expression Literal expression to type check
     * @return Optional containing the type of the literal
     */
    override fun visitLiteralExpr(expression: LiteralExpression): Optional<IxType> {
        return result(expression, TypeUtils.getFromToken(expression.literal.type))
    }

    /**
     * @param expression List literal expression to type check
     * @return Optional containing the type of the list
     */
    override fun visitLiteralList(expression: LiteralListExpression): Optional<IxType> {
        val expected = expectedType as? ListType
        if (expression.entries.isEmpty() && expected == null) fail(
            expression,
            "type.empty_list: provide an element type"
        )
        val elements = expression.entries.map { check(it, expected?.contentType) }
        val content = expected?.contentType ?: common(elements)
        for ((index, type) in elements.withIndex()) if (!typesMatch(content, type)) fail(
            expression.entries[index],
            "list.element_type"
        )
        return result(expression, ListType(content))
    }

    /**
     * @param statement Match statement to type check
     * @return Empty optional as match statements don't produce values
     */


    override fun visitModuleAccess(expression: ModuleAccessExpression): Optional<IxType> {
        expression.foreign.accept(this)
        return Optional.empty()
    }

    override fun visitParameterStmt(statement: ParameterStatement): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * @param expression Postfix expression to type check
     * @return Empty optional as postfix expressions don't produce new values
     */
    override fun visitPostfixExpr(expression: PostfixExpression): Optional<IxType> {
        expression.realType = expression.expression.accept(this).get()
        val target = expression.expression as? IdentifierExpression
        if (target != null && currentContext.getVariableMutability(target.identifier.source) == com.kingmang.ixion.api.IxionConstant.Mutability.IMMUTABLE)
            fail(expression, "mutation.immutable_binding: ${target.identifier.source}")

        if (expression.expression !is IdentifierExpression ||
            !(expression.realType is BuiltInType && expression.realType.isNumeric)
        ) {
            CannotPostfixException().send(
                ixApi,
                file,
                expression.expression,
                expression.operator.source,
                expression.realType.name
            )
        }
        return Optional.of(expression.realType)
    }

    /**
     * @param expression Prefix expression to type check
     * @return Optional containing the type of the prefixed expression
     */
    override fun visitPrefix(expression: PrefixExpression): Optional<IxType> {
        val type = check(expression.right)
        if (expression.operator.type == TokenType.NOT && type !== BuiltInType.BOOLEAN ||
            expression.operator.type != TokenType.NOT && !type.isNumeric
        ) fail(expression, "operator.prefix_type")
        return result(expression, type)
    }

    /**
     * @param expression Property access expression to type check
     * @return Optional containing the type of the accessed property
     */
    override fun visitPropertyAccess(expression: PropertyAccessExpression): Optional<IxType> {
        val t: Optional<IxType> = expression.expression.accept(this)

        val typeChain = ArrayList<IxType?>()

        if (t.isPresent) {
            val exprType = t.get()
            val pointer: StructType?
            var result: IxType? = null
            if (exprType is MonomorphizedStruct) {
                pointer = exprType.struct
                typeChain.add(pointer)
                result = pointer

                result = getTempMSTType(expression, typeChain, pointer, result)
                if (result is GenericType) {
                    result = exprType.resolved[result.key]
                }
            } else if (exprType is StructType) {
                pointer = exprType
                typeChain.add(pointer)
                result = exprType

                result = getTempMSTType(expression, typeChain, pointer, result)
            }
            if (result == null) {
                FieldNotPresentException().send(
                    ixApi,
                    file,
                    expression,
                    expression.identifiers.first().identifier.source,
                    exprType.name
                )
                return Optional.empty()
            }
            expression.realType = result
        } else {
            MethodNotFoundException().send(ixApi, file, expression.expression, "ree")
        }
        expression.typeChain = typeChain

        return Optional.ofNullable(expression.realType)
    }

    override fun visitLambda(expression: LambdaExpression): Optional<IxType> {
        val expected = expectedType as? LambdaType
        val previous = currentContext
        val context = expression.body.context
        context.parent = previous
        val parameters = expression.parameters.mapIndexed { index, param ->
            val type = param.type?.let(::resolve) ?: expected?.parameters?.getOrNull(index)?.second
            ?: fail(expression, "lambda.parameter_type: ${param.name.source}")
            context.setVariableType(param.name.source, type)
            param.name.source to type
        }.toMutableList()
        val inferResult = expression.inferredReturn && (expected == null || expected.returnType is UnknownType)
        var returnType = if (inferResult) UnknownType("<lambda-result>")
        else if (expression.inferredReturn && expected != null) expected.returnType else resolve(expression.returnType)
        expression.captures = FreeVariables.of(expression).mapNotNull { name ->
            val type = previous.getVariable(name)
            if (type == null || type is DefType || type is ConstructorType || type is StructType && previous.getType(
                    name
                ) === type
            ) null
            else name to type
        }
        for ((name, type) in expression.captures) context.addVariable(name, type)
        val function = DefType("#lambda", parameters)
        function.returnType = returnType
        if (inferResult) inferredLambdaReturns[function] = arrayListOf()
        functionStack.push(function)
        currentContext = context
        try {
            expression.body.accept(this)
            if (inferResult) {
                val returns = inferredLambdaReturns.remove(function)!!
                if (!ControlFlow.exits(expression.body)) returns.add(UnitType)
                returnType = common(returns)
                function.returnType = returnType
                // Recheck with the joined result, so returned literals and nested constructors
                // receive the same contextual conversions as an annotated lambda.
                expression.body.accept(this)
            }
            if (!ControlFlow.exits(expression.body)) {
                if (SemanticTypes.of(returnType) != com.kingmang.ixion.types.PrimitiveType.UNIT) fail(
                    expression,
                    "lambda.missing_return"
                )
                expression.body.statements.add(
                    ReturnStatement(
                        expression.position,
                        UnitExpression(expression.position!!)
                    )
                )
            }
        } finally {
            inferredLambdaReturns.remove(function)
            currentContext = previous
            functionStack.pop()
        }
        return result(expression, LambdaType(parameters, returnType, TypeSyntax.interfaceFor(parameters.size)))
    }

    override fun visitEnumAccess(expression: EnumAccessExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * @param statement Return statement to type check
     * @return Empty optional as return statements don't produce values
     */
    override fun visitReturnStmt(statement: ReturnStatement): Optional<IxType> {
        if (functionStack.isEmpty()) fail(statement, "return.outside_function")
        val function = functionStack.peek()
        val inferred = inferredLambdaReturns[function]
        val actual = check(statement.expression!!, if (inferred == null) function.returnType else null)
        if (inferred != null) inferred.add(actual)
        else if (!typesMatch(function.returnType, actual)) ReturnTypeMismatchException().send(
            ixApi,
            file,
            statement,
            function.name,
            function.returnType.name,
            actual.name
        )
        function.hasReturn2 = true
        return Optional.empty()
    }

    /**
     * @param statement Struct statement to type check
     * @return Empty optional as struct definitions don't produce values
     */
    override fun visitStruct(statement: StructStatement): Optional<IxType> {
        return Optional.empty()
    }

    override fun visitTypeAlias(statement: TypeStatement): Optional<IxType> {
        return Optional.of(resolve(statement))
    }

    override fun visitUnionType(statement: UnionTypeStatement): Optional<IxType> {
        val union = UnionType.of(
            statement.types.stream()
                .map<IxType?> { type: TypeStatement? -> type!!.accept(this).orElseThrow() }
                .collect(Collectors.toSet())
        )
        return Optional.of(union)
    }

    /**
     * @param statement Variable declaration statement to type check
     * @return Empty optional as variable declarations don't produce values
     */
    override fun visitVariable(statement: VariableStatement): Optional<IxType> {
        val declared = statement.type.orElse(null)?.let(::resolve)
        val inferred = check(statement.expression, declared)
        if (declared != null && !typesMatch(declared, inferred)) {
            BadAssignmentException().send(ixApi, file, statement, statement.name.source, declared.name, inferred.name)
        }
        currentContext.setVariableType(statement.name.source, declared ?: inferred)
        return Optional.empty()
    }

    /**
     * @param statement While loop statement to type check
     * @return Empty optional as loops don't produce values
     */
    override fun visitWhile(statement: WhileStatement): Optional<IxType> {
        val childEnvironment = statement.block.context
        childEnvironment.parent = currentContext

        currentContext = childEnvironment
        if (check(statement.condition) !== BuiltInType.BOOLEAN) fail(statement.condition, "condition.expected_bool")

        statement.block.accept(this)

        this.popContext()
        return Optional.empty()
    }

    /**
     * Resolve property access chain types for struct types
     * @param expr Property access expression
     * @param typeChain Chain of types encountered during access
     * @param pointer Current struct type being accessed
     * @param result Current result type
     * @return Final resolved type after traversing the property chain
     */
    private fun getTempMSTType(
        expr: PropertyAccessExpression,
        typeChain: ArrayList<IxType?>,
        pointer: StructType,
        result: IxType?
    ): IxType? {
        var pointer = pointer
        var result = result
        for (identifier in expr.identifiers) {
            val foundField: Optional<Pair<kotlin.String, IxType>> = pointer.parameters.stream()
                .filter(Predicate { i: Pair<kotlin.String, IxType> -> i.first == identifier.identifier.source })
                .findAny()
            if (foundField.isPresent) {
                val pointerCandidate = foundField.get().second
                if (pointerCandidate is StructType) {
                    pointer = pointerCandidate
                    typeChain.add(pointer)
                    result = pointerCandidate
                } else {
                    result = pointerCandidate
                    typeChain.add(pointerCandidate)
                }
            } else {
                FieldNotPresentException().send(ixApi, file, identifier, identifier.identifier.source, pointer.name)
                break
            }
        }
        return result
    }

    private fun popContext() {
        this.currentContext = this.currentContext.parent!!
    }
}
