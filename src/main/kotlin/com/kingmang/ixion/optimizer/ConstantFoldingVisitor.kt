package com.kingmang.ixion.optimizer

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.BuiltInType
import kotlin.math.pow

class ConstantFoldingVisitor : Visitor<Any> {
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

    override fun visitTypeAlias(statement: TypeAliasStatement): Any = statement

    override fun visitBlockStmt(statement: BlockStatement): Any {
        val rewritten = mutableListOf<Statement?>()
        for (stmt in statement.statements) {
            rewritten.add(if (stmt == null) null else rewriteStatement(stmt))
        }
        return BlockStatement(statement.position, rewritten, statement.context)
    }

    override fun visitEnum(statement: EnumStatement): Any = statement

    override fun visitExport(statement: ExportStatement): Any {
        return ExportStatement(statement.position, rewriteStatement(statement.stmt))
    }

    override fun visitExpressionStmt(statement: ExpressionStatement): Any {
        return ExpressionStatement(statement.position, rewriteExpression(statement.expression))
    }

    override fun visitFor(statement: ForStatement): Any {
        return ForStatement(
            statement.position,
            statement.name,
            rewriteExpression(statement.expression),
            rewriteStatement(statement.block) as BlockStatement
        )
    }

    override fun visitFunctionStmt(statement: DefStatement): Any {
        return DefStatement(
            statement.position,
            statement.name,
            statement.parameters,
            statement.returnType,
            if (statement.body == null) null else rewriteStatement(statement.body) as BlockStatement,
            statement.generics
        )
    }

    override fun visitIf(statement: IfStatement): Any {
        return IfStatement(
            statement.position,
            rewriteExpression(statement.condition),
            rewriteStatement(statement.trueBlock) as BlockStatement,
            if (statement.falseStatement == null) null else rewriteStatement(statement.falseStatement)
        )
    }

    override fun visitUse(statement: UseStatement): Any = statement

    override fun visitMatch(statement: CaseStatement): Any {
        val rewrittenCases = LinkedHashMap<TypeStatement, Pair<String, BlockStatement>>()
        for ((typeStmt, pair) in statement.cases) {
            rewrittenCases[typeStmt] = Pair(pair.first, rewriteStatement(pair.second) as BlockStatement)
        }
        val rewritten = CaseStatement(
            statement.position,
            rewriteExpression(statement.expression),
            rewrittenCases
        )
        rewritten.types.putAll(statement.types)
        return rewritten
    }

    override fun visitParameterStmt(statement: ParameterStatement): Any = statement

    override fun visitReturnStmt(statement: ReturnStatement): Any {
        return ReturnStatement(
            statement.position,
            if (statement.expression == null) null else rewriteExpression(statement.expression)
        )
    }

    override fun visitStruct(statement: StructStatement): Any = statement

    override fun visitTypeAlias(statement: TypeStatement): Any = statement

    override fun visitUnionType(statement: UnionTypeStatement): Any = statement

    override fun visitVariable(statement: VariableStatement): Any {
        return VariableStatement(
            statement.position,
            statement.mutability,
            statement.name,
            rewriteExpression(statement.expression),
            statement.type
        )
    }

