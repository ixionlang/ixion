package com.kingmang.ixion.lexer

import com.kingmang.ixion.lexer.TokenType.Companion.find
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.PushbackReader
import java.lang.String
import kotlin.Boolean
import kotlin.Char
import kotlin.Int
import kotlin.charArrayOf
import kotlin.code

class LexerImpl(file: File) : Lexer {
    private val sb = StringBuilder()
    private val reader: PushbackReader = PushbackReader(FileReader(file), 5)
    private var line: Int = 1
    private var col: Int = 1

    override fun tokenize(): Token {
        skipWhitespace()

        val startLine = line
        val startCol = col
        val currentChar = peek()

        if (currentChar == '\u0000') {
            return Token(TokenType.EOF, startLine, startCol, "")
        }

        if (isAlpha(currentChar)) {
            return consumeIdentifierToken(startLine, startCol)
        }

        if (isDigit(currentChar)) {
            return consumeNumberToken(startLine, startCol)
        }

        if (currentChar == '"') {
            return consumeStringToken(startLine, startCol)
        }

        if (currentChar == '\'') {
            return consumeCharToken(startLine, startCol)
        }

        return consumeOperatorToken(startLine, startCol)
    }

    override fun position(): Position =
        Position(this.line, this.col)

    private fun skipWhitespace() {
        while (true) {
            val currentChar = peek()

            when (currentChar) {
                ' ', '\r', '\t', '\n' -> advance()
                '/' -> handleComment()
                else -> return
            }
        }
    }

    private fun handleComment() {
        val nextChar = peekNext()

        if (nextChar == '/') {
            skipSingleLineComment()
        } else if (nextChar == '*') {
            skipMultiLineComment()
        }
    }


    private fun skipSingleLineComment() {
        advance()
        advance()

        while (peek() != '\n' && peek() != '\u0000') {
            advance()
        }
    }

    private fun skipMultiLineComment() {
        advance()
        advance()

        var nextTwoChars: kotlin.String
        do {
            advance()
            nextTwoChars = peek().toString() + peekNext()
        } while (nextTwoChars != "*/")

        advance()
        advance()
    }

    private fun consumeCharToken(line: Int, col: Int): Token {
        advance()

        val currentChar = peek()
        val charValue: Char

        if (currentChar == '\\') {
            advance()
            val escapeChar = peek()
            charValue = when (escapeChar) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'f' -> '\u000c'
                '\'' -> '\''
                '\\' -> '\\'
                '0' -> '\u0000'
                else -> {
                    advance()
                    '\\'
                }
            }
            advance()
        } else {
            charValue = currentChar
            advance()
        }

        if (peek() != '\'') {
            return Token(TokenType.ERROR, line, col, "Unterminated character literal")
        }

        advance()

        return Token(TokenType.CHAR, line, col, charValue.toString())
    }

    private fun advance(): Char {
        try {
            val charCode = reader.read()
            if (charCode == -1) return '\u0000'

            if (charCode == '\n'.code) {
                line++
                col = 1
            } else {
                col++
            }

            return charCode.toChar()
        } catch (e: IOException) {
            e.printStackTrace()
            return '\u0000'
        }
    }

    private fun clearStringBuilder(): kotlin.String {
        val result = sb.toString()
        sb.setLength(0)
        return result
    }

    private fun consumeIdentifierToken(line: Int, col: Int): Token {
        val identifier = consumeIdentifier()
        var tokenType: TokenType? = TokenType.IDENTIFIER

        val keywordType = find(identifier)
        if (keywordType != null) {
            tokenType = keywordType
        }

        return Token(tokenType!!, line, col, identifier)
    }

    private fun consumeIdentifier(): kotlin.String {
        while (isAlphaNumeric(peek())) {
            sb.append(advance())
        }
        return clearStringBuilder()
    }


    private fun consumeNumberToken(line: Int, col: Int): Token {
        var type = TokenType.INT

        consumeIntegerPart()

        if (peek() == '.') {
            type = TokenType.FLOAT
            consumeDecimalPart()
        }

        consumeFloatSuffix()
        consumeExponentPart()
        consumeDoubleSuffix()

        val value = clearStringBuilder()
        return Token(type, line, col, value)
    }


    private fun consumeIntegerPart() {
        while (isDigit(peek())) {
            sb.append(advance())
        }
    }


    private fun consumeDecimalPart() {
        sb.append(advance()) // consume '.'

        while (isDigit(peek())) {
            sb.append(advance())
        }
    }


    private fun consumeFloatSuffix() {
        if (peek() == 'f') {
            sb.append(advance())
        }
    }


    private fun consumeExponentPart() {
        var currentChar = peek()
        if (currentChar == 'e' || currentChar == 'E') {
            sb.append(advance())

            currentChar = peek()
            if (currentChar == '-' || currentChar == '+') {
                sb.append(advance())
            }

            while (isDigit(peek())) {
                sb.append(advance())
            }
        }
    }


    private fun consumeDoubleSuffix() {
        if (peek() == 'd') {
            sb.append(advance())
        }
    }

    private fun consumeStringToken(line: Int, col: Int): Token {
        advance()

        var currentChar = peek()
        while (currentChar != '"' && currentChar != '\u0000') {
            sb.append(advance())
            currentChar = peek()
        }

        advance()

        val stringLiteral = clearStringBuilder()
        val escapedString: kotlin.String = StringEscapeUtils.unescapeJava(stringLiteral)

        return Token(TokenType.STRING, line, col, escapedString)
    }

    private fun consumeOperatorToken(line: Int, col: Int): Token {
        val currentChar = peek()
        val nextChar = peekNext()
        val twoCharOperator = String.valueOf(charArrayOf(currentChar, nextChar))

        val longToken = find(twoCharOperator)
        val shortToken = find(currentChar.toString())

        if (longToken != null) {
            advance()
            advance()
            return Token(longToken, line, col, twoCharOperator)
        }

        if (shortToken != null) {
            advance()
            return Token(shortToken, line, col, currentChar.toString())
        }

        advance()
        advance()
        return Token(TokenType.ERROR, line, col, twoCharOperator)
    }

    private fun isAlpha(c: Char): Boolean {
        return Character.isLetter(c) || c == '_'
    }

    private fun isAlphaNumeric(c: Char): Boolean {
        return isAlpha(c) || isDigit(c)
    }

    private fun isDigit(c: Char): Boolean {
        return c in '0'..'9'
    }


    private fun peek(): Char {
        try {
            val charCode = reader.read()
            if (charCode == -1) return '\u0000'

            reader.unread(charCode)
            return charCode.toChar()
        } catch (e: IOException) {
            e.printStackTrace()
            return '\u0000'
        }
    }

    private fun peekNext(): Char {
        try {
            val firstChar = reader.read()
            val secondChar = reader.read()

            if (secondChar == -1) return '\u0000'

            reader.unread(secondChar)
            reader.unread(firstChar)

            return secondChar.toChar()
        } catch (e: IOException) {
            e.printStackTrace()
            return '\u0000'
        }
    }
}