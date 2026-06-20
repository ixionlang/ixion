package com.kingmang.ixion.parser.infix

import com.kingmang.ixion.ast.AssignExpression
import com.kingmang.ixion.ast.BinaryExpression
import com.kingmang.ixion.ast.Expression
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.parser.Parser
import com.kingmang.ixion.parser.Precedence

class CompoundAssignOperatorParser(private val binaryOp: TokenType) : InfixParselet {
    override fun parse(parser: Parser, left: Expression, token: Token): Expression {
        val pos = parser.pos
        val right = parser.expression(precedence - 1)
        val opToken = Token(binaryOp, token.line, token.col, token.source)
        return AssignExpression(pos, left, BinaryExpression(pos, left, opToken, right))
    }

    override val precedence: Int
        get() = Precedence.ASSIGNMENT
}
