package com.kingmang.ixion.typechecker

import com.kingmang.ixion.Visitor
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
class TypeCheckVisitor(private val ixApi: IxApi, private val rootContext: Context, ixFile: IxFile) : Visitor<Optional<IxType>> {
    private val file: File = ixFile.file
    private val functionStack = Stack<DefType>()
    private var currentContext: Context

    /**
     * @param ixApi The API instance for error reporting
     * @param rootContext The root context for variable resolution
     * @param ixFile The source file being type checked
     */
    init {
        this.currentContext = this.rootContext
    }

    override fun visit(statement: Statement): Optional<IxType> {
        return statement.accept(this)
    }

    /**
     * @param statement Type alias statement to process
     * @return Empty optional as type aliases don't produce values
     */
    override fun visitTypeAlias(statement: TypeAliasStatement): Optional<IxType> {
        val a = currentContext.getVariable(statement.identifier.source)

        val resolvedTypes = HashSet<IxType?>()
        if (a is UnionType) {
            extractedMethodForUnions(resolvedTypes, a, statement)

            currentContext.setVariableType(statement.identifier(), a)
        }

        return Optional.empty()
    }

    /**
     * @param expression Assignment expression to type check
     * @return Empty optional as assignments don't produce values
     */
    override fun visitAssignExpr(expression: AssignExpression): Optional<IxType> {
        expression.left.accept(this)
        expression.right.accept(this)

        when (expression.left) {
            is IdentifierExpression -> {
                if (expression.left.realType !== expression.right.realType) {
                    BadAssignmentException().send(ixApi, file, expression, expression.left.identifier.source)
                }
            }

            is PropertyAccessExpression -> {
                val lType = expression.left.realType
                val rType = expression.right.realType
                if (!typesMatch(lType, rType)) {
                    ParameterTypeMismatchException().send(ixApi, file, expression.left, rType.name)
                }
            }

            else -> throw IllegalStateException("Unexpected value: " + expression.left.realType)
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
        val t1: Optional<IxType> = expression.left.accept(this)
        val t2: Optional<IxType> = expression.right.accept(this)

        if (t1.isEmpty || t2.isEmpty) {
            ImplementationException().send(ixApi, file, expression, "Types in binary expression not determined.")
            return Optional.empty()
        }

        if (t1.get() === BuiltInType.ANY || t2.get() === BuiltInType.ANY) {
            CannotApplyOperatorException().send(ixApi, file, expression, expression.operator.source)
            return Optional.empty()
        }

        var totalType: IxType? = t1.get()

        when (expression.operator.type) {
            TokenType.ADD, TokenType.SUB, TokenType.MUL, TokenType.DIV, TokenType.MOD -> {
                if (t1.get() is BuiltInType && t2.get() is BuiltInType) {
                    totalType = widen(t1.get() as BuiltInType, t2.get() as BuiltInType)
                } else {
                    CannotApplyOperatorException().send(ixApi, file, expression, expression.operator.source)
                }
            }

            TokenType.EQUAL, TokenType.NOTEQUAL, TokenType.LT, TokenType.GT, TokenType.LE, TokenType.GE -> {
                if (t1.get() is BuiltInType && t2.get() is BuiltInType) {
                    if (expression.operator.type != TokenType.EQUAL && expression.operator.type != TokenType.NOTEQUAL) {
                        val bt1 = t1.get() as BuiltInType
                        val bt2 = t2.get() as BuiltInType
                        if (bt1 == BuiltInType.STRING || bt2 == BuiltInType.STRING) {
                            CannotApplyOperatorException().send(ixApi, file, expression, expression.operator.source)
                        } else if (bt1 == BuiltInType.BOOLEAN || bt2 == BuiltInType.BOOLEAN) {
                            CannotApplyOperatorException().send(ixApi, file, expression, expression.operator.source)
                        }
                    }
                    totalType = BuiltInType.BOOLEAN
                }
            }

            TokenType.AND, TokenType.OR, TokenType.XOR -> {
                if (t1.get() is BuiltInType && t2.get() is BuiltInType) {
                    if (t1.get() != BuiltInType.BOOLEAN || t2.get() != BuiltInType.BOOLEAN) {
                        CannotApplyOperatorException().send(ixApi, file, expression, expression.operator.source)
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
        for (stmt in statement.statements) {
            stmt!!.accept(this)
        }
        return Optional.empty()
    }

    /**
     * @param expression Function call expression to type check
     * @return Optional containing the return type of the function call
     */
    override fun visitCall(expression: CallExpression): Optional<IxType> {
        val e: Optional<IxType> = expression.item.accept(this)
        if (e.isEmpty) exit(
            ("Type checking failed to resolve function in ["
                    + expression.position!!.line + ":" + expression.position.col
                    + "]"), 95
        )

        val t: IxType = e.orElseThrow()!!
        if (t is StructType) {
            if (t.parameters.size != expression.arguments.size) {
                FunctionSignatureMismatchException().send(ixApi, file, expression.item, t.name)
                return Optional.empty()
            }
            updateUnknownParameters(expression, t)

            zip(
                t.parameters,
                expression.arguments,
                BiConsumer { param: Pair<kotlin.String, IxType>, arg: Expression ->
                    val at: Optional<IxType> = arg.accept(this)
                    at.ifPresent(Consumer { type: IxType? -> typecheckCallParameters(param, arg, type!!) })
                })
        }

        when (t) {
            is LambdaType -> {
                if (t.parameters.size != expression.arguments.size) {
                    FunctionSignatureMismatchException().send(ixApi, file, expression.item, "lambda")
                    return Optional.empty()
                }

                zip(
                    t.parameters,
                    expression.arguments,
                    BiConsumer { param: Pair<kotlin.String, IxType>, arg: Expression ->
                        val at: Optional<IxType> = arg.accept(this)
                        at.ifPresent(Consumer { type: IxType? -> typecheckCallParameters(param, arg, type!!) })
                    })

                expression.realType = t.returnType
                return Optional.of(t.returnType)
            }

            is DefType -> {
                var rt = t.returnType
                if (t.hasGenerics()) {
                    val specialization = t.buildSpecialization(expression.arguments)

                    t.specializations.add(specialization)

                    if (rt is GenericType) {
                        rt = specialization[rt.key]!!
                    }
                }

                expression.realType = rt

                return Optional.of(rt)
            }

            is StructType -> {
                expression.realType = t
                return Optional.of(t)
            }

            else -> {
                MethodNotFoundException().send(ixApi, file, expression.item, String.valueOf(expression.item))
            }
        }

        return Optional.empty()
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
        return statement.expression.accept(this)
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
                is ExternalType -> {
                    if ((b.get() as ExternalType).foundClass!!.getName() == "java.util.Iterator") {
                        currentContext.setVariableType(statement.name.source, BuiltInType.INT)
                    }
                }

                is ListType-> {
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
        val funcType = currentContext.getVariableTyped<DefType>(statement.name.source)
        if (funcType != null) {
            functionStack.add(funcType)
            val childEnvironment = statement.body!!.context

            val parametersBefore: MutableList<Pair<kotlin.String, IxType>> = funcType.parameters
            val parametersAfter = ArrayList<Pair<kotlin.String, IxType>>()

            for (param in parametersBefore) {
                when (param.second) {
                    is UnknownType -> {
                        val attempt = currentContext.getVariable((param.second as UnknownType).typeName)
                        if (attempt != null) {
                            childEnvironment.setVariableType(param.first, attempt)
                            val nt = Pair(param.first, attempt)
                            parametersAfter.add(nt)
                        } else {
                            IdentifierNotFoundException().send(ixApi, file, statement, (param.second as UnknownType).typeName)
                            parametersAfter.add(param)
                        }
                    }

                    is UnionType -> {
                        parametersAfter.add(param)
                        val resolvedTypes = HashSet<IxType?>()
                        extractedMethodForUnions(resolvedTypes, param.second as UnionType, statement)

                        currentContext.setVariableType(param.first, param.second)
                    }

                    else -> {
                        parametersAfter.add(param)
                    }
                }
            }
            funcType.parameters.clear()
            funcType.parameters.addAll(parametersAfter)

            if (funcType.returnType is UnknownType) {
                val attempt = currentContext.getVariable((funcType.returnType as UnknownType).typeName)
                if (attempt != null) {
                    funcType.returnType = attempt
                } else {
                    IdentifierNotFoundException().send(ixApi, file, statement, (funcType.returnType as UnknownType).typeName)
                }
            }

            currentContext = childEnvironment

            statement.body.accept(this)

            if (!funcType.hasReturn2) {
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
        return expression.expression.accept(this)
    }

    /**
     * @param expression Identifier expression to resolve
     * @return Optional containing the type of the identifier
     */
    override fun visitIdentifierExpr(expression: IdentifierExpression): Optional<IxType> {
        var t = currentContext.getVariable(expression.identifier.source)
        if (t != null) {
            if (t is UnknownType) {
                val attempt = currentContext.getVariable(t.typeName)
                if (attempt != null) {
                    t = attempt
                }
            }
            expression.realType = t
        } else {
            IdentifierNotFoundException().send(ixApi, file, expression, expression.identifier.source)
        }
        return Optional.ofNullable(t)
    }

    /**
     * @param statement If statement to type check
     * @return Empty optional as if statements don't produce values
     */
    override fun visitIf(statement: IfStatement): Optional<IxType> {
        currentContext = statement.trueBlock.context
        statement.condition.accept(this)
        statement.trueBlock.accept(this)
        statement.falseStatement?.accept(this)

        this.popContext()
        return Optional.empty()
    }

    override fun visitUse(statement: UseStatement): Optional<IxType> {
        return Optional.empty()
    }

    override fun visitIndexAccess(expression: IndexAccessExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * @param expression Literal expression to type check
     * @return Optional containing the type of the literal
     */
    override fun visitLiteralExpr(expression: LiteralExpression): Optional<IxType> {
        return Optional.ofNullable(expression.realType)
    }

    /**
     * @param expression List literal expression to type check
     * @return Optional containing the type of the list
     */
    override fun visitLiteralList(expression: LiteralListExpression): Optional<IxType> {
        val firstType: Optional<IxType> = expression.entries[0].accept(this)

        firstType.ifPresent(Consumer { type: IxType? ->
            expression.realType = ListType(type!!)
            for (i in expression.entries.indices) {
                val t: Optional<IxType> = expression.entries[i].accept(this)
                if (t.isPresent) {
                    if (t.get() != type) {
                        ListTypeException().send(ixApi, file, expression.entries[i], t.get().name)
                        break
                    }
                }
            }
        })

        return Optional.of(expression.realType)
    }

    /**
     * @param statement Match statement to type check
     * @return Empty optional as match statements don't produce values
     */
    override fun visitMatch(statement: CaseStatement): Optional<IxType> {
        statement.expression.accept(this)

        if (statement.expression.realType is UnionType) {
            val typesToCover: HashSet<IxType> = HashSet<IxType>((statement.expression.realType as UnionType).types)
            statement.cases.forEach(BiConsumer { keyTypeStmt: TypeStatement?, pair: Pair<kotlin.String, BlockStatement> ->
                val id: kotlin.String = pair.first
                val block: BlockStatement = pair.second
                var caseType = statement.types[keyTypeStmt]
                if (caseType is UnknownType) {
                    val attempt = currentContext.getVariable(caseType.typeName)
                    if (attempt != null) {
                        caseType = attempt
                    }
                }

                typesToCover.remove(caseType)

                val childEnvironment = block.context
                childEnvironment.parent = currentContext
                childEnvironment.setVariableType(id, caseType)

                currentContext = childEnvironment
                block.accept(this)
                this.popContext()
            })
            if (!typesToCover.isEmpty()) {
                MatchCoverageException().send(ixApi, file, statement, String.valueOf((statement.expression.realType as UnionType)))
            }
        } else {
            TypeNotResolvedException().send(ixApi, file, statement.expression, "")
        }

        return Optional.empty()
    }

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

        if (!(expression.realType is BuiltInType && expression.realType.isNumeric)) {
            CannotPostfixException().send(ixApi, file, expression.expression, expression.operator.source)
        }
        return Optional.empty()
    }

    /**
     * @param expression Prefix expression to type check
     * @return Optional containing the type of the prefixed expression
     */
    override fun visitPrefix(expression: PrefixExpression): Optional<IxType> {
        return expression.right.accept(this)
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
            expression.realType = result!!
        } else {
            MethodNotFoundException().send(ixApi, file, expression.expression, "ree")
        }
        expression.typeChain = typeChain

        return Optional.ofNullable(expression.realType)
    }

    override fun visitLambda(expression: LambdaExpression): Optional<IxType> {
        val childEnvironment = expression.body.context
        childEnvironment.parent = currentContext

        val parameters = ArrayList<Pair<kotlin.String, IxType>>()
        for (param in expression.parameters) {
            val rawType = if (param.type != null) {
                param.type.accept(this).orElseGet { UnknownType() }
            } else {
                UnknownType()
            }
            val resolvedType = if (rawType is UnknownType) {
                currentContext.getVariable(rawType.typeName) ?: rawType
            } else {
                rawType
            }
            parameters.add(Pair(param.name.source!!, resolvedType))
            childEnvironment.setVariableType(param.name.source, resolvedType)
        }

        var returnType = expression.returnType.accept(this).orElseGet { UnknownType() }
        if (returnType is UnknownType) {
            val attempt = currentContext.getVariable(returnType.typeName)
            if (attempt != null) {
                returnType = attempt
            } else {
                IdentifierNotFoundException().send(ixApi, file, expression, returnType.typeName)
            }
        }

        val lambdaType = LambdaType(parameters, returnType, functionalInterfaceByArity(parameters.size, expression))
        expression.realType = lambdaType

        val lambdaFunction = DefType("#lambda", parameters)
        lambdaFunction.returnType = returnType
        functionStack.add(lambdaFunction)

        currentContext = childEnvironment
        expression.body.accept(this)
        this.popContext()

        if (!lambdaFunction.hasReturn2) {
            val returnStmt = ReturnStatement(
                Position(0, 0),
                EmptyExpression(Position(0, 0))
            )
            expression.body.statements.add(returnStmt)
        }
        functionStack.pop()

        return Optional.of(lambdaType)
    }

    override fun visitEnumAccess(expression: EnumAccessExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * @param statement Return statement to type check
     * @return Empty optional as return statements don't produce values
     */
    override fun visitReturnStmt(statement: ReturnStatement): Optional<IxType> {
        val t: Optional<IxType> = statement.expression!!.accept(this)

        if (t.isPresent) {
            if (!functionStack.isEmpty()) {
                val newType = t.get()
                val functionType = functionStack.peek()

                when {
                    statement.expression is EmptyListExpression && functionType.returnType is ListType -> {
                        functionType.hasReturn2 = true
                        return Optional.empty()
                    }
                    typesMatch(functionType.returnType, newType) -> {}
                    functionType.returnType is UnionType -> {
                        if (!(functionType.returnType as UnionType).types.contains(newType)) {
                            ParameterTypeMismatchException().send(
                                ixApi,
                                file,
                                statement.expression,
                                String.valueOf(newType)
                            )
                        }
                    }
                    functionType.returnType === BuiltInType.VOID -> {
                        if (newType !== BuiltInType.VOID) {
                            ReturnTypeMismatchException().send(ixApi, file, statement, functionType.name)
                        }
                    }
                    else -> {
                        ReturnTypeMismatchException().send(ixApi, file, statement, functionType.name)
                    }
                }
            }
        }

        functionStack.peek().hasReturn2 = true

        return Optional.empty()
    }

    /**
     * @param statement Struct statement to type check
     * @return Empty optional as struct definitions don't produce values
     */
    override fun visitStruct(statement: StructStatement): Optional<IxType> {
        val structType = currentContext.getVariableTyped<StructType>(statement.name.source)
        if (structType != null) {
            val parametersAfter = ArrayList<Pair<kotlin.String, IxType>>()
            zip(
                statement.fields,
                structType.parameters,
                BiConsumer { a: ParameterStatement?, b: Pair<kotlin.String, IxType>? ->
                    val bType = b!!.second
                    if (bType is UnknownType) {
                        val attempt = currentContext.getVariable(bType.typeName)
                        if (attempt != null) {
                            parametersAfter.add(Pair(b.first, attempt))
                        } else if (structType.generics.contains(bType.typeName)) {
                            parametersAfter.add(Pair(b.first, GenericType(bType.typeName)))
                        } else {
                            IdentifierNotFoundException().send(ixApi, file, a!!, bType.typeName)
                            parametersAfter.add(b)
                        }
                    } else {
                        parametersAfter.add(b)
                    }
                })
            structType.parameters.clear()
            structType.parameters.addAll(parametersAfter)
        }

        return Optional.empty()
    }

    override fun visitTypeAlias(statement: TypeStatement): Optional<IxType> {
        var type: IxType?
        if (statement.next.isEmpty) {
            val bt = TypeUtils.getFromString(statement.identifier!!.source!!)
            type = Objects.requireNonNullElseGet(bt) { UnknownType(statement.identifier.source) }
            if (statement.listType) {
                type = ListType(type!!)
            }
        } else {
            val path = StringBuilder(statement.identifier!!.source)
            var ptr: Optional<TypeStatement> = statement.next
            while (ptr.isPresent) {
                path.append(".").append(ptr.get().identifier!!.source)
                ptr = ptr.get().next
            }
            type = Objects.requireNonNullElse(
                currentContext.getVariableTyped<StructType>(path.toString()),
                UnknownType(path.toString())
            )
        }
        return Optional.of(type!!)
    }

    override fun visitUnionType(statement: UnionTypeStatement): Optional<IxType> {
        val union = UnionType(
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
        val expr = statement.expression
        val t: Optional<IxType> = expr.accept(this)

        if (t.isPresent) {
            currentContext.setVariableType(statement.name.source, t.get())
        } else {
            TypeNotResolvedException().send(ixApi, file, expr, statement.name.source)
        }
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
        statement.condition.accept(this)

        statement.block.accept(this)

        this.popContext()
        return Optional.empty()
    }

    /**
     * Resolve unknown types within a union type
     * @param resolvedTypes Set to store resolved types
     * @param ut Union type containing potentially unknown types
     * @param node AST node for error reporting
     */
    private fun extractedMethodForUnions(resolvedTypes: HashSet<IxType?>, ut: UnionType, node: Statement) {
        for (type in ut.types) {
            if (type is UnknownType) {
                val attempt = currentContext.getVariable(type.typeName)
                if (attempt != null) {
                    resolvedTypes.add(attempt)
                } else {
                    IdentifierNotFoundException().send(ixApi, file, node, type.typeName)
                }
            } else {
                resolvedTypes.add(type)
            }
        }
        ut.types = resolvedTypes
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
                FieldNotPresentException().send(ixApi, file, identifier, identifier.identifier.source)
                break
            }
        }
        return result
    }

    /**
     * Validate that function call arguments match parameter types
     * @param param Function parameter (name, type)
     * @param arg Argument expression
     * @param argType Resolved type of the argument
     */
    private fun typecheckCallParameters(param: Pair<kotlin.String, IxType>, arg: Expression, argType: IxType) {
        if (argType === BuiltInType.VOID) {
            VoidUsageException().send(ixApi, file, arg)
        }
        if (!typesMatch(param.second, argType)) {
            ParameterTypeMismatchException().send(ixApi, file, arg, argType.name)
            typesMatch(param.second, argType)
            arg.accept(this)
        } else {
            arg.realType = argType
        }
    }

    /**
     * Update unknown types in function/struct parameters with resolved types
     * @param expr Function call expression
     * @param structType Struct or function type being called
     */
    private fun updateUnknownParameters(expr: CallExpression, structType: StructType) {
        val parametersAfter = ArrayList<Pair<kotlin.String, IxType>>()
        zip(
            structType.parameters,
            expr.arguments,
            BiConsumer { param: Pair<kotlin.String, IxType>, arg: Expression? ->
                if (param.second is UnknownType) {
                    val attempt = currentContext.getVariable((param.second as UnknownType).typeName)
                    if (attempt != null) {
                        parametersAfter.add(Pair(param.first, attempt))
                    } else {
                        IdentifierNotFoundException().send(ixApi, file, arg!!, (param.second as UnknownType).typeName)
                        parametersAfter.add(param)
                    }
                } else {
                    parametersAfter.add(param)
                }
            })

        structType.parameters.clear()
        structType.parameters.addAll(parametersAfter)
    }

    private fun functionalInterfaceByArity(arity: Int, expression: LambdaExpression): Class<*> {
        return when (arity) {
            0 -> IxFunction0::class.java
            1 -> IxFunction1::class.java
            2 -> IxFunction2::class.java
            3 -> IxFunction3::class.java
            4 -> IxFunction4::class.java
            else -> {
                ImplementationException().send(ixApi, file, expression, "Lambdas support up to 4 parameters.")
                IxFunction4::class.java
            }
        }
    }

    private fun popContext() {
        this.currentContext = this.currentContext.parent!!
    }
}
