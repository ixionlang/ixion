package com.kingmang.ixion.optimizer

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.parser.Node

/**
 * Shared post-order traversal for optimization passes over checked syntax.
 * Reconstructed nodes retain semantic annotations and source positions. Data-expression
 * subtrees and lambdas remain opaque so checked match plans and capture metadata stay valid.
 * Subclasses implement local rules in [transformExpression], without duplicating traversal.
 */
abstract class AstRewriteVisitor : Visitor<Node> {
    /** Retains the checked unit expression. */
    override fun visitUnit(expression: UnitExpression): Expression = expression

    /** Retains tuple subtrees that may participate in checked match plans. */
    override fun visitTuple(expression: TupleExpression): Expression = expression

    /** Retains resolved type arguments and their checked syntax. */
    override fun visitTypeApplication(expression: TypeApplicationExpression): Expression = expression

    /** Retains checked record-update syntax and its child expressions. */
    override fun visitUpdate(expression: UpdateExpression): Expression = expression

    /** Retains expression blocks referenced by checked match plans. */
    override fun visitBlockExpression(expression: BlockExpression): Expression = expression

    /** Retains the match subject, branches, contexts, and checked pattern plans by identity. */
    override fun visitCaseExpression(expression: CaseExpression): Expression = expression

    /** Rewrites the file statements in place using this pass alone. */
    fun optimize(file: IxFile) {
        for (i in file.statements.indices) {
            file.statements[i] = rewriteStatement(file.statements[i])
        }
    }

    /** Rewrites a statement and its supported descendants while preserving checked metadata. */
    fun rewriteStatement(statement: Statement): Statement {
        return statement.accept(this) as Statement
    }

    /** Rewrites children before applying this pass to their parent expression. */
    fun rewriteExpression(expression: Expression): Expression {
        return transformExpression(expression.accept(this) as Expression)
    }

    /** Applies a local optimization to a node whose supported children have already been rewritten. */
    protected open fun transformExpression(expression: Expression): Expression = expression

    /** Dispatches a statement through the shared rewriting traversal. */
    override fun visit(statement: Statement): Statement = rewriteStatement(statement)

    /** Retains type declarations and their resolved annotations. */
    override fun visitTypeAlias(statement: TypeAliasStatement): Statement = statement

    /** Rewrites block statements in order and retains their lexical context and empty entries. */
    override fun visitBlockStmt(statement: BlockStatement): Statement {
        val rewritten = mutableListOf<Statement?>()
        for (stmt in statement.statements) {
            rewritten.add(if (stmt == null) null else rewriteStatement(stmt))
        }
        return BlockStatement(statement.position, rewritten, statement.context)
    }

    /** Retains enum declarations. */
    override fun visitEnum(statement: EnumStatement): Statement = statement

    /** Rewrites an exported declaration while retaining its export wrapper. */
    override fun visitExport(statement: ExportStatement): Statement {
        return ExportStatement(statement.position, rewriteStatement(statement.stmt))
    }

    /** Rewrites the expression evaluated by this statement. */
    override fun visitExpressionStmt(statement: ExpressionStatement): Statement {
        return ExpressionStatement(statement.position, rewriteExpression(statement.expression))
    }

    /** Rewrites the iterable and loop body in their original contexts. */
    override fun visitFor(statement: ForStatement): Statement {
        return ForStatement(
            statement.position,
            statement.name,
            rewriteExpression(statement.expression),
            rewriteStatement(statement.block) as BlockStatement
        )
    }

    /** Rewrites a function body and preserves its resolved signature and generic parameters. */
    override fun visitFunctionStmt(statement: DefStatement): Statement {
        return DefStatement(
            statement.position,
            statement.name,
            statement.parameters,
            statement.returnType,
            if (statement.body == null) null else rewriteStatement(statement.body) as BlockStatement,
            statement.generics
        ).also { it.functionType = statement.functionType }
    }

    /** Rewrites the condition and both branches without changing their evaluation order. */
    override fun visitIf(statement: IfStatement): Statement {
        return IfStatement(
            statement.position,
            rewriteExpression(statement.condition),
            rewriteStatement(statement.trueBlock) as BlockStatement,
            if (statement.falseStatement == null) null else rewriteStatement(statement.falseStatement)
        )
    }

    /** Retains module imports. */
    override fun visitUse(statement: UseStatement): Statement = statement

    /** Retains checked parameter declarations. */
    override fun visitParameterStmt(statement: ParameterStatement): Statement = statement

    /** Rewrites a return value when present, retaining bare returns. */
    override fun visitReturnStmt(statement: ReturnStatement): Statement {
        return ReturnStatement(
            statement.position,
            if (statement.expression == null) null else rewriteExpression(statement.expression)
        )
    }

    /** Retains checked structure declarations. */
    override fun visitStruct(statement: StructStatement): Statement = statement

    /** Retains type declarations and their resolved annotations. */
    override fun visitTypeAlias(statement: TypeStatement): Statement = statement

