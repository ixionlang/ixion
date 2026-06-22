package com.kingmang.ixion.optimizer

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.BuiltInType

class DeadCodeEliminationVisitor : Visitor<Any> {

    fun optimize(file: IxFile) {
        for (i in file.statements.indices) {
            file.statements[i] = rewriteStatement(file.statements[i])
        }
    }

    private fun rewriteStatement(statement: Statement): Statement {
        return statement.accept(this) as Statement
    }

    private fun rewriteExpression(expression: Expression): Expression {
        return expression.accept(this) as Expression
    }

    override fun visit(statement: Statement): Any = rewriteStatement(statement)

    override fun visitBlockStmt(statement: BlockStatement): Any {
        val rewritten = mutableListOf<Statement?>()
        var terminated = false
        for (stmt in statement.statements) {
            if (stmt == null || terminated) continue
            val rewrittenStmt = rewriteStatement(stmt)
            if (isTerminatingStatement(rewrittenStmt)) {
                terminated = true
            }
            rewritten.add(rewrittenStmt)
        }
        return BlockStatement(statement.position, rewritten, statement.context)
    }

    override fun visitIf(statement: IfStatement): Any {
        val condition = rewriteExpression(statement.condition)

        if (condition is LiteralExpression && condition.realType == BuiltInType.BOOLEAN) {
            return when (condition.literal.type) {
                TokenType.TRUE -> rewriteStatement(statement.trueBlock)
                TokenType.FALSE -> {
                    if (statement.falseStatement != null) {
                        rewriteStatement(statement.falseStatement)
                    } else {
                        BlockStatement(statement.position, mutableListOf(), statement.trueBlock.context)
                    }
                }
                else -> IfStatement(
                    statement.position,
                    condition,
                    asBlockStatement(rewriteStatement(statement.trueBlock)),
                    statement.falseStatement?.let { rewriteStatement(it) }
                )
            }
        }

        return IfStatement(
            statement.position,
            condition,
            asBlockStatement(rewriteStatement(statement.trueBlock)),
            statement.falseStatement?.let { rewriteStatement(it) }
        )
    }

    override fun visitWhile(statement: WhileStatement): Any {
        val condition = rewriteExpression(statement.condition)

        if (condition is LiteralExpression && condition.realType == BuiltInType.BOOLEAN
            && condition.literal.type == TokenType.FALSE) {
            return BlockStatement(statement.position, mutableListOf(), statement.block.context)
        }

        return WhileStatement(
            statement.position,
            condition,
            asBlockStatement(rewriteStatement(statement.block))
        )
    }

    override fun visitFor(statement: ForStatement): Any {
        val expression = rewriteExpression(statement.expression)

        if ((expression is LiteralListExpression && expression.entries.isEmpty())
            || expression is EmptyListExpression) {
            return BlockStatement(statement.position, mutableListOf(), statement.block.context)
        }

        return ForStatement(
            statement.position,
            statement.name,
            expression,
            asBlockStatement(rewriteStatement(statement.block))
        )
    }

    override fun visitFunctionStmt(statement: DefStatement): Any {
        return DefStatement(
            statement.position,
            statement.name,
            statement.parameters,
            statement.returnType,
            statement.body?.let { asBlockStatement(rewriteStatement(it)) },
            statement.generics
        )
    }

    override fun visitVariable(statement: VariableStatement): Any {
        return VariableStatement(
            statement.position,
            statement.mutability,
            statement.name,
            rewriteExpression(statement.expression),
            statement.type
        )
    }

    override fun visitExpressionStmt(statement: ExpressionStatement): Any {
        return ExpressionStatement(statement.position, rewriteExpression(statement.expression))
    }

    override fun visitReturnStmt(statement: ReturnStatement): Any {
        return ReturnStatement(
            statement.position,
            statement.expression?.let { rewriteExpression(it) }
        )
    }

    override fun visitExport(statement: ExportStatement): Any {
        return ExportStatement(statement.position, rewriteStatement(statement.stmt))
    }

    override fun visitMatch(statement: CaseStatement): Any {
        val rewrittenCases = LinkedHashMap<TypeStatement, Pair<String, BlockStatement>>()
        for ((typeStmt, pair) in statement.cases) {
            rewrittenCases[typeStmt] = Pair(pair.first, asBlockStatement(rewriteStatement(pair.second)))
        }
        val rewritten = CaseStatement(
            statement.position,
            rewriteExpression(statement.expression),
            rewrittenCases
        )
        rewritten.types.putAll(statement.types)
        return rewritten
    }

