package com.kingmang.ixion.parser

import com.kingmang.ixion.api.Context
import com.kingmang.ixion.api.IxApi.Companion.exit
import com.kingmang.ixion.api.PublicAccess
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.lexer.Lexer
import com.kingmang.ixion.lexer.Position
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.parser.infix.*
import com.kingmang.ixion.parser.prefix.*
import java.util.*

/**
 * Parser for the Ixion
 * Converts tokens into an abstract syntax tree (AST)
 */
class Parser(private val lexer: Lexer) {
    val prefixParselets: MutableMap<TokenType?, PrefixParselet?> = HashMap()
    val infixParselets: MutableMap<TokenType?, InfixParselet> = HashMap()
    private val mRead: MutableList<Token> = ArrayList<Token>()

    /**
     * Constructor for Parser
     * @param tokens The lexer providing tokens to parse
     */
    init {
        // Register infix parsers for operators and accessors
        putInfix(TokenType.ASSIGN, AssignOperatorParser())
        putInfix(TokenType.DOT, PropertyAccessParser())
        putInfix(TokenType.LBRACK, IndexAccessParser())

        // Register prefix parsers for literals and identifiers
        putPrefix(TokenType.INT, LiteralParser(false))
        putPrefix(TokenType.FLOAT, LiteralParser(false))
        putPrefix(TokenType.CHAR, LiteralParser(false))
        putPrefix(TokenType.DOUBLE, LiteralParser(false))
        putPrefix(TokenType.TRUE, LiteralParser(false))
        putPrefix(TokenType.FALSE, LiteralParser(false))
        putPrefix(TokenType.STRING, LiteralParser(false))
        putPrefix(TokenType.LBRACK, LiteralParser(true))
        putPrefix(TokenType.IDENTIFIER, IdentifierParser())
        putPrefix(TokenType.LAMBDA, LambdaParser())

        // Register grouping and function call parsers
        putPrefix(TokenType.LPAREN, GroupingParser())
        putInfix(TokenType.LPAREN, CallParser())

        // Register prefix operators
        prefix(TokenType.ADD, Precedence.PREFIX)
        prefix(TokenType.SUB, Precedence.PREFIX)
        prefix(TokenType.MOD, Precedence.PREFIX)
        prefix(TokenType.NOT, Precedence.PREFIX)

        // Register binary operators with associativity and precedence
        infixLeft(TokenType.ADD, Precedence.SUM)
        infixLeft(TokenType.SUB, Precedence.SUM)
        infixLeft(TokenType.MOD, Precedence.SUM)
        infixLeft(TokenType.MUL, Precedence.PRODUCT)
        infixLeft(TokenType.DIV, Precedence.PRODUCT)
        infixRight(TokenType.POW, Precedence.EXPONENT)

        infixLeft(TokenType.RANGE, Precedence.COMPARISON)

        // Register comparison operators
        infixLeft(TokenType.EQUAL, Precedence.COMPARISON)
        infixLeft(TokenType.NOTEQUAL, Precedence.COMPARISON)
        infixLeft(TokenType.LT, Precedence.COMPARISON)
        infixLeft(TokenType.GT, Precedence.COMPARISON)
        infixLeft(TokenType.LE, Precedence.COMPARISON)
        infixLeft(TokenType.GE, Precedence.COMPARISON)
        infixLeft(TokenType.AND, Precedence.AND)
        infixLeft(TokenType.OR, Precedence.OR)
        infixLeft(TokenType.XOR, Precedence.XOR)

        // Register postfix operators
        postfix(TokenType.PLUSPLUS, Precedence.POSTFIX)
        postfix(TokenType.MINUSMINUS, Precedence.POSTFIX)
    }

    /**
     * Parses the entire program into a list of statements
     * @return List of parsed statements
     */
    fun parse(): MutableList<Statement> {
        val stmts: MutableList<Statement> = ArrayList()
        while (notAtEnd())
            stmts.add(statement())
        return stmts
    }

