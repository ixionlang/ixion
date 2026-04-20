package com.kingmang.ixion.semantic

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.api.Context
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxApi.Companion.exit
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.api.IxionConstant.Mutability
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.exception.*
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.lexer.TokenType.Companion.isKeyword
import com.kingmang.ixion.modules.Modules.getExports
import com.kingmang.ixion.modules.Modules.modules
import com.kingmang.ixion.runtime.*
import com.kingmang.ixion.runtime.ixfunction.IxFunction0
import com.kingmang.ixion.runtime.ixfunction.IxFunction1
import com.kingmang.ixion.runtime.ixfunction.IxFunction2
import com.kingmang.ixion.runtime.ixfunction.IxFunction3
import com.kingmang.ixion.runtime.ixfunction.IxFunction4
import com.kingmang.ixion.typechecker.TypeUtils
import com.kingmang.ixion.typechecker.TypeUtils.getFromToken
import java.io.File
import java.util.*
import java.util.function.Supplier
import java.util.stream.Collectors

/**
 * Visitor for building the symbol table and type environment during compilation
 * Traverses the AST and registers variables, functions, types in appropriate scopes
 *
 *
 * @param ixApi The API instance for error reporting and system interactions
 * @param rootContext The root context/scope for the compilation unit
 * @param source The source file being processed
 */
class SemanticVisitor(val ixApi: IxApi, val rootContext: Context?, val source: IxFile) : Visitor<Optional<IxType>> {
    val file: File = source.file
    var currentContext: Context? = rootContext

    /**
     * Generic visit method that delegates to specific AST node handlers
     * @param statement The statement to visit
     * @return Optional containing the type if applicable, empty otherwise
     */
    override fun visit(statement: Statement): Optional<IxType> {
        return statement.accept(this)
    }

    /**
     * Visits a type alias declaration and registers it in the current context
     * @param statement The type alias statement
     * @return Empty optional as type aliases don't produce values
     */
    override fun visitTypeAlias(statement: TypeAliasStatement): Optional<IxType> {
        val type: Optional<IxType> = statement.typeStmt.accept(this)
        currentContext!!.addVariable(statement.identifier.source, type.orElseThrow())
        return Optional.empty()
    }

    /**
     * Visits an assignment expression and checks mutability constraints
     * @param expression The assignment expression
     * @return Empty optional as assignments don't produce types in environment phase
     */
    override fun visitAssignExpr(expression: AssignExpression): Optional<IxType> {
        expression.left.accept(this)
        expression.right.accept(this)

        if (expression.left is IdentifierExpression) {
            val mut = currentContext!!.getVariableMutability(expression.left.identifier.source)
            if (mut == Mutability.IMMUTABLE) {
                MutabilityException().send(ixApi, file, expression.left, expression.left.identifier.source)
            }
        } else if (expression.left is PropertyAccessExpression) {
            // Property assignment - handled in type checking phase
        } else {
            ImplementationException().send(
                ixApi,
                file,
                expression,
                "Assignment not implemented for any recipient but identifier yet"
            )
        }

        return Optional.empty()
    }

