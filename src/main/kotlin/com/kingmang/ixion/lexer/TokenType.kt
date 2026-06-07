package com.kingmang.ixion.lexer

import java.util.*

enum class TokenType {
    CASE("case"),
    FOR("for"),
    WHILE("while"),
    IF("if"),
    ELSE("else"),
    RETURN("return"),
    STRUCT("struct"),
    ENUM("enum"),
    DEF("def"),
    TYPEALIAS("type"),
    CONSTANT("const"),
    VARIABLE("var"),
    PUB("pub"),
    USE("use"),
    NEW("new"),
    //MUT("mut"),
    LAMBDA("lambda"),

    LPAREN("("),
    RPAREN(")"),
    LBRACE("{"),
    RBRACE("}"),
    LBRACK("["),
    RBRACK("]"),
    COMMA(","),
    VARARGS("..."),
    RANGE(".."),
    DOT("."),
    MODULE("::"),
    COLON(":"),
    DEFAULT("_"),

    ASSIGN("="),
    GE(">="),
    LE("<="),
    GT(">"),
    LT("<"),
    EQUAL("=="),
    NOTEQUAL("!="),
    AND("&&"),
    OR("||"),
    XOR("^"),
    ADD("+"),
    MUL("*"),
    SUB("-"),
    DIV("/"),
    MOD("%"),
    POW("**"),
    PIPE("|"),
    ARROW("=>"),


    NOT("!"),
    PLUSPLUS("++"),
    MINUSMINUS("--"),

    TRUE("true"),
    FALSE("false"),
    STRING,
    IDENTIFIER,
    INT,
    FLOAT,
    DOUBLE,
    CHAR,
    NUMBER,

    ERROR,
    EOF;

    val representation: String?
    val alternate: String?

    constructor() {
        this.representation = null
        this.alternate = null
    }

    constructor(representation: String?) {
        this.representation = representation
        this.alternate = null
    }

    constructor(representation: String?, alternate: String?) {
        this.representation = representation
        this.alternate = alternate
    }

    val isKeyword: Boolean
        get() = KEYWORD_TYPES.contains(this)

    companion object {
        private val matcher = HashMap<String?, TokenType>()
        private val KEYWORD_TYPES: MutableSet<TokenType> = EnumSet.of(
            CASE,
            FOR,
            WHILE,
            IF,
            ELSE,
            RETURN,
            STRUCT,
            ENUM,
            DEF,
            TYPEALIAS,
            CONSTANT,
            VARIABLE,
            PUB,
            USE,
            NEW,
            TRUE,
            FALSE
        )

        init {
            for (tokenType in entries) {
                tokenType.representation?.let { matcher[it] = tokenType }
                tokenType.alternate?.let { matcher[it] = tokenType }
            }
        }

        fun find(representation: String?): TokenType? {
            return matcher[representation]
        }

        fun isKeyword(str: String?): Boolean {
            val tokenType: TokenType? = find(str)
            return tokenType != null && tokenType.isKeyword
        }
    }
}