package com.kingmang.ixion.lexer

interface Lexer {
    fun tokenize(): Token?
    fun position(): Position
}