    /** Retains checked union declarations. */
    override fun visitUnionType(statement: UnionTypeStatement): Statement = statement

    /** Rewrites an initializer while retaining the declared name, type, and mutability. */
    override fun visitVariable(statement: VariableStatement): Statement {
        return VariableStatement(
            statement.position,
            statement.mutable,
            statement.name,
            rewriteExpression(statement.expression),
            statement.type
        )
    }

    /** Rewrites a loop condition and body without moving computations between them. */
    override fun visitWhile(statement: WhileStatement): Statement {
        return WhileStatement(
            statement.position,
            rewriteExpression(statement.condition),
            rewriteStatement(statement.block) as BlockStatement
        )
    }

    /** Rewrites assignment operands and preserves the checked result type. */
    override fun visitAssignExpr(expression: AssignExpression): Expression {
        val rewritten = AssignExpression(
            expression.position,
            rewriteExpression(expression.left),
            rewriteExpression(expression.right)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Retains invalid syntax for the existing diagnostics pipeline. */
    override fun visitBad(expression: BadExpression): Expression = expression

    /** Rewrites both operands and preserves the operator and checked result type. */
    override fun visitBinaryExpr(expression: BinaryExpression): Expression {
        val left = rewriteExpression(expression.left)
        val right = rewriteExpression(expression.right)
        val rewritten = BinaryExpression(expression.position, left, expression.operator, right)
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Rewrites the callee and arguments while preserving foreign-call and specialization metadata. */
    override fun visitCall(expression: CallExpression): Expression {
        val item = rewriteExpression(expression.item)
        val args = expression.arguments.mapTo(mutableListOf()) { rewriteExpression(it) }
        val rewritten = CallExpression(expression.position, item, args)
        rewritten.foreign = expression.foreign
        rewritten.realType = expression.realType
        rewritten.specialization = expression.specialization
        return rewritten
    }

    /** Retains an empty expression. */
    override fun visitEmpty(expression: EmptyExpression): Expression = expression

    /** Retains a checked empty-list expression. */
    override fun visitEmptyList(expression: EmptyListExpression): Expression = expression

    /** Removes redundant grouping after rewriting its child, retaining the checked type. */
    override fun visitGroupingExpr(expression: GroupingExpression): Expression {
        val rewritten = rewriteExpression(expression.expression)
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Retains a variable reference and its checked type. */
    override fun visitIdentifierExpr(expression: IdentifierExpression): Expression = expression

    /** Rewrites the receiver and index while preserving the checked result type. */
    override fun visitIndexAccess(expression: IndexAccessExpression): Expression {
        val rewritten = IndexAccessExpression(
            expression.position,
            rewriteExpression(expression.left),
            rewriteExpression(expression.right)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Retains a typed literal. */
    override fun visitLiteralExpr(expression: LiteralExpression): Expression = expression

    /** Rewrites list entries in order and preserves the checked list type. */
    override fun visitLiteralList(expression: LiteralListExpression): Expression {
        val rewritten = LiteralListExpression(
            expression.position,
            expression.entries.mapTo(mutableListOf()) { rewriteExpression(it) }
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Rewrites module access operands and retains the checked result type. */
    override fun visitModuleAccess(expression: ModuleAccessExpression): Expression {
        val rewritten = ModuleAccessExpression(
            expression.position,
            rewriteExpression(expression.identifier) as IdentifierExpression,
            rewriteExpression(expression.foreign)
        )
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Rewrites a postfix operand and retains its resolved local slot and result type. */
    override fun visitPostfixExpr(expression: PostfixExpression): Expression {
        val rewritten = PostfixExpression(
            expression.position,
            rewriteExpression(expression.expression),
            expression.operator
        )
        rewritten.localIndex = expression.localIndex
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Rewrites a prefix operand and preserves the operator and checked result type. */
    override fun visitPrefix(expression: PrefixExpression): Expression {
        val right = rewriteExpression(expression.right)
        val rewritten = PrefixExpression(expression.position, expression.operator, right)
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Rewrites the receiver and preserves the resolved field type chain. */
    override fun visitPropertyAccess(expression: PropertyAccessExpression): Expression {
        val rewritten = PropertyAccessExpression(
            expression.position,
            rewriteExpression(expression.expression),
            expression.identifiers
        )
        rewritten.typeChain = expression.typeChain
        rewritten.realType = expression.realType
        return rewritten
    }

    /** Retains lambda syntax and its checked capture metadata. */
    override fun visitLambda(expression: LambdaExpression): Expression = expression

    /** Rewrites enum access operands while preserving the checked result type. */
    override fun visitEnumAccess(expression: EnumAccessExpression): Expression {
        val rewritten = EnumAccessExpression(
            expression.position,
            rewriteExpression(expression.enumType),
            rewriteExpression(expression.enumValue) as IdentifierExpression
        )
        rewritten.realType = expression.realType
        return rewritten
    }
}