    /**
     * Parses an expression with given precedence using Pratt parsing
     * @param precedence The minimum precedence to parse
     * @return The parsed expression
     */
    /**
     * Parses an expression with default precedence
     * @return The parsed expression
     */
    @JvmOverloads
    fun expression(precedence: Int = 0): Expression {
        var token = consume()
        val prefix = prefixParselets[token.type] ?: error(token, "Could not parse.")

        var left = prefix.parse(this, token)

        while (precedence < this.precedence) {
            token = consume()
            val infix: InfixParselet = infixParselets[token.type]!!
            left = infix.parse(this, left, token)
        }

        return left
    }

    /**
     * Parses a return statement
     * @return The parsed return statement
     */
    fun parseReturn(): ReturnStatement {
        val pos = this.pos

        // Handle return with or without expression on same line
        val expression =
            if (peek().type != TokenType.RBRACE && peek().line == pos.line) {
                expression()
            } else {
                EmptyExpression(pos)
            }
        return ReturnStatement(pos, expression)
    }

    /**
     * Parses a type alias statement (struct, enum, or type alias)
     * @return The parsed type alias statement
     */
    private fun parseTypeAlias(): Statement {
        val pos = this.pos
        val name = consume(TokenType.IDENTIFIER, "Expected type name.")
        consume(TokenType.ASSIGN, "Expected assignment operator.")

        if (match(TokenType.STRUCT)) {
            val generics = ArrayList<Token>()
            if (match(TokenType.LBRACK)) {
                var t = consume()
                generics.add(t)
                while (check(TokenType.COMMA)) {
                    consume()
                    t = consume()
                    generics.add(t)
                }
                consume(TokenType.RBRACK, "Expected closing bracket after struct generics.")
            }

            consume(TokenType.LBRACE, "Expected opening curly braces before struct body.")

            val parameters: MutableList<ParameterStatement> = ArrayList()
            while (!check(TokenType.RBRACE)) {
                val parameter = parseParameter()
                optional(TokenType.COMMA)
                parameters.add(parameter)
            }

            consume(TokenType.RBRACE, "Expected closing curly braces after struct body.")

            return StructStatement(pos, name, parameters, generics)
        } else if (match(TokenType.ENUM)) {
            consume(TokenType.LBRACE, "Expected opening curly braces before struct body.")

            val values: MutableList<Token?> = ArrayList<Token?>()
            while (!check(TokenType.RBRACE)) {
                val value = consume(TokenType.IDENTIFIER, "Expected enum value.")
                optional(TokenType.COMMA)
                values.add(value)
            }

            consume(TokenType.RBRACE, "Expected opening curly braces before struct body.")

            return EnumStatement(pos, name, values)
        }
        val union = parseUnion()

        return TypeAliasStatement(pos, name, union)
    }

    /**
     * Parses a block statement enclosed in curly braces
     * @return The parsed block statement
     */
    private fun parseBlock(): BlockStatement {
        val pos = this.pos
        val statements: MutableList<Statement?> = ArrayList<Statement?>()
        consume(TokenType.LBRACE, "Expect '{' before block.")

        while (!check(TokenType.RBRACE) && notAtEnd()) {
            statements.add(statement())
        }

        consume(TokenType.RBRACE, "Expect '}' after block.")
        return BlockStatement(pos, statements, Context())
    }

