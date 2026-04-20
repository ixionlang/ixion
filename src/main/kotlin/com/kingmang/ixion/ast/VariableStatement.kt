package com.kingmang.ixion.ast

import com.kingmang.ixion.StatementVisitor
import com.kingmang.ixion.api.PublicAccess
import com.kingmang.ixion.lexer.Position
import com.kingmang.ixion.lexer.Token
import java.util.*

class VariableStatement(
    pos: Position?,
    val mutability: Token,
    val name: Token,
    val expression: Expression,
    val type: Optional<TypeStatement>
) : Statement(pos), PublicAccess {
    var localIndex: Int = -1

    override fun <R> accept(visitor: StatementVisitor<R>): R {
        return visitor.visitVariable(this)
    }

    override fun identifier(): String {
        return name.source
    }
}