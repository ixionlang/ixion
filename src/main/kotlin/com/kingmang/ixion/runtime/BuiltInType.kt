package com.kingmang.ixion.runtime

import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.lexer.TokenType
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.GeneratorAdapter
import java.io.Serializable

enum class BuiltInType(
    name: String,
    typeClass: Class<*>,
    descriptor: String,
    opcodes: TypeSpecificOpcodes,
    defaultValue: Any?,
    isNumeric: Boolean
) : IxType, Serializable {
    BOOLEAN(
        "bool",
        Boolean::class.java,
        "Z",
        TypeSpecificOpcodes.INT,
        false,
        false
    ),

    CHAR(
        "char",
        Char::class.java,
        "C",
        TypeSpecificOpcodes.INT,
        '\u0000',
        true
    ),

    INT(
        "int",
        Int::class.java,
        "I",
        TypeSpecificOpcodes.INT,
        0,
        true
    ),

    FLOAT(
        "float",
        Float::class.java,
        "F",
        TypeSpecificOpcodes.FLOAT,
        0.0f,
        true
    ),

    DOUBLE(
        "double",
        Double::class.java,
        "D",
        TypeSpecificOpcodes.DOUBLE,
        0.0,
        true
    ),

    STRING(
        "string",
        String::class.java,
        "Ljava/lang/String;",
        TypeSpecificOpcodes.OBJECT,
        "",
        false
    ),

    VOID(
        "void",
        Void.TYPE,
        "V",
        TypeSpecificOpcodes.VOID,
        null,
        false
    ),

    ANY(
        "any",
        Any::class.java,
        "Ljava/lang/Object;",
        TypeSpecificOpcodes.OBJECT,
        false,
        false
    );

    private val namee: String?
    override val typeClass: Class<*>?
    override val descriptor: String?
    private val opcodes: TypeSpecificOpcodes
    override val defaultValue: Any?
    override val isNumeric: Boolean

    init {
        this.namee = name
        this.typeClass = typeClass
        this.descriptor = descriptor
        this.opcodes = opcodes
        this.defaultValue = defaultValue
        this.isNumeric = isNumeric
    }

    fun doBoxing(mv: MethodVisitor) {
        when (this) {
            INT -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Integer",
                "valueOf",
                "(I)Ljava/lang/Integer;",
                false
            )

            FLOAT -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Float",
                "valueOf",
                "(F)Ljava/lang/Float;",
                false
            )

            DOUBLE -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Double",
                "valueOf",
                "(D)Ljava/lang/Double;",
                false
            )

            BOOLEAN -> mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/Boolean",
                "valueOf",
                "(Z)Ljava/lang/Boolean;",
                false
            )

            STRING -> {}
            ANY -> {}
            else -> System.err.println("Boxing isn't supported for that type.")
        }
    }

    fun doUnboxing(mv: MethodVisitor) {
        when (this) {
            INT -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
            }

            CHAR -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false)
            }

            FLOAT -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Integer")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "floatValue", "()F", false)
            }

            DOUBLE -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Double")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
            }

            BOOLEAN -> {
                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
            }

            STRING -> mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
            else -> {
                IxApi.exit("Unboxing isn't supported for that type.", 29)
            }
        }
    }

    val addOpcode: Int
        get() = opcodes.add

    val divideOpcode: Int
        get() = opcodes.divide

    override val internalName: String?
        get() = descriptor

    override val loadVariableOpcode: Int
        get() = opcodes.load

    val multiplyOpcode: Int
        get() = opcodes.multiply

    val negOpcode: Int
        get() = opcodes.neg

    override val returnOpcode: Int
        get() = opcodes.`return`

    val subtractOpcode: Int
        get() = opcodes.subtract

    override fun kind(): String? {
        return null
    }

    fun pushOne(ga: GeneratorAdapter) {
        when (this) {
            INT, CHAR -> ga.push(1)
            FLOAT -> ga.push(1.0f)
            DOUBLE -> ga.push(1.0)
            else -> ga.push(0)
        }
    }

    override fun toString(): String {
        return name
    }

    fun unboxNoCheck(mv: MethodVisitor) {
        when (this) {
            INT -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false)
            CHAR -> mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Character",
                "charValue",
                "()C",
                false
            )

            FLOAT -> mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Float",
                "floatValue",
                "()F",
                false
            )

            DOUBLE -> mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Double",
                "doubleValue",
                "()D",
                false
            )

            BOOLEAN -> mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Boolean",
                "booleanValue",
                "()Z",
                false
            )

            else -> {}
        }
    }

    companion object {
        val widenings: MutableMap<BuiltInType?, Int?> = HashMap()

        fun getFromToken(tokenType: TokenType): BuiltInType? {
            return when (tokenType) {
                TokenType.TRUE, TokenType.FALSE -> BOOLEAN
                TokenType.STRING -> STRING
                TokenType.INT -> INT
                TokenType.FLOAT -> FLOAT
                TokenType.DOUBLE -> DOUBLE
                TokenType.CHAR -> CHAR
                else -> throw IllegalStateException("Unexpected value: $tokenType")
            }
        }
        init {
            widenings[BOOLEAN]  = -1
            widenings[CHAR]     = 0
            widenings[INT]      = 0
            widenings[FLOAT]    = 1
            widenings[DOUBLE]   = 2
            widenings[STRING]   = 10
        }

        fun widen(a: BuiltInType?, b: BuiltInType?): BuiltInType? {
            val priorityA: Int? = widenings[a]
            val priorityB: Int? = widenings[b]

            return when {
                priorityA == null && priorityB == null -> a
                priorityA == null -> b
                priorityB == null -> a
                priorityA > priorityB -> a
                else -> b
            }
        }
    }
}