    override fun visitLambda(expression: LambdaExpression): Any {
        val rewritten = LambdaExpression(
            expression.position,
            expression.parameters,
            expression.returnType,
            asBlockStatement(rewriteStatement(expression.body))
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitBinaryExpr(expression: BinaryExpression): Any {
        val rewritten = BinaryExpression(
            expression.position,
            rewriteExpression(expression.left),
            expression.operator,
            rewriteExpression(expression.right)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitPrefix(expression: PrefixExpression): Any {
        val rewritten = PrefixExpression(
            expression.position,
            expression.operator,
            rewriteExpression(expression.right)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitPostfixExpr(expression: PostfixExpression): Any {
        val rewritten = PostfixExpression(
            expression.position,
            rewriteExpression(expression.expression),
            expression.operator
        )
        rewritten.localIndex = expression.localIndex
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitAssignExpr(expression: AssignExpression): Any {
        val rewritten = AssignExpression(
            expression.position,
            rewriteExpression(expression.left),
            rewriteExpression(expression.right)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitCall(expression: CallExpression): Any {
        val item = rewriteExpression(expression.item)
        val args = expression.arguments.mapTo(mutableListOf()) { rewriteExpression(it) }
        val rewritten = CallExpression(expression.position, item, args)
        rewritten.foreign = expression.foreign
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitLiteralExpr(expression: LiteralExpression): Any = expression

    override fun visitIdentifierExpr(expression: IdentifierExpression): Any = expression

    override fun visitGroupingExpr(expression: GroupingExpression): Any {
        val rewritten = rewriteExpression(expression.expression)
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitModuleAccess(expression: ModuleAccessExpression): Any {
        val rewritten = ModuleAccessExpression(
            expression.position,
            asIdentifierExpression(rewriteExpression(expression.identifier)),
            rewriteExpression(expression.foreign)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitPropertyAccess(expression: PropertyAccessExpression): Any {
        val rewritten = PropertyAccessExpression(
            expression.position,
            rewriteExpression(expression.expression),
            expression.identifiers
        )
        rewritten.typeChain = expression.typeChain
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitIndexAccess(expression: IndexAccessExpression): Any {
        val rewritten = IndexAccessExpression(
            expression.position,
            rewriteExpression(expression.left),
            rewriteExpression(expression.right)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitLiteralList(expression: LiteralListExpression): Any {
        val rewritten = LiteralListExpression(
            expression.position,
            expression.entries.mapTo(mutableListOf()) { rewriteExpression(it) }
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitEmpty(expression: EmptyExpression): Any = expression

    override fun visitEmptyList(expression: EmptyListExpression): Any = expression

    override fun visitBad(expression: BadExpression): Any = expression

    override fun visitEnumAccess(expression: EnumAccessExpression): Any {
        val rewritten = EnumAccessExpression(
            expression.position,
            rewriteExpression(expression.enumType),
            asIdentifierExpression(rewriteExpression(expression.enumValue))
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitTypeAlias(statement: TypeAliasStatement): Any = statement

    override fun visitEnum(statement: EnumStatement): Any = statement

    override fun visitUse(statement: UseStatement): Any = statement

    override fun visitParameterStmt(statement: ParameterStatement): Any = statement

    override fun visitStruct(statement: StructStatement): Any = statement

    override fun visitTypeAlias(statement: TypeStatement): Any = statement

    override fun visitUnionType(statement: UnionTypeStatement): Any = statement

    private fun isTerminatingStatement(stmt: Statement): Boolean {
        var current = stmt
        while (current is BlockStatement) {
            val nonNull = current.statements.filterNotNull()
            if (nonNull.isEmpty()) return false
            current = nonNull.last()
        }
        return current is ReturnStatement
    }

    private fun asBlockStatement(stmt: Statement): BlockStatement {
        require(stmt is BlockStatement) { "Expected BlockStatement, got ${stmt::class.qualifiedName}" }
        return stmt
    }

    private fun asIdentifierExpression(expr: Expression): IdentifierExpression {
        require(expr is IdentifierExpression) { "Expected IdentifierExpression, got ${expr::class.qualifiedName}" }
        return expr
    }
}