    override fun visitWhile(statement: WhileStatement): Any {
        return WhileStatement(
            statement.position,
            rewriteExpression(statement.condition),
            rewriteStatement(statement.block) as BlockStatement
        )
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

    override fun visitBad(expression: BadExpression): Any = expression

    override fun visitBinaryExpr(expression: BinaryExpression): Any {
        val left = rewriteExpression(expression.left)
        val right = rewriteExpression(expression.right)
        val folded = foldBinary(expression, left, right)
        if (folded != null) return folded
        val rewritten = BinaryExpression(expression.position, left, expression.operator, right)
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

    override fun visitEmpty(expression: EmptyExpression): Any = expression

    override fun visitEmptyList(expression: EmptyListExpression): Any = expression

    override fun visitGroupingExpr(expression: GroupingExpression): Any {
        val rewritten = rewriteExpression(expression.expression)
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitIdentifierExpr(expression: IdentifierExpression): Any = expression

    override fun visitIndexAccess(expression: IndexAccessExpression): Any {
        val rewritten = IndexAccessExpression(
            expression.position,
            rewriteExpression(expression.left),
            rewriteExpression(expression.right)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitLiteralExpr(expression: LiteralExpression): Any = expression

    override fun visitLiteralList(expression: LiteralListExpression): Any {
        val rewritten = LiteralListExpression(
            expression.position,
            expression.entries.mapTo(mutableListOf()) { rewriteExpression(it) }
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitModuleAccess(expression: ModuleAccessExpression): Any {
        val rewritten = ModuleAccessExpression(
            expression.position,
            rewriteExpression(expression.identifier) as IdentifierExpression,
            rewriteExpression(expression.foreign)
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

    override fun visitPrefix(expression: PrefixExpression): Any {
        val right = rewriteExpression(expression.right)
        val folded = foldPrefix(expression, right)
        if (folded != null) return folded
        val rewritten = PrefixExpression(expression.position, expression.operator, right)
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

    override fun visitLambda(expression: LambdaExpression): Any {
        val rewritten = LambdaExpression(
            expression.position,
            expression.parameters,
            expression.returnType,
            rewriteStatement(expression.body) as BlockStatement
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    override fun visitEnumAccess(expression: EnumAccessExpression): Any {
        val rewritten = EnumAccessExpression(
            expression.position,
            rewriteExpression(expression.enumType),
            rewriteExpression(expression.enumValue) as IdentifierExpression
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    private fun foldPrefix(original: PrefixExpression, right: Expression): Expression? {
        val literal = right as? LiteralExpression ?: return null
        return when (original.operator.type) {
            TokenType.SUB -> when (literal.realType) {
                BuiltInType.INT -> makeLiteral(original, TokenType.INT, (-literal.literal.source.toInt()).toString(), BuiltInType.INT)
                BuiltInType.FLOAT -> makeLiteral(original, TokenType.FLOAT, (-literal.literal.source.toFloat()).toString(), BuiltInType.FLOAT)
                BuiltInType.DOUBLE -> makeLiteral(original, TokenType.DOUBLE, (-literal.literal.source.toDouble()).toString(), BuiltInType.DOUBLE)
                else -> null
            }

            TokenType.NOT -> if (literal.realType == BuiltInType.BOOLEAN) {
                val value = literal.literal.source.toBooleanStrict()
                booleanLiteral(original, !value)
            } else null

            else -> null
        }
    }

    private fun foldBinary(original: BinaryExpression, left: Expression, right: Expression): Expression? {
        val l = left as? LiteralExpression ?: return null
        val r = right as? LiteralExpression ?: return null

        val lType = l.realType
        val rType = r.realType
        val op = original.operator.type

        if (lType is BuiltInType && rType is BuiltInType && lType.isNumeric && rType.isNumeric) {
            return foldNumeric(original, l.literal, r.literal, op, original.realType)
        }

        if (lType == BuiltInType.STRING && rType == BuiltInType.STRING && op == TokenType.ADD) {
            val value = l.literal.source + r.literal.source
            return makeLiteral(original, TokenType.STRING, value, BuiltInType.STRING)
        }

        if (lType == BuiltInType.BOOLEAN && rType == BuiltInType.BOOLEAN) {
            val lv = l.literal.source.toBooleanStrict()
            val rv = r.literal.source.toBooleanStrict()
            val result = when (op) {
                TokenType.AND -> lv && rv
                TokenType.OR -> lv || rv
                TokenType.EQUAL -> lv == rv
                TokenType.NOTEQUAL -> lv != rv
                else -> return null
            }
            return booleanLiteral(original, result)
        }

        return null
    }

    private fun foldNumeric(original: BinaryExpression, left: Token, right: Token, op: TokenType, resultHint: com.kingmang.ixion.runtime.IxType): Expression? {
        val resultType = when (resultHint) {
            BuiltInType.INT -> TokenType.INT
            BuiltInType.FLOAT -> TokenType.FLOAT
            BuiltInType.DOUBLE -> TokenType.DOUBLE
            else -> if (left.type == TokenType.DOUBLE || right.type == TokenType.DOUBLE) {
            TokenType.DOUBLE
        } else if (left.type == TokenType.FLOAT || right.type == TokenType.FLOAT) {
            TokenType.FLOAT
        } else {
            TokenType.INT
        }
        }

        val l = left.source.toDouble()
        val r = right.source.toDouble()

        return when (op) {
            TokenType.ADD -> numericLiteral(original, resultType, l + r)
            TokenType.SUB -> numericLiteral(original, resultType, l - r)
            TokenType.MUL -> numericLiteral(original, resultType, l * r)
            TokenType.DIV -> {
                if (r == 0.0) null else {
                    if (resultType == TokenType.INT) {
                        makeLiteral(original, TokenType.INT, (left.source.toInt() / right.source.toInt()).toString(), BuiltInType.INT)
                    } else {
                        numericLiteral(original, resultType, l / r)
                    }
                }
            }
            TokenType.MOD -> {
                if (r == 0.0) null else {
                    if (resultType == TokenType.INT) {
                        makeLiteral(original, TokenType.INT, (left.source.toInt() % right.source.toInt()).toString(), BuiltInType.INT)
                    } else {
                        numericLiteral(original, resultType, l % r)
                    }
                }
            }
            TokenType.POW -> numericLiteral(original, resultType, l.pow(r))
            TokenType.GT -> booleanLiteral(original, l > r)
            TokenType.GE -> booleanLiteral(original, l >= r)
            TokenType.LT -> booleanLiteral(original, l < r)
            TokenType.LE -> booleanLiteral(original, l <= r)
            TokenType.EQUAL -> booleanLiteral(original, l == r)
            TokenType.NOTEQUAL -> booleanLiteral(original, l != r)
            else -> null
        }
    }

    private fun numericLiteral(expression: Expression, type: TokenType, value: Double): Expression {
        return when (type) {
            TokenType.INT -> makeLiteral(expression, TokenType.INT, value.toInt().toString(), BuiltInType.INT)
            TokenType.FLOAT -> makeLiteral(expression, TokenType.FLOAT, value.toFloat().toString(), BuiltInType.FLOAT)
            TokenType.DOUBLE -> makeLiteral(expression, TokenType.DOUBLE, value.toString(), BuiltInType.DOUBLE)
            else -> throw IllegalArgumentException("Unexpected numeric type: $type")
        }
    }

    private fun booleanLiteral(expression: Expression, value: Boolean): Expression {
        return if (value) makeLiteral(expression, TokenType.TRUE, "true", BuiltInType.BOOLEAN)
        else makeLiteral(expression, TokenType.FALSE, "false", BuiltInType.BOOLEAN)
    }

    private fun makeLiteral(expression: Expression, type: TokenType, source: String, foldedType: BuiltInType): Expression {
        val pos = expression.position
        val token = Token(type, pos?.line ?: 0, pos?.col ?: 0, source)
        val literal = LiteralExpression(pos, token)
        literal.realType = foldedType
        return literal
    }
}