    /**
     * Parses a lambda function (-> expression (not implemented) or -> { block })
     * @return The parsed lambda expression
     * 
     * TODO: implement the design: -> expression
     */
    fun parseLambda(): LambdaExpression {
        val pos = this.pos

        // Parse parameters
        val parameters: MutableList<ParameterStatement> = ArrayList()
        if (match(TokenType.LPAREN)) {
            if (!check(TokenType.RPAREN)) {
                do {
                    parameters.add(parseParameter())
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.RPAREN, "Expected closing parentheses after lambda parameters.")
        } else {
            // Single parameter without parentheses
            val name = consume(TokenType.IDENTIFIER, "Expected parameter name or parentheses.")
            parameters.add(ParameterStatement(this.pos, name, null))
        }

        // Parse return type if specified
        var returnType: TypeStatement? = null
        if (match(TokenType.COLON)) {
            returnType = parseUnion()
        }

        // Parse lambda body - всегда блок
        val body = parseBlock()

        return LambdaExpression(pos, parameters, returnType!!, body)
    }

    /**
     * Parses an export (public) statement
     * @return The parsed export statement
     */
    private fun parsePublic(): ExportStatement {
        val pos = this.pos
        val stmt = statement()
        if (stmt is PublicAccess)
            return ExportStatement(pos, stmt)
        exit("Can only export struct, type alias, variable, enum or function.", 65)
    }

    /**
     * Parses a function definition
     * @return The parsed function statement
     */
    private fun parseFunction(): DefStatement {
        val pos = this.pos
        val name = consume(TokenType.IDENTIFIER, "Expected function name.")

        val generics = ArrayList<Token>()
        if (match(TokenType.LBRACK)) {
            var t = consume()
            generics.add(t)
            while (check(TokenType.COMMA)) {
                consume()
                t = consume()
                generics.add(t)
            }
            consume(TokenType.RBRACK, "Expected closing bracket after function generics.")
        }

        consume(TokenType.LPAREN, "Expected opening parentheses after function name.")

        val parameters: MutableList<ParameterStatement> = ArrayList()
        if (!check(TokenType.RPAREN)) {
            do {
                parameters.add(parseParameter())
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected closing parentheses after function parameters.")

        var returnType: TypeStatement? = null
        if (match(TokenType.COLON)) {
            returnType = parseUnion()
        }

        val block = parseBlock()

        val func = DefStatement(pos, name, parameters, returnType, block, generics)
        if (!hasReturnStatement(func.body) && returnType != null) {
            error(
                name,
                "Function must return a value of type '${func.returnType!!.identifier!!.source}'"
            )
        }
        return func
    }

    private fun hasReturnStatement(stmt: Statement?): Boolean {
        return when (stmt) {
            is BlockStatement -> {
                for (s in stmt.statements) {
                    if (hasReturnStatement(s)) {
                        return true
                    }
                }
                false
            }

            is ReturnStatement -> true

            is IfStatement -> {
                val thenHasReturn = hasReturnStatement(stmt.trueBlock)
                val elseHasReturn = stmt.falseStatement != null &&
                        hasReturnStatement(stmt.falseStatement)
                thenHasReturn && elseHasReturn
            }

            else -> false
        }
    }

    /**
     * Parses a use (import) statement
     * @return The parsed use statement
     */
    private fun parseUse(): UseStatement {
        val pos = this.pos

        if (!match(TokenType.LT)) {
            error(peek(), "Expected '<' at the start of using path")
        }

        val usePath = StringBuilder()
        var first = true

        while (!check(TokenType.GT) && notAtEnd()) {
            if (!first) {
                if (!match(TokenType.DIV)) {
                    error(peek(), "Expected '/' in use path")
                }
                usePath.append("/")
            }

            val part = consume(TokenType.IDENTIFIER, "Expected identifier in using path")
            usePath.append(part.source)
            first = false
        }

        consume(TokenType.GT, "Expected '>' at the end of import path")

        val id: Optional<Token> = Optional.empty()
        val useToken = Token(TokenType.STRING, pos.line, pos.col, usePath.toString())
        return UseStatement(pos, useToken, id)
    }

    /**
     * Parses a case (pattern matching) statement
     * @return The parsed case statement
     */
    private fun parseCase(): Statement {
        val pos = this.pos

        val expr = expression()

        consume(TokenType.LBRACE, "Expected opening curly braces before case body.")
        val cases: MutableMap<TypeStatement, kotlin.Pair<String, BlockStatement>> = HashMap()

        while (!check(TokenType.RBRACE)) {
            val type = parseUnion()
            val s = consume(TokenType.IDENTIFIER, "Expected name for reified value before `=>` in case statement.")

            consume(TokenType.ARROW, "Expected `=>` after type before expression in case statement.")
            val caseBody: BlockStatement?
            if (check(TokenType.LBRACE)) {
                caseBody = parseBlock()
            } else {
                val stmt = ExpressionStatement(this.pos, expression())
                caseBody = BlockStatement(this.pos, mutableListOf(stmt), Context())
            }
            cases[type] = kotlin.Pair(s.source!!, caseBody)
        }

        consume(TokenType.RBRACE, "Expected closing curly braces after case body.")

        return CaseStatement(pos, expr, cases)
    }

    /**
     * Parses a function or struct parameter
     * @return The parsed parameter statement
     */
    private fun parseParameter(): ParameterStatement {
        val pos = this.pos
        val name = consume(TokenType.IDENTIFIER, "Expected field name.")
        consume(TokenType.COLON, "Expected colon after parameter name.")
        val type = parseUnion()
        return ParameterStatement(pos, name, type)
    }

    private fun parseFor(): ForStatement {
        val pos = this.pos
        val name = consume(TokenType.IDENTIFIER, "Need iterator variable name.")
        consume(TokenType.COLON, "Expected ':' operator.")
        val condition = expression()
        val block = parseBlock()
        return ForStatement(pos, name, condition, block)
    }


    /**
     * Parses an if statement with optional else branch
     * @return The parsed if statement
     */
    private fun parseIf(): IfStatement {
        val pos = this.pos
        val condition = expression()
        val trueBlock = parseBlock()
        var falseStatement: Statement? = null
        if (match(TokenType.ELSE)) {
            falseStatement =
                when {
                    match(TokenType.IF) -> parseIf()
                    check(TokenType.LBRACE) -> parseBlock()
                    else -> error(peek(), "Invalid end to if-else statement.")
                }
        }
        return IfStatement(pos, condition, trueBlock, falseStatement)
    }

    /**
     * Parses a while loop statement
     * @return The parsed while statement
     */
    private fun parseWhile(): WhileStatement {
        val pos = this.pos
        val condition = expression()
        val block = parseBlock()
        return WhileStatement(pos, condition, block)
    }

    /**
     * Parses any kind of statement based on the current token
     * @return The parsed statement
     */
    private fun statement(): Statement {
        val pos = this.pos
        return when {
            check(TokenType.ERROR) -> throw Error("error")
            match(TokenType.USE) -> parseUse()
            match(TokenType.PUB) -> parsePublic()
            match(TokenType.TYPEALIAS) -> parseTypeAlias()
            match(TokenType.CASE) -> parseCase()
            match(TokenType.DEF) -> parseFunction()
            match(TokenType.IF) -> parseIf()
            check(TokenType.VARIABLE) || check(TokenType.CONSTANT) -> parseVariable()
            match(TokenType.FOR) -> parseFor()
            match(TokenType.WHILE) -> parseWhile()
            match(TokenType.RETURN) -> parseReturn()
            check(TokenType.LBRACE) -> parseBlock()
            else -> ExpressionStatement(pos, expression())
        }
    }

    /**
     * Parses a type reference
     * @return The parsed type statement
     */
    private fun parseType(): TypeStatement {
        val pos = this.pos
        val identifier = consume(TokenType.IDENTIFIER, "Expected type name.")

        var listType = false
        if (match(TokenType.LBRACK)) {
            listType = true
            consume(TokenType.RBRACK, "Expected ']' in list type.")
        }

        var t: Optional<TypeStatement> = Optional.empty()
        if (match(TokenType.DOT)) {
            t = Optional.of(parseType())
        }

        return TypeStatement(pos, identifier, t, listType)
    }

    /**
     * Parses a union type (multiple types separated by pipes)
     * @return The parsed union type statement
     */
    private fun parseUnion(): TypeStatement {
        val pos = this.pos
        val types: MutableList<TypeStatement> = ArrayList()
        do {
            if (check(TokenType.PIPE)) consume()
            val type = parseType()
            types.add(type)
        } while (check(TokenType.PIPE))

        var t: TypeStatement = UnionTypeStatement(pos, types)
        if (types.size == 1) {
            t = TypeStatement(
                pos,
                types.first().identifier,
                Optional.empty<TypeStatement>(),
                types.first().listType
            )
        }

        return t
    }

    /**
     * Parses a variable declaration
     * @return The parsed variable statement
     */
    private fun parseVariable(): VariableStatement {
        val pos = this.pos
        val mutability = consume()
        val name = consume(TokenType.IDENTIFIER, "Expected variable name.")
        consume(TokenType.ASSIGN, "Expected assignment operator.")
        val expression = expression()
        return VariableStatement(pos, mutability, name, expression, Optional.empty<TypeStatement>())
    }


    /**
     * Consumes the current token
     * @return The consumed token
     */
    fun consume(): Token {
        lookAhead()
        return mRead.removeFirst()
    }

    /**
     * Consumes a token of expected type or reports error
     * @param type The expected token type
     * @param message Error message if type doesn't match
     * @return The consumed token
     */
    fun consume(type: TokenType?, message: String?): Token {
        if (check(type)) return consume()
        error(peek(), message)
    }

    /**
     * Registers a left-associative infix operator
     * @param token The operator token type
     * @param precedence The operator precedence
     */
    fun infixLeft(token: TokenType?, precedence: Int) {
        putInfix(token, BinaryOperatorParser(precedence, false))
    }

    /**
     * Registers a right-associative infix operator
     * @param token The operator token type
     * @param precedence The operator precedence
     */
    fun infixRight(token: TokenType?, precedence: Int) {
        putInfix(token, BinaryOperatorParser(precedence, true))
    }

    val pos: Position
        /**
         * Gets the current parsing position
         * @return The current position
         */
        get() = this.lexer.position()

    /**
     * Registers a postfix operator
     * @param token The operator token type
     * @param precedence The operator precedence
     */
    fun postfix(token: TokenType?, precedence: Int) {
        putInfix(token, PostfixOperatorParser(precedence))
    }

    /**
     * Registers a prefix operator
     * @param token The operator token type
     * @param precedence The operator precedence
     */
    fun prefix(token: TokenType?, precedence: Int) {
        putPrefix(token, PrefixOperatorParser(precedence))
    }

    /**
     * Checks if the current token matches the given type
     * @param type The token type to check
     * @return True if current token matches the type
     */
    fun check(type: TokenType?): Boolean {
        return peek().type == type
    }


    /**
     * Reports a parsing error
     * @param token The token where error occurred
     * @param message The error message
     */
    fun error(token: Token, message: String?): Nothing {
        if (token.type == TokenType.EOF) {
            error(token.line, " at end", message)
        } else {
            error(token.line, " at '" + token.source + "'", message)
        }
    }

    val precedence: Int
        /**
         * Gets the precedence of the next operator
         * @return The precedence value
         */
        get() {
            val parser = infixParselets[lookAhead().type]
            if (parser != null)
                return parser.precedence
            return 0
        }

    /**
     * Looks ahead at a token at the specified distance without consuming it
     * @param distance The lookahead distance (0 = current token, 1 = next token, etc.)
     * @return The token at the specified distance
     */
    fun lookAhead(distance: Int): Token {
        while (mRead.size <= distance)
            mRead.add(lexer.tokenize() ?: break)
        return mRead[distance]
    }

    /**
     * Looks ahead at the next token without consuming it
     * @return The next token
     */
    fun lookAhead(): Token {
        while (mRead.isEmpty())
            mRead.add(lexer.tokenize() ?: break)
        return mRead.first()
    }

    /**
     * Checks if there are more tokens to parse
     * @return True if not at end of input
     */
    fun notAtEnd(): Boolean {
        return peek().type != TokenType.EOF
    }

    /**
     * Optionally consumes a token if it matches the expected type
     * @param type The token type to optionally consume
     */
    fun optional(type: TokenType?) {
        if (check(type)) consume()
    }

    /**
     * Peeks at the next token without consuming it
     * @return The next token
     */
    fun peek(): Token {
        return lookAhead()
    }

    /**
     * Registers an infix parselet
     * @param token The token type
     * @param parselet The infix parselet
     */
    fun putInfix(token: TokenType?, parselet: InfixParselet?) {
        infixParselets[token] = parselet!!
    }

    /**
     * Registers a prefix parselet
     * @param token The token type
     * @param parselet The prefix parselet
     */
    fun putPrefix(token: TokenType?, parselet: PrefixParselet?) {
        prefixParselets[token] = parselet
    }

    /**
     * Matches and consumes a token if it matches the expected type
     * @param expected The expected token type
     * @return True if token was matched and consumed
     */
    fun match(expected: TokenType?): Boolean {
        val token = lookAhead()
        if (token.type != expected) {
            return false
        }
        consume()
        return true
    }

    companion object {
        /**
         * Reports a parsing error and exits
         * @param line The line number where error occurred
         * @param where Location description
         * @param message Error message
         */
        private fun error(line: Int, where: String?, message: String?): Nothing {
            exit("[line $line] Parser error$where: $message", 1)
        }
    }
}