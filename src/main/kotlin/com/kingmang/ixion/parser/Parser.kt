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
    private var speculativeTokens: MutableList<Token>? = null

    /**
     * Constructor for Parser
     * @param tokens The lexer providing tokens to parse
     */
    init {
        // Register infix parsers for operators and accessors
        putInfix(TokenType.ASSIGN, AssignOperatorParser())
        putInfix(TokenType.ASSIGNADD, CompoundAssignOperatorParser(TokenType.ADD))
        putInfix(TokenType.ASSIGNSUB, CompoundAssignOperatorParser(TokenType.SUB))
        putInfix(TokenType.ASSIGNMUL, CompoundAssignOperatorParser(TokenType.MUL))
        putInfix(TokenType.ASSIGNDIV, CompoundAssignOperatorParser(TokenType.DIV))
        putInfix(TokenType.ASSIGNMOD, CompoundAssignOperatorParser(TokenType.MOD))
        putInfix(TokenType.ASSIGNXOR, CompoundAssignOperatorParser(TokenType.XOR))
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
        putPrefix(TokenType.CASE, object : PrefixParselet {
            override fun parse(parser: Parser, token: Token): Expression = parser.parseCaseExpression(Position(token.line, token.col))
        })
        putInfix(TokenType.WITH, object : InfixParselet {
            override val precedence: Int get() = 1
            override fun parse(parser: Parser, left: Expression, token: Token): Expression {
                parser.consume(TokenType.LBRACE, "Expected '{' after with.")
                val fields = arrayListOf<Pair<Token, Expression>>()
                while (!parser.check(TokenType.RBRACE)) {
                    val name = parser.consume(TokenType.IDENTIFIER, "Expected field name.")
                    parser.consume(TokenType.ASSIGN, "Expected '=' in update.")
                    fields.add(name to parser.expression())
                    if (!parser.match(TokenType.COMMA)) break
                }
                parser.consume(TokenType.RBRACE, "Expected '}' after update.")
                return UpdateExpression(Position(token.line, token.col), left, fields)
            }
        })

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
        infixLeft(TokenType.MOD, Precedence.PRODUCT)
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
        val pos = Position(peek().line, peek().col)
        if (check(TokenType.RBRACE) || check(TokenType.EOF)) error(peek(), "Return requires an expression; use return ().")
        return ReturnStatement(pos, expression())
    }

    /**
     * Parses a type alias statement (struct, enum, or type alias)
     * @return The parsed type alias statement
     */
    private fun parseTypeAlias(): Statement {
        val pos = this.pos
        val name = consume(TokenType.IDENTIFIER, "Expected type name.")
        val declaredGenerics = parseTypeParameters()
        consume(TokenType.ASSIGN, "Expected assignment operator.")
        if (match(TokenType.STRUCT)) {
            // The legacy spelling remains parseable during the staged migration.
            val generics = if (check(TokenType.LBRACK)) parseTypeParameters() else declaredGenerics
            consume(TokenType.LBRACE, "Expected '{' before struct fields.")
            val fields = arrayListOf<ParameterStatement>()
            while (!check(TokenType.RBRACE)) {
                fields.add(parseParameter())
                optional(TokenType.COMMA)
            }
            consume(TokenType.RBRACE, "Expected '}' after struct fields.")
            return StructStatement(pos, name, fields, generics.toMutableList())
        }
        if (match(TokenType.ENUM)) {
            consume(TokenType.LBRACE, "Expected '{' before enum variants.")
            val variants = arrayListOf<VariantDeclaration>()
            while (!check(TokenType.RBRACE)) {
                val variant = consume(TokenType.IDENTIFIER, "Expected variant name.")
                val fields = arrayListOf<ParameterStatement>()
                if (match(TokenType.LPAREN)) {
                    if (check(TokenType.RPAREN)) error(peek(), "Declare a nullary variant without parentheses.")
                    while (!check(TokenType.RPAREN)) {
                        fields.add(parseParameter())
                        if (!match(TokenType.COMMA)) break
                    }
                    consume(TokenType.RPAREN, "Expected ')' after variant fields.")
                }
                variants.add(VariantDeclaration(variant, fields))
                if (!match(TokenType.COMMA)) break
            }
            consume(TokenType.RBRACE, "Expected '}' after enum variants.")
            if (variants.isEmpty()) error(name, "An enum must declare at least one variant.")
            return EnumStatement(pos, name, variants.map { it.name }.toMutableList(), declaredGenerics, variants)
        }
        return TypeAliasStatement(pos, name, parseUnion(), declaredGenerics)
    }

    private fun parseTypeParameters(): List<Token> {
        if (!match(TokenType.LBRACK)) return emptyList()
        val names = arrayListOf<Token>()
        do { names.add(consume(TokenType.IDENTIFIER, "Expected type parameter.")) }
        while (match(TokenType.COMMA) && !check(TokenType.RBRACK))
        consume(TokenType.RBRACK, "Expected ']' after type parameters.")
        return names
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
                    val start = peek()
                    val name = consume(TokenType.IDENTIFIER, "Expected lambda parameter.")
                    val type = if (match(TokenType.COLON)) parseUnion() else null
                    parameters.add(ParameterStatement(Position(start.line, start.col), name, type))
                } while (match(TokenType.COMMA) && !check(TokenType.RPAREN))
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

        return LambdaExpression(pos, parameters, returnType ?: TypeStatement.voidInstance(pos), body, returnType == null)
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

        val generics = parseTypeParameters().toMutableList()

        consume(TokenType.LPAREN, "Expected opening parentheses after function name.")

        val parameters: MutableList<ParameterStatement> = ArrayList()
        if (!check(TokenType.RPAREN)) {
            do {
                parameters.add(parseParameter())
            } while (match(TokenType.COMMA) && !check(TokenType.RPAREN))
        }

        consume(TokenType.RPAREN, "Expected closing parentheses after function parameters.")

        var returnType: TypeStatement? = null
        if (match(TokenType.COLON)) {
            returnType = parseUnion()
        }

        val block = parseBlock()

        return DefStatement(pos, name, parameters, returnType, block, generics)
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
    fun parseCaseExpression(pos: Position): CaseExpression {
        val subject = expression()
        consume(TokenType.LBRACE, "Expected '{' before case branches.")
        val branches = arrayListOf<CaseBranch>()
        while (!check(TokenType.RBRACE) && notAtEnd()) {
            val pattern = parsePattern()
            val guard = if (match(TokenType.IF)) expression() else null
            consume(TokenType.ARROW, "Expected '=>' after pattern.")
            val body = if (check(TokenType.LBRACE)) {
                val block = parseBlock()
                val last = block.statements.lastOrNull()
                val result = if (last is ExpressionStatement && last.expression !is AssignExpression) {
                    block.statements.removeAt(block.statements.lastIndex)
                    last.expression
                } else null
                BlockExpression(block.position!!, block, result)
            } else if (match(TokenType.RETURN)) {
                BlockExpression(pos, BlockStatement(pos, mutableListOf(parseReturn()), Context()), null)
            } else {
                val value = expression()
                if (value is AssignExpression) BlockExpression(pos, BlockStatement(pos, mutableListOf(ExpressionStatement(value.position, value)), Context()), null)
                else BlockExpression(pos, BlockStatement(pos, arrayListOf(), Context()), value)
            }
            branches.add(CaseBranch(pattern, guard, body))
            // Existing type-match fixtures omit commas; new source should use commas.
            optional(TokenType.COMMA)
        }
        consume(TokenType.RBRACE, "Expected '}' after case branches.")
        return CaseExpression(pos, subject, branches)
    }

    private fun parenthesizedTypePattern(): Pattern? {
        val consumed = arrayListOf<Token>()
        val start = peek()
        var result: Pattern? = null
        speculativeTokens = consumed
        try {
            val type = parseUnion()
            if (check(TokenType.IDENTIFIER)) {
                val binding = consume()
                if (check(TokenType.ARROW) || check(TokenType.IF)) result = TypePattern(Position(start.line, start.col), type, binding)
            }
        } catch (_: com.kingmang.ixion.runtime.IxionExitException) {
            // The same tokens are retried as a tuple/grouping pattern.
        } finally {
            speculativeTokens = null
            if (result == null) mRead.addAll(0, consumed)
        }
        return result
    }

    private fun parsePattern(): Pattern {
        val start = peek()
        val pos = Position(start.line, start.col)
        if (check(TokenType.LPAREN) || check(TokenType.NEVER)) parenthesizedTypePattern()?.let {
            it.end = Position(peek().line, peek().col)
            return it
        }
        val pattern: Pattern = when {
            match(TokenType.DEFAULT) -> WildcardPattern(pos)
            match(TokenType.LBRACK) -> {
                val elements = arrayListOf<Pattern>()
                var rest: Pattern? = null
                while (!check(TokenType.RBRACK)) {
                    if (match(TokenType.RANGE)) {
                        rest = if (match(TokenType.DEFAULT)) WildcardPattern(pos)
                            else BindingPattern(consume(TokenType.IDENTIFIER, "Expected rest binding."))
                        optional(TokenType.COMMA)
                        break
                    }
                    elements.add(parsePattern())
                    if (!match(TokenType.COMMA)) break
                }
                consume(TokenType.RBRACK, "A rest pattern must be last in a list pattern.")
                ListPattern(pos, elements, rest)
            }
            match(TokenType.LPAREN) -> {
                if (match(TokenType.RPAREN)) LiteralPattern(pos, UnitExpression(pos))
                else {
                    val first = parsePattern()
                    if (!match(TokenType.COMMA)) {
                        consume(TokenType.RPAREN, "Expected ')'.")
                        first
                    } else {
                        val elements = arrayListOf(first)
                        while (!check(TokenType.RPAREN)) {
                            elements.add(parsePattern())
                            if (!match(TokenType.COMMA)) break
                        }
                        consume(TokenType.RPAREN, "Expected ')' after tuple pattern.")
                        TuplePattern(pos, elements)
                    }
                }
            }
            start.type in setOf(TokenType.INT, TokenType.FLOAT, TokenType.DOUBLE, TokenType.STRING, TokenType.CHAR, TokenType.TRUE, TokenType.FALSE) || check(TokenType.SUB) -> {
                val negative = match(TokenType.SUB)
                val token = consume()
                if (negative && token.type !in setOf(TokenType.INT, TokenType.FLOAT, TokenType.DOUBLE)) error(token, "Expected negative numeric literal.")
                LiteralPattern(pos, LiteralExpression(pos, if (negative) Token(token.type, token.line, token.col, "-" + token.source) else token))
            }
            else -> {
                val names = arrayListOf(consume(TokenType.IDENTIFIER, "Expected pattern."))
                while (match(TokenType.MODULE) || match(TokenType.DOT)) names.add(consume(TokenType.IDENTIFIER, "Expected qualified name."))
                val args = if (check(TokenType.LBRACK) && lookAhead(1).type != TokenType.RBRACK) parseTypeArguments() else emptyList()
                var listDepth = 0
                while (check(TokenType.LBRACK) && lookAhead(1).type == TokenType.RBRACK) { consume(); consume(); listDepth++ }
                if (match(TokenType.MODULE)) names.add(consume(TokenType.IDENTIFIER, "Expected variant name."))
                fun named(parts: List<Token>): TypeStatement {
                    var type = TypeStatement(pos, Token(TokenType.IDENTIFIER, pos.line, pos.col, parts.joinToString("::") { it.source }), Optional.empty(), false, args)
                    repeat(listDepth) { type = TypeStatement(pos, null, Optional.empty(), false, form = TypeForm.LIST, components = listOf(type)) }
                    return type
                }
                when {
                    check(TokenType.IDENTIFIER) -> TypePattern(pos, named(names), consume())
                    match(TokenType.LBRACE) -> {
                        val fields = arrayListOf<Pair<Token, Pattern>>()
                        while (!check(TokenType.RBRACE)) {
                            val field = consume(TokenType.IDENTIFIER, "Expected field name.")
                            consume(TokenType.COLON, "Expected ':' in field pattern.")
                            fields.add(field to parsePattern())
                            if (!match(TokenType.COMMA)) break
                        }
                        consume(TokenType.RBRACE, "Expected '}' after struct pattern.")
                        StructPattern(pos, named(names), fields)
                    }
                    names.size > 1 -> {
                        val fields = arrayListOf<Pattern>()
                        if (match(TokenType.LPAREN)) {
                            if (!check(TokenType.RPAREN)) do { fields.add(parsePattern()) }
                            while (match(TokenType.COMMA) && !check(TokenType.RPAREN))
                            consume(TokenType.RPAREN, "Expected ')' after constructor pattern.")
                        }
                        ConstructorPattern(pos, named(names.dropLast(1)), names.last(), fields)
                    }
                    else -> {
                        if (names.single().source in setOf("int", "float", "double", "string", "char", "bool", "any"))
                            error(start, "Expected name for reified value before `=>` in case statement.")
                        BindingPattern(names.single())
                    }
                }
            }
        }
        pattern.end = Position(peek().line, peek().col)
        return pattern
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
            match(TokenType.DEF) -> parseFunction()
            match(TokenType.IF) -> parseIf()
            check(TokenType.LET) -> parseVariable()
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
    fun parseType(): TypeStatement {
        val token = peek()
        val pos = Position(token.line, token.col)
        var type: TypeStatement
        if (match(TokenType.LPAREN)) {
            val parts = arrayListOf<TypeStatement>()
            var tuple = false
            if (!check(TokenType.RPAREN)) {
                parts.add(parseUnion())
                while (match(TokenType.COMMA)) {
                    tuple = true
                    if (check(TokenType.RPAREN)) break
                    parts.add(parseUnion())
                }
            }
            consume(TokenType.RPAREN, "Expected ')' in type.")
            type = if (match(TokenType.ARROW)) TypeStatement(pos, null, Optional.empty(), false, form = TypeForm.FUNCTION, components = parts, result = parseUnion())
                else if (parts.size == 1 && !tuple) parts.single()
                else TypeStatement(pos, null, Optional.empty(), false, form = TypeForm.TUPLE, components = parts)
        } else {
            val names = arrayListOf(if (match(TokenType.NEVER)) Token(TokenType.IDENTIFIER, token.line, token.col, "never")
                else consume(TokenType.IDENTIFIER, "Expected type name."))
            while (match(TokenType.MODULE) || match(TokenType.DOT)) names.add(consume(TokenType.IDENTIFIER, "Expected qualified type name."))
            val args = if (check(TokenType.LBRACK) && lookAhead(1).type != TokenType.RBRACK) parseTypeArguments() else emptyList()
            type = TypeStatement(pos, Token(TokenType.IDENTIFIER, pos.line, pos.col, names.joinToString("::") { it.source }), Optional.empty(), false, args)
        }
        while (check(TokenType.LBRACK) && lookAhead(1).type == TokenType.RBRACK) {
            consume(); consume()
            type = TypeStatement(pos, null, Optional.empty(), false, form = TypeForm.LIST, components = listOf(type))
        }
        return type
    }

    fun parseUnion(): TypeStatement {
        val pos = Position(peek().line, peek().col)
        val types = arrayListOf(parseType())
        while (match(TokenType.PIPE)) types.add(parseType())
        return if (types.size == 1) types.single() else UnionTypeStatement(pos, types)
    }

    fun parseTypeArguments(): List<TypeStatement> {
        consume(TokenType.LBRACK, "Expected '[' before type arguments.")
        val types = arrayListOf<TypeStatement>()
        do { types.add(parseUnion()) } while (match(TokenType.COMMA) && !check(TokenType.RBRACK))
        consume(TokenType.RBRACK, "Expected ']' after type arguments.")
        return types
    }

    fun isTypeApplication(): Boolean {
        var depth = 1
        var offset = 0
        while (depth > 0 && lookAhead(offset).type != TokenType.EOF) {
            when (lookAhead(offset).type) { TokenType.LBRACK -> depth++; TokenType.RBRACK -> depth--; else -> Unit }
            offset++
        }
        return depth == 0 && lookAhead(offset).type in setOf(TokenType.LPAREN, TokenType.MODULE)
    }

    /**
     * Parses a variable declaration
     * @return The parsed variable statement
     */
    private fun parseVariable(): VariableStatement {
        val pos = this.pos
        consume()
        val mutable = match(TokenType.MUT)
        val name = consume(TokenType.IDENTIFIER, "Expected variable name.")
        val type: Optional<TypeStatement> = if (match(TokenType.COLON)) {
            Optional.of(parseUnion())
        } else {
            Optional.empty()
        }
        consume(TokenType.ASSIGN, "Expected assignment operator.")
        val expression = expression()
        return VariableStatement(pos, mutable, name, expression, type)
    }


    /**
     * Consumes the current token
     * @return The consumed token
     */
    fun consume(): Token {
        lookAhead()
        return mRead.removeFirst().also { speculativeTokens?.add(it) }
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