    /**
     * Visits a malformed expression node
     * @param expression The bad expression
     * @return Empty optional
     */
    override fun visitBad(expression: BadExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * Visits a binary expression and processes both operands
     * @param expression The binary expression
     * @return Empty optional as binary expressions are type-checked later
     */
    override fun visitBinaryExpr(expression: BinaryExpression): Optional<IxType> {
        expression.left.accept(this)
        expression.right.accept(this)
        return Optional.empty()
    }

    /**
     * Visits a block statement and processes all statements within it
     * Also checks for unreachable code after return statements
     * @param statement The block statement
     * @return Empty optional
     */
    override fun visitBlockStmt(statement: BlockStatement): Optional<IxType> {
        var returned = false
        for (stmt in statement.statements) {
            stmt!!.accept(this)
            if (!returned) {
                if (stmt is ReturnStatement) returned = true
            } else {
                UnreachableException().send(ixApi, file, stmt)
            }
        }
        return Optional.empty()
    }

    /**
     * Visits a function call expression and processes the callee and arguments
     * @param expression The call expression
     * @return Empty optional as function calls are type-checked later
     */
    override fun visitCall(expression: CallExpression): Optional<IxType> {
        expression.item.accept(this)
        for (arg in expression.arguments) {
            arg.accept(this)
        }
        return Optional.empty()
    }

    /**
     * Visits an empty expression
     * @param expression The empty expression
     * @return Empty optional
     */
    override fun visitEmpty(expression: EmptyExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * Visits an empty list expression and determines its type
     * @param expression The empty list expression
     * @return Optional containing the list type
     */
    override fun visitEmptyList(expression: EmptyListExpression): Optional<IxType> {
        val bt = TypeUtils.getFromString(expression.tokenType.source)
        val lt = ListType(bt!!)
        expression.realType = lt
        return Optional.of(lt)
    }

    /**
     * Visits an enum declaration
     * @param statement The enum statement
     * @return Empty optional as enums are processed differently
     */
    override fun visitEnum(statement: EnumStatement): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * Visits an export statement and processes the exported declaration
     * @param statement The export statement
     * @return Empty optional
     */
    override fun visitExport(statement: ExportStatement): Optional<IxType> {
        statement.stmt.accept(this)
        return Optional.empty()
    }

    /**
     * Visits an expression statement and processes its expression
     * @param statement The expression statement
     * @return The result of visiting the expression
     */
    override fun visitExpressionStmt(statement: ExpressionStatement): Optional<IxType> {
        return statement.expression.accept(this)
    }

    /**
     * Visits a for loop statement and sets up the loop variable scope
     * @param statement The for statement
     * @return Empty optional
     */
    override fun visitFor(statement: ForStatement): Optional<IxType> {
        val childEnvironment = statement.block.context
        childEnvironment.parent = currentContext

        currentContext = childEnvironment

        statement.expression.accept(this)

        currentContext!!.addVariable(statement.name.source, UnknownType())

        statement.block.accept(this)

        currentContext = currentContext!!.parent

        return Optional.empty()
    }

    /**
     * Visits a function declaration and registers it in the current context
     * Sets up the function's parameter scope and processes the function body
     * @param statement The function statement
     * @return Optional containing the function type definition
     */
    override fun visitFunctionStmt(statement: DefStatement): Optional<IxType> {
        val name = statement.name.source
        val generics = statement.generics!!.stream().map { it.source }.toList()

        val childEnvironment = statement.body!!.context
        childEnvironment.parent = currentContext

        // Annotate parameters in the current scope
        val parameters: MutableList<Pair<String, IxType>> = ArrayList()
        for (param in statement.parameters!!) {
            val pt: Optional<IxType> = param.type!!.accept(this)
            if (pt.isPresent) {
                val t = pt.get()
                if (t is UnknownType && generics.contains(t.typeName)) {
                    val gt = GenericType(t.typeName)
                    parameters.add(Pair(param.name.source, gt))
                    childEnvironment.addVariable(param.name.source, gt)
                } else {
                    childEnvironment.addVariable(param.name.source, t)
                    parameters.add(Pair(param.name.source, t))
                }
            } else {
                exit("pt not present", 783)
            }
        }

        val funcType = DefType(name, parameters, generics)
        if (statement.returnType != null) {
            val ttt: Optional<IxType> = statement.returnType.accept(this)
            funcType.returnType = ttt.get()
        }
        currentContext!!.addVariableOrError(ixApi, name, funcType, file, statement)

        currentContext = childEnvironment
        statement.body.accept(this)

        currentContext = currentContext!!.parent
        return Optional.of(funcType)
    }

    /**
     * Visits a grouping expression (parentheses) and processes the inner expression
     * @param expression The grouping expression
     * @return Empty optional
     */
    override fun visitGroupingExpr(expression: GroupingExpression): Optional<IxType> {
        expression.expression.accept(this)
        return Optional.empty()
    }

    /**
     * Visits an identifier expression and looks up the variable in the current context
     * @param expression The identifier expression
     * @return Optional containing the variable's type if found
     */
    override fun visitIdentifierExpr(expression: IdentifierExpression): Optional<IxType> {
        return Optional.ofNullable(currentContext!!.getVariable(expression.identifier.source))
    }

    /**
     * Visits an if statement and sets up the conditional branch scopes
     * @param statement The if statement
     * @return Empty optional
     */
    override fun visitIf(statement: IfStatement): Optional<IxType> {
        val childEnvironment = statement.trueBlock.context
        childEnvironment.parent = currentContext
        currentContext = childEnvironment
        statement.condition.accept(this)
        statement.trueBlock.accept(this)
        statement.falseStatement?.accept(this)

        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    /**
     * Visits a use/import statement and imports module exports into current context
     * @param statement The use statement
     * @return Empty optional
     */
    override fun visitUse(statement: UseStatement): Optional<IxType> {
        val requestedImport = statement.stringLiteral.source
        if (modules.containsKey(requestedImport)) {
            val ree = getExports(requestedImport)
            for (ft in ree) {
                val typeName = ft!!.name
                this.currentContext!!.addVariableOrError(ixApi, "$requestedImport::$typeName", ft, file, statement)
            }
        }

        return Optional.empty()
    }

    /**
     * Visits an index access expression (array/list indexing)
     * @param expression The index access expression
     * @return Empty optional (handled in type checking phase)
     */
    override fun visitIndexAccess(expression: IndexAccessExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * Visits a literal expression and determines its built-in type
     * @param expression The literal expression
     * @return Optional containing the literal's type
     */
    override fun visitLiteralExpr(expression: LiteralExpression): Optional<IxType> {
        val t = getFromToken(expression.literal.type)
        expression.realType = t
        return Optional.ofNullable(t)
    }

    /**
     * Visits a list literal expression and processes all entries
     * @param expression The list literal expression
     * @return Empty optional
     */
    override fun visitLiteralList(expression: LiteralListExpression): Optional<IxType> {
        if (expression.entries.isEmpty()) {
            ListLiteralIncompleteException().send(ixApi, file, expression)
        }

        for (entry in expression.entries) {
            entry.accept(this)
        }
        return Optional.empty()
    }

    /**
     * Visits a match statement and sets up case pattern matching scopes
     * @param statement The match statement
     * @return Empty optional
     */
    override fun visitMatch(statement: CaseStatement): Optional<IxType> {
        statement.expression.accept(this)
        statement.cases.forEach { (keyType: TypeStatement?, pair: Pair<String, BlockStatement>) ->
            val id: String = pair.first
            val block: BlockStatement = pair.second
            keyType!!.accept(this)

            // create environment for case
            val pt: Optional<IxType> = keyType.accept(this)
            if (pt.isPresent) {
                val t = pt.get()
                statement.types[keyType] = t
            } else {
                exit("pt not present", 783)
            }
            val childEnvironment = block.context
            childEnvironment.parent = currentContext
            childEnvironment.addVariable(id, statement.types[keyType])
            currentContext = childEnvironment
            block.accept(this)
            currentContext = currentContext!!.parent
        }

        return Optional.empty()
    }

    /**
     * Visits a module access expression (qualified name access)
     * @param expression The module access expression
     * @return Empty optional
     */
    override fun visitModuleAccess(expression: ModuleAccessExpression): Optional<IxType> {
        expression.foreign.accept(this)
        return Optional.empty()
    }

    /**
     * Visits a parameter statement (function parameter declaration)
     * @param statement The parameter statement
     * @return Empty optional
     */
    override fun visitParameterStmt(statement: ParameterStatement): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * Visits a postfix expression (increment/decrement operators)
     * @param expression The postfix expression
     * @return Empty optional
     */
    override fun visitPostfixExpr(expression: PostfixExpression): Optional<IxType> {
        expression.expression.accept(this)
        return Optional.empty()
    }

    /**
     * Visits a prefix expression (unary operators)
     * @param expression The prefix expression
     * @return Empty optional
     */
    override fun visitPrefix(expression: PrefixExpression): Optional<IxType> {
        expression.right.accept(this)
        return Optional.empty()
    }

    /**
     * Visits a property access expression (dot notation)
     * @param expression The property access expression
     * @return Empty optional
     */
    override fun visitPropertyAccess(expression: PropertyAccessExpression): Optional<IxType> {
        expression.expression.accept(this)
        return Optional.empty()
    }

    override fun visitLambda(expression: LambdaExpression): Optional<IxType> {
        val childEnvironment = expression.body.context
        childEnvironment.parent = currentContext

        val parameters = ArrayList<Pair<String, IxType>>()
        for (param in expression.parameters) {
            val resolved = if (param.type != null) {
                param.type.accept(this).orElseGet(Supplier { UnknownType() })
            } else {
                UnknownType()
            }
            parameters.add(Pair(param.name.source, resolved))
            childEnvironment.addVariable(param.name.source, resolved)
        }

        val resolvedReturnType = expression.returnType.accept(this).orElseGet(Supplier { UnknownType() })
        val lambdaType = LambdaType(parameters, resolvedReturnType, functionalInterfaceByArity(expression.parameters.size, expression))
        expression.realType = lambdaType

        currentContext = childEnvironment
        expression.body.accept(this)
        currentContext = currentContext!!.parent

        return Optional.of(lambdaType)
    }

    override fun visitEnumAccess(expression: EnumAccessExpression): Optional<IxType> {
        return Optional.empty()
    }

    /**
     * Visits a return statement and processes the return expression
     * @param statement The return statement
     * @return Empty optional
     */
    override fun visitReturnStmt(statement: ReturnStatement): Optional<IxType> {
        statement.expression!!.accept(this)
        return Optional.empty()
    }

    /**
     * Visits a struct declaration and registers the struct type in the current context
     * Processes all struct fields and handles generics
     * @param statement The struct statement
     * @return Optional containing the struct type definition
     */
    override fun visitStruct(statement: StructStatement): Optional<IxType> {
        val fieldNames = arrayOfNulls<String>(statement.fields.size)
        val fieldTypes = arrayOfNulls<IxType>(statement.fields.size)

        val parameters: MutableList<Pair<String, IxType>> = ArrayList()
        for (i in fieldNames.indices) {
            val field = statement.fields[i]
            fieldNames[i] = field.name.source
            if (isKeyword(fieldNames[i])) {
                ReservedWordException().send(ixApi, file, statement.fields[i], fieldNames[i])
            }
            val fieldT: Optional<IxType> = field.type!!.accept(this)
            if (fieldT.isPresent) {
                fieldTypes[i] = fieldT.get()
                parameters.add(Pair(fieldNames[i]!!, fieldTypes[i]!!))
            } else {
                exit("fieldT not present", 429)
            }
        }
        val name = statement.name.source
        val generics = statement.generics!!.stream().map { it.source }.toList()

        val structType = StructType(name, parameters, generics)
        structType.qualifiedName = source.fullRelativePath + "$" + name
        structType.parentName = source.fullRelativePath
        currentContext!!.addVariableOrError(ixApi, name, structType, file, statement)

        return Optional.of(structType)
    }

    /**
     * Visits a type statement and resolves the type reference
     * Handles both built-in types, custom types, and list types
     * @param statement The type statement
     * @return Optional containing the resolved type
     */
    override fun visitTypeAlias(statement: TypeStatement): Optional<IxType> {
        var type: IxType?
        if (statement.next.isEmpty) {
            val bt = TypeUtils.getFromString(statement.identifier!!.source)
            type = Objects.requireNonNullElseGet(bt, Supplier { UnknownType(statement.identifier.source) })
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
                currentContext!!.getVariableTyped<StructType>(path.toString()),
                UnknownType(path.toString())
            )
        }
        return Optional.of(type!!)
    }

    /**
     * Visits a union type declaration and creates the union type
     * @param statement The union type statement
     * @return Optional containing the union type definition
     */
    override fun visitUnionType(statement: UnionTypeStatement): Optional<IxType> {
        val union = UnionType(
            statement.types.stream()
                .map<IxType?> { type: TypeStatement? -> type!!.accept(this).orElseThrow() }
                .collect(Collectors.toSet())
        )
        return Optional.of(union)
    }

    /**
     * Visits a variable declaration and registers it in the current context
     * Handles mutability and type inference from initializer expressions
     * @param statement The variable statement
     * @return Empty optional
     */
    override fun visitVariable(statement: VariableStatement): Optional<IxType> {
        val t: Optional<IxType> = statement.expression.accept(this)
        val type: IxType? = t.orElseGet(Supplier { UnknownType() })

        var mut = Mutability.IMMUTABLE
        if (statement.mutability.type == TokenType.VARIABLE) {
            mut = Mutability.MUTABLE
        }

        currentContext!!.addVariableOrError(ixApi, statement.name.source, type, file, statement)
        currentContext!!.setVariableMutability(statement.name.source, mut)
        return Optional.empty()
    }

    /**
     * Visits a while loop statement and sets up the loop scope
     * @param statement The while statement
     * @return Empty optional
     */
    override fun visitWhile(statement: WhileStatement): Optional<IxType> {
        val childEnvironment = statement.block.context
        childEnvironment.parent = currentContext

        currentContext = childEnvironment
        statement.condition.accept(this)

        statement.block.accept(this)

        currentContext = currentContext!!.parent
        return Optional.empty()
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
}
