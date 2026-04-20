package com.kingmang.ixion.codegen

import com.kingmang.ixion.Visitor
import com.kingmang.ixion.api.Context
import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxApi.Companion.exit
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.api.IxionConstant.ArrayListType
import com.kingmang.ixion.api.IxionConstant.Init
import com.kingmang.ixion.api.IxionConstant.IteratorType
import com.kingmang.ixion.api.IxionConstant.ListWrapperType
import com.kingmang.ixion.api.IxionConstant.ObjectType
import com.kingmang.ixion.api.IxionConstant.PublicStatic
import com.kingmang.ixion.ast.*
import com.kingmang.ixion.exception.IdentifierNotFoundException
import com.kingmang.ixion.exception.ImplementationException
import com.kingmang.ixion.lexer.Token
import com.kingmang.ixion.lexer.TokenType
import com.kingmang.ixion.runtime.*
import com.kingmang.ixion.runtime.BuiltInType.Companion.getFromToken
import com.kingmang.ixion.runtime.BuiltInType.Companion.widenings
import com.kingmang.ixion.runtime.DefType.Companion.getSpecializedType
import com.kingmang.ixion.typechecker.TypeResolver
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.NotImplementedException
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.io.File
import java.util.*
import java.util.function.BiConsumer

class CodegenVisitor(val api: IxApi, val rootContext: Context?, val source: IxFile, cw: ClassWriter) : Visitor<Optional<ClassWriter>> {
    private val file: File
    private val cw: ClassWriter
    private val structWriters: MutableMap<StructType, ClassWriter> = HashMap()
    private val functionStack = Stack<DefType>()
    private var lambdaCounter = 0

    var currentContext: Context?

    init {
        this.currentContext = this.rootContext
        this.cw = cw
        this.file = source.file
    }

    fun getStructWriters(): Map<StructType, ClassWriter> {
        return this.structWriters
    }

    override fun visit(statement: Statement): Optional<ClassWriter> {
        return statement.accept(this)
    }

    /**
     * Обрабатывает объявление псевдонима типа.
     * Поскольку псевдонимы не требуют генерации байт-кода, метод возвращает пустой Optional.
     * @param statement Выражение псевдонима типа
     * @return Пустой Optional
     */
    override fun visitTypeAlias(statement: TypeAliasStatement): Optional<ClassWriter> {
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для операции присваивания значения переменной или полю объекта.
     * Для идентификаторов сохраняет значение в локальную переменную, для доступа к полям объекта - использует putField.
     * @param expression Выражение присваивания
     * @return Пустой Optional
     */
    override fun visitAssignExpr(expression: AssignExpression): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        val ga = funcType.ga

        when (expression.left) {
            is IdentifierExpression -> {
                expression.right.accept(this)
                val index = funcType.localMap[expression.left.identifier.source]
                ga!!.storeLocal(index!!)
            }

            is PropertyAccessExpression -> {
                val lType = expression.left.realType
                val rType = expression.right.realType

                val root: Expression = expression.left.expression
                root.accept(this)

                val typeChain: MutableList<IxType?> = expression.left.typeChain
                val identifiers: MutableList<IdentifierExpression> = expression.left.identifiers
                for (i in 0..<typeChain.size - 2) {
                    val current = typeChain[i]
                    val next = typeChain[i + 1]
                    val fieldName = identifiers[i].identifier.source
                    ga!!.getField(Type.getType(current!!.descriptor), fieldName, Type.getType(next!!.descriptor))
                }

                expression.right.accept(this)

                if (rType is BuiltInType) {
                    if (lType is UnionType) {
                        rType.doBoxing(ga!!)
                    }
                }

                ga!!.putField(
                    Type.getType(typeChain[typeChain.size - 2]!!.descriptor),
                    identifiers[identifiers.size - 1].identifier.source,
                    Type.getType(typeChain[typeChain.size - 1]!!.descriptor)
                )
            }

            else -> {
                ImplementationException().send(
                    api,
                    file,
                    expression,
                    "Assignment not implemented for any recipient but identifier yet"
                )
            }
        }
        return Optional.empty()
    }

    /**
     * Обрабатывает некорректное выражение. Возвращает пустой результат, так как такое выражение не должно встречаться на этапе генерации кода.
     * @param expression Некорректное выражение
     * @return Пустой Optional
     */
    override fun visitBad(expression: BadExpression): Optional<ClassWriter> {
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для бинарных операций, включая арифметические, сравнения, логические операции и конкатенацию строк.
     * Для строк используется StringBuilder, для логических операций - short-circuit evaluation.
     * @param expression Бинарное выражение
     * @return Пустой Optional
     */
    override fun visitBinaryExpr(expression: BinaryExpression): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        val ga = funcType.ga
        val left = expression.left
        val right = expression.right
        if (expression.realType == BuiltInType.STRING) {
            ga!!.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder")
            ga.visitInsn(Opcodes.DUP)
            ga.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", Init, "()V", false)

            expression.left.accept(this)

            val leftExprDescriptor = expression.left.realType.descriptor
            var descriptor = "($leftExprDescriptor)Ljava/lang/StringBuilder;"
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", descriptor, false)

            expression.right.accept(this)

            val rightExprDescriptor: String = expression.right.realType.descriptor!!
            descriptor = "($rightExprDescriptor)Ljava/lang/StringBuilder;"
            ga.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", descriptor, false)
            ga.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuilder",
                "toString",
                "()Ljava/lang/String;",
                false
            )
        } else {
            when (expression.operator.type) {
                TokenType.AND -> {
                    val falseLabel = Label()
                    val successLabel = Label()

                    left.accept(this)
                    ga!!.ifZCmp(GeneratorAdapter.EQ, falseLabel)

                    right.accept(this)
                    ga.ifZCmp(GeneratorAdapter.EQ, falseLabel)
                    ga.push(true)
                    ga.goTo(successLabel)

                    ga.mark(falseLabel)
                    ga.push(false)

                    ga.mark(successLabel)
                }

                TokenType.OR -> {
                    val falseLabel = Label()
                    val successLabel = Label()
                    val endLabel = Label()

                    left.accept(this)
                    ga!!.ifZCmp(GeneratorAdapter.NE, successLabel)

                    right.accept(this)
                    ga.ifZCmp(GeneratorAdapter.NE, successLabel)
                    ga.goTo(falseLabel)

                    ga.mark(successLabel)
                    ga.push(true)
                    ga.goTo(endLabel)

                    ga.mark(falseLabel)
                    ga.push(false)

                    ga.mark(endLabel)
                }

                TokenType.XOR -> {
                    left.accept(this)
                    right.accept(this)
                    ga!!.visitInsn(Opcodes.IXOR)
                }

                TokenType.EQUAL, TokenType.NOTEQUAL, TokenType.LT, TokenType.GT, TokenType.LE, TokenType.GE -> {
                    val cmpType: Type? = castAndAccept(ga!!, left, right, this)

                    val endLabel = Label()
                    val falseLabel = Label()

                    val opcode = when (expression.operator.type) {
                        TokenType.EQUAL -> GeneratorAdapter.NE
                        TokenType.NOTEQUAL -> GeneratorAdapter.EQ
                        TokenType.LT -> GeneratorAdapter.GT
                        TokenType.GT -> GeneratorAdapter.LT
                        TokenType.LE -> GeneratorAdapter.GE
                        TokenType.GE -> GeneratorAdapter.LE
                        else -> throw IllegalStateException("Unexpected value: " + expression.operator.type)
                    }
                    ga.ifCmp(cmpType, opcode, falseLabel)
                    ga.push(true)
                    ga.goTo(endLabel)

                    ga.mark(falseLabel)
                    ga.push(false)
                    ga.mark(endLabel)
                }

                TokenType.MOD -> {
                    val cmpType: Type? = castAndAccept(ga!!, left, right, this)

                    if (cmpType === Type.DOUBLE_TYPE) {
                        ga.visitInsn(Opcodes.DREM)
                    } else if (cmpType === Type.INT_TYPE) {
                        ga.visitInsn(Opcodes.IREM)
                    } else if (cmpType === Type.FLOAT_TYPE) {
                        ga.visitInsn(Opcodes.FREM)
                    }
                }

                TokenType.POW -> {
                }

                TokenType.ADD, TokenType.SUB, TokenType.MUL, TokenType.DIV -> arithmetic(
                    ga!!,
                    left,
                    right,
                    expression.operator,
                    expression.realType,
                    this
                )

                else -> {}
            }
        }
        return Optional.empty()
    }

    /**
     * Обрабатывает блок операторов, посещая каждый оператор в блоке последовательно.
     * @param statement Блок операторов
     * @return Пустой Optional
     */
    override fun visitBlockStmt(statement: BlockStatement): Optional<ClassWriter> {
        for (stmt in statement.statements) stmt!!.accept(this)
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для вызова функции или создания экземпляра структуры.
     * Обрабатывает как встроенные (glue) функции, так и пользовательские, включая специализацию дженериков.
     * @param expression Выражение вызова
     * @return Пустой Optional
     */
    override fun visitCall(expression: CallExpression): Optional<ClassWriter> {
        val funcType = functionStack.peek()

        if (expression.item is IdentifierExpression) {
            expression.item.realType = currentContext!!.getVariable(expression.item.identifier.source)!!
        }

        if (expression.item.realType is DefType) {
            val callType = expression.item.realType as DefType
            if (callType.glue) {
                val owner: String? = callType.owner
                var name: String = callType.name
                if (callType.isPrefixed) name = "_$name"

                val params =
                    callType.parameters
                        .stream()
                        .map { Pair(it!!.second.name!!, it.second) }
                        .toList()

                val returnType: IxType = callType.returnType
                val methodDescriptor = CollectionUtil.getMethodDescriptor(params, returnType)

                CollectionUtil.zip(
                    params,
                    expression.arguments,
                    BiConsumer { param: Pair<String?, IxType>?, arg: Expression? ->
                        arg!!.accept(this)
                        if (arg.realType is BuiltInType) {
                            if (param!!.second is ExternalType && (param.second as ExternalType).foundClass == Any::class.java) {
                                (arg.realType as BuiltInType).doBoxing(funcType.ga!!)
                            }
                        }
                    })

                funcType.ga!!.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, methodDescriptor, false)
            } else {
                val ga = funcType.ga!!
                CollectionUtil.zip(
                    callType.parameters,
                    expression.arguments,
                    BiConsumer { param: Pair<String, IxType>, arg: Expression? ->
                        arg!!.accept(this)
                        if (arg.realType is BuiltInType) {
                            when (param.second) {
                                is ExternalType -> if ((param.second as ExternalType).foundClass == Any::class.java) (arg.realType as BuiltInType).doBoxing(ga)
                                is UnionType-> (arg.realType as BuiltInType).doBoxing(ga)
                            }
                        }
                    })

                val specialization: MutableMap<String, IxType> = callType.buildSpecialization(expression.arguments)
                var returnType: IxType? = callType.returnType
                if (returnType is GenericType) {
                    returnType = getSpecializedType(specialization, returnType.key)
                }

                val parameters: MutableList<Pair<String, IxType>> =
                    callType.buildParametersFromSpecialization(specialization)

                val descriptor = CollectionUtil.getMethodDescriptor(parameters, returnType!!)
                val methodDescriptor: String = descriptor
                val name = "_" + callType.name
                var owner = FilenameUtils.removeExtension(source.fullRelativePath)

                if (callType.external != null) {
                    owner = callType.external!!.fullRelativePath
                }

                funcType.ga!!.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, methodDescriptor, false)
            }
        } else if (expression.item.realType is LambdaType) {
            val lambdaType = expression.item.realType as LambdaType
            val ga = funcType.ga!!

            expression.item.accept(this)
            CollectionUtil.zip(
                lambdaType.parameters,
                expression.arguments,
                BiConsumer { _: Pair<String, IxType>, arg: Expression? ->
                    arg!!.accept(this)
                    if (arg.realType is BuiltInType) {
                        (arg.realType as BuiltInType).doBoxing(ga)
                    }
                })

            ga.invokeInterface(
                Type.getType(lambdaType.descriptor),
                Method("apply", lambdaType.erasedApplyDescriptor())
            )

            when (val returnType = lambdaType.returnType) {
                is BuiltInType -> when (returnType) {
                    BuiltInType.VOID -> ga.pop()
                    BuiltInType.ANY -> {}
                    else -> returnType.doUnboxing(ga)
                }
                else -> ga.checkCast(Type.getType(returnType.descriptor))
            }
        } else if (expression.item.realType is StructType) {
            val st = expression.item.realType as StructType
            val ga = funcType.ga

            ga!!.newInstance(Type.getType("L" + st.qualifiedName + ";"))
            ga.visitInsn(Opcodes.DUP)

            val typeDescriptor = StringBuilder()
            CollectionUtil.zip(
                st.parameters,
                expression.arguments,
                BiConsumer { param: Pair<String, IxType>, arg: Expression? ->
                    arg!!.accept(this)
                    val paramType = param.second
                    if (paramType is UnionType || paramType is GenericType) {
                        typeDescriptor.append(paramType.descriptor)
                        if (arg.realType is BuiltInType) {
                            (arg.realType as BuiltInType).doBoxing(ga)
                        }
                    } else {
                        typeDescriptor.append(arg.realType.descriptor)
                    }
                })

            ga.invokeConstructor(
                Type.getType("L${st.qualifiedName};"),
                Method(Init, "($typeDescriptor)V")
            )
        } else {
            exit("Bad!", 43)
        }

        return Optional.empty()
    }

    /**
     * Обрабатывает пустое выражение. Не генерирует байт-код.
     * @param expression Пустое выражение
     * @return Пустой Optional
     */
    override fun visitEmpty(expression: EmptyExpression): Optional<ClassWriter> {
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для создания пустого списка, используя конструктор ArrayList.
     * @param expression Выражение пустого списка
     * @return Пустой Optional
     */
    override fun visitEmptyList(expression: EmptyListExpression): Optional<ClassWriter> {
        val ga = functionStack.peek().ga

        ga!!.newInstance(ArrayListType)
        ga.dup()

        ga.invokeConstructor(ArrayListType, Method(Init, "()V"))
        return Optional.empty()
    }

    /**
     * Обрабатывает объявление перечисления. В данный момент не реализовано.
     * @param statement Оператор перечисления
     * @return Выбрасывает NotImplementedException
     */
    override fun visitEnum(statement: EnumStatement): Optional<ClassWriter> {
        throw NotImplementedException("method not implemented")
    }

    /**
     * Обрабатывает экспорт оператора, посещая внутренний оператор.
     * @param statement Оператор экспорта
     * @return Результат посещения внутреннего оператора
     */
    override fun visitExport(statement: ExportStatement): Optional<ClassWriter> {
        return statement.stmt.accept(this)
    }

    /**
     * Генерирует байт-код для выражения, используемого как оператор.
     * @param statement Оператор выражения
     * @return Пустой Optional
     */
    override fun visitExpressionStmt(statement: ExpressionStatement): Optional<ClassWriter> {
        statement.expression.accept(this)
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для цикла for. Поддерживает итерацию по идентификатору или вызову, возвращающему итератор.
     * @param statement Оператор цикла for
     * @return Пустой Optional
     */
    override fun visitFor(statement: ForStatement): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        val ga = functionStack.peek().ga
        currentContext = statement.block.context
        val startLabel = Label()
        val endLabel = Label()

        if (statement.expression is IdentifierExpression) {
            ga!!.mark(startLabel)
            statement.expression.accept(this)
        } else {
            statement.expression.accept(this)
            statement.localExprIndex = ga!!.newLocal(IteratorType)
            funcType.localMap["______"] = statement.localExprIndex
            ga.storeLocal(statement.localExprIndex, IteratorType)
            ga.mark(startLabel)
            ga.loadLocal(statement.localExprIndex)
        }

        ga.invokeInterface(IteratorType, Method("hasNext", "()Z"))
        ga.visitJumpInsn(Opcodes.IFEQ, endLabel)

        if (statement.expression is IdentifierExpression) {
            statement.expression.accept(this)
        } else {
            ga.loadLocal(statement.localExprIndex)
        }
        ga.invokeInterface(IteratorType, Method("next", "()Ljava/lang/Object;"))
        BuiltInType.INT.doUnboxing(ga)
        statement.localExprIndex = ga.newLocal(Type.getType(BuiltInType.INT.descriptor))
        funcType.localMap[statement.name.source] = statement.localExprIndex
        funcType.ga!!.storeLocal(statement.localExprIndex, Type.getType(BuiltInType.INT.descriptor))

        statement.block.accept(this)

        ga.goTo(startLabel)
        ga.mark(endLabel)
        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для объявления функции, включая специализации для дженериков.
     * Создает метод в текущем ClassWriter с соответствующим дескриптором и генерирует тело функции.
     * @param statement Оператор объявления функции
     * @return Пустой Optional
     */
    override fun visitFunctionStmt(statement: DefStatement): Optional<ClassWriter> {
        val funcType = currentContext!!.getVariableTyped<DefType>(statement.name.source)
        functionStack.add(funcType)
        val childEnvironment = statement.body!!.context
        var name = "_" + funcType!!.name
        val access = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC

        if (funcType.hasGenerics()) {
            for (specialization in funcType.specializations) {
                funcType.currentSpecialization = specialization
                var returnType: IxType? = funcType.returnType
                if (returnType is GenericType) {
                    returnType = getSpecializedType(specialization, returnType.key)
                }

                val parameters = funcType.buildParametersFromSpecialization(specialization)

                val descriptor = CollectionUtil.getMethodDescriptor(parameters, returnType!!)

                val mv = cw.visitMethod(access, name, descriptor, null, null)
                funcType.ga = GeneratorAdapter(mv, access, name, descriptor)
                for (i in funcType.parameters.indices) {
                    val param: Pair<String?, IxType?> = funcType.parameters[i]
                    funcType.argMap[param.first] = i
                }

                currentContext = childEnvironment
                statement.body.accept(this)
                funcType.ga!!.endMethod()
                currentContext = currentContext!!.parent
            }
            functionStack.pop()
        } else {
            var descriptor = CollectionUtil.getMethodDescriptor(funcType.parameters, funcType.returnType)
            if (funcType.name == "main") {
                name = "main"
                descriptor = "([Ljava/lang/String;)V"
            }
            val mv = cw.visitMethod(access, name, descriptor, null, null)
            funcType.ga = GeneratorAdapter(mv, access, name, descriptor)
            for (i in funcType.parameters.indices) {
                val param: Pair<String?, IxType?> = funcType.parameters[i]
                funcType.argMap[param.first] = i
            }

            currentContext = childEnvironment
            statement.body.accept(this)
            funcType.ga!!.endMethod()
            currentContext = currentContext!!.parent
            functionStack.pop()
        }
        return Optional.empty()
    }

    /**
     * Обрабатывает группирующее выражение, посещая внутреннее выражение.
     * @param expression Группирующее выражение
     * @return Пустой Optional
     */
    override fun visitGroupingExpr(expression: GroupingExpression): Optional<ClassWriter> {
        expression.expression.accept(this)
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для загрузки значения идентификатора из локальной переменной или аргумента метода.
     * @param expression Выражение идентификатора
     * @return Пустой Optional
     */
    override fun visitIdentifierExpr(expression: IdentifierExpression): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        val ga = funcType.ga
        var type = currentContext!!.getVariable(expression.identifier.source)

        if (type is GenericType) {
            type = funcType.currentSpecialization!![type.key]
        }

        expression.realType = type!!

        val index: Int
        val source = expression.identifier.source
        if (funcType.localMap.containsKey(source)) {
            index = funcType.localMap[source]!!
            ga!!.loadLocal(index, Type.getType(type.descriptor))
        } else {
            index = funcType.argMap.getOrDefault(source, -1)!!
            if (index == -1) {
                IdentifierNotFoundException().send(api, file, expression, source)
                return Optional.empty()
            }
            ga!!.loadArg(index)
        }

        return Optional.empty()
    }

    /**
     * Генерирует байт-код для условного оператора if, включая ветку else если она присутствует.
     * @param statement Оператор if
     * @return Пустой Optional
     */
    override fun visitIf(statement: IfStatement): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        val ga = funcType.ga
        val endLabel = Label()
        val falseLabel = Label()

        statement.condition.accept(this)

        currentContext = statement.trueBlock.context
        ga!!.ifZCmp(GeneratorAdapter.EQ, falseLabel)
        statement.trueBlock.accept(this)
        ga.goTo(endLabel)
        ga.mark(falseLabel)
        statement.falseStatement?.accept(this)
        ga.mark(endLabel)
        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    /**
     * Обрабатывает оператор использования (use). Не требует генерации байт-кода.
     * @param statement Оператор use
     * @return Пустой Optional
     */
    override fun visitUse(statement: UseStatement): Optional<ClassWriter> {
        return Optional.empty()
    }

    /**
     * Обрабатывает доступ по индексу. В данный момент не реализовано.
     * @param expression Выражение доступа по индексу
     * @return Выбрасывает NotImplementedException
     */
    override fun visitIndexAccess(expression: IndexAccessExpression): Optional<ClassWriter> {
        throw NotImplementedException("method not implemented")
    }

    /**
     * Генерирует байт-код для литералов встроенных типов (int, float, double, boolean, string).
     * @param expression Выражение литерала
     * @return Пустой Optional
     */
    override fun visitLiteralExpr(expression: LiteralExpression): Optional<ClassWriter> {
        if (expression.realType is BuiltInType) {
            val transformed =
                TypeResolver.getValueFromString(expression.literal.source, getFromToken(expression.literal.type)!!)
            val ga = functionStack.peek().ga
            when (expression.realType) {
                BuiltInType.INT -> ga!!.push(transformed as Int)
                BuiltInType.FLOAT -> ga!!.push(transformed as Float)
                BuiltInType.DOUBLE -> ga!!.push(transformed as Double)
                BuiltInType.BOOLEAN -> ga!!.push(transformed as Boolean)
                BuiltInType.STRING -> ga!!.push(transformed as String?)
            }
        } else {
            ImplementationException().send(
                api,
                source.file,
                expression,
                "This should never happen. All literals should be builtin, for now."
            )
        }
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для создания списка литералов с помощью ListWrapper.
     * Каждый элемент добавляется через метод add ArrayList.
     * @param expression Выражение списка литералов
     * @return Пустой Optional
     */
    override fun visitLiteralList(expression: LiteralListExpression): Optional<ClassWriter> {
        val ga = functionStack.peek().ga

        ga!!.newInstance(ListWrapperType)
        ga.dup()
        ga.push((expression.realType as ListType).contentType.name)
        ga.invokeConstructor(ListWrapperType, Method(Init, "(Ljava/lang/String;)V"))
        ga.dup()
        ga.invokeVirtual(ListWrapperType, Method("list", "()Ljava/util/ArrayList;"))

        for (entry in expression.entries) {
            ga.dup()

            entry.accept(this)
            val rt = entry.realType
            if (rt is BuiltInType) {
                rt.doBoxing(ga)
            }
            ga.invokeVirtual(ArrayListType, Method("add", "(Ljava/lang/Object;)Z"))
            ga.pop()
        }
        ga.pop()
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для оператора match, преобразуя его в последовательность проверок instanceof и условных переходов.
     * Поддерживает сопоставление с типами ListType, BuiltInType и StructType.
     * @param statement Оператор match
     * @return Пустой Optional
     */
    override fun visitMatch(statement: CaseStatement): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        val ga = funcType.ga

        statement.expression.accept(this)
        val localExprIndex = ga!!.newLocal(ObjectType)
        val s = "bruh"
        funcType.localMap[s] = localExprIndex
        ga.storeLocal(localExprIndex)

        for (typeStmt in statement.cases.keys) {
            val pair = statement.cases[typeStmt]
            val scopedName = pair!!.first
            val block = pair.second
            var t = statement.types[typeStmt]
            if (t is UnknownType) {
                val attempt = currentContext!!.getVariable(t.typeName)
                if (attempt != null) {
                    t = attempt
                }
            }

            val end = Label()
            ga.loadLocal(localExprIndex)

            if (t is ListType) {
                ga.instanceOf(ListWrapperType)
                ga.visitJumpInsn(Opcodes.IFEQ, end)
                ga.loadLocal(localExprIndex)
                ga.checkCast(ListWrapperType)
                ga.storeLocal(localExprIndex)

                ga.loadLocal(localExprIndex)
                funcType.ga!!.invokeVirtual(ListWrapperType, Method("name", "()Ljava/lang/String;"))
                ga.push(t.contentType.name)
                funcType.ga!!.invokeVirtual(Type.getType(String::class.java), Method("equals", "(Ljava/lang/Object;)Z"))
                ga.visitJumpInsn(Opcodes.IFEQ, end)
                ga.loadLocal(localExprIndex)
            } else {
                val typeClass: Type? =
                    when (t) {
                        is BuiltInType -> Type.getType(t.typeClass)
                        is StructType -> Type.getType("L" + t.qualifiedName + ";")
                        else -> Type.getType(t!!.descriptor)
                    }
                ga.instanceOf(typeClass)
                ga.visitJumpInsn(Opcodes.IFEQ, end)
                ga.loadLocal(localExprIndex)
                ga.checkCast(typeClass)
            }

            if (t is BuiltInType && t.isNumeric) {
                t.unboxNoCheck(ga)

                val localPrimitiveType = ga.newLocal(Type.getType(CollectionUtil.convert(t.typeClass!!)))
                ga.storeLocal(localPrimitiveType)
                funcType.localMap[scopedName] = localPrimitiveType
            } else {
                val localObjectType = ga.newLocal(ObjectType)
                ga.storeLocal(localObjectType)
                funcType.localMap[scopedName] = localObjectType
            }

            currentContext = block.context
            block.accept(this)
            currentContext = currentContext!!.parent

            ga.mark(end)
        }

        return Optional.empty()
    }

    /**
     * Обрабатывает доступ к модулю. Не требует генерации байт-кода.
     * @param expression Выражение доступа к модулю
     * @return Пустой Optional
     */
    override fun visitModuleAccess(expression: ModuleAccessExpression): Optional<ClassWriter> {
        return Optional.empty()
    }

    /**
     * Обрабатывает оператор параметра. В данный момент не реализовано.
     * @param statement Оператор параметра
     * @return Выбрасывает NotImplementedException
     */
    override fun visitParameterStmt(statement: ParameterStatement): Optional<ClassWriter> {
        throw NotImplementedException("method not implemented")
    }

    /**
     * Генерирует байт-код для постфиксных операций (инкремент/декремент) над переменными встроенных типов.
     * @param expression Постфиксное выражение
     * @return Пустой Optional
     */
    override fun visitPostfixExpr(expression: PostfixExpression): Optional<ClassWriter> {
        val ga = functionStack.peek().ga!!
        expression.expression.accept(this)
        if (expression.realType is BuiltInType) {
            (expression.realType as BuiltInType).pushOne(ga)
            val op: Int = when (expression.operator.type) {
                TokenType.PLUSPLUS -> (expression.realType as BuiltInType).addOpcode
                TokenType.MINUSMINUS -> (expression.realType as BuiltInType).subtractOpcode
                else -> throw IllegalStateException("Unexpected value: " + expression.operator.type)
            }

            ga.visitInsn(op)
            if (expression.expression is IdentifierExpression) {
                ga.storeLocal(functionStack.peek().localMap[expression.expression.identifier.source]!!)
            }
        } else {
            exit("postfix only works with builtin types", 49)
        }
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для префиксных операций. В настоящее время поддерживает только унарный минус для числовых типов.
     * @param expression Префиксное выражение
     * @return Пустой Optional
     */
    override fun visitPrefix(expression: PrefixExpression): Optional<ClassWriter> {
        val ga = functionStack.peek().ga

        expression.right.accept(this)

        val t = expression.right.realType
        if (expression.operator.type == TokenType.SUB && t is BuiltInType) {
            ga!!.visitInsn(t.negOpcode)
            expression.realType = t
        }

        return Optional.empty()
    }

    /**
     * Генерирует байт-код для доступа к полям объекта, включая поддержку монотипизированных структур.
     * @param expression Выражение доступа к свойству
     * @return Пустой Optional
     */
    override fun visitPropertyAccess(expression: PropertyAccessExpression): Optional<ClassWriter> {
        val ga = functionStack.peek().ga

        val root = expression.expression
        val rootType = root.realType

        if (rootType is MonomorphizedStruct) {
            root.accept(this)

            for (i in 0..<expression.typeChain.size - 1) {
                val current = expression.typeChain[i]
                val next = expression.typeChain[i + 1]

                val key = (next as GenericType).key

                val r = rootType.resolved[key]

                val fieldName = expression.identifiers[i].identifier.source
                ga!!.getField(Type.getType(current!!.descriptor), fieldName, Type.getType(next.descriptor))

                ga.checkCast(Type.getType(r!!.descriptor))
            }
        } else {
            root.accept(this)

            for (i in 0..<expression.typeChain.size - 1) {
                val current = expression.typeChain[i]
                val next = expression.typeChain[i + 1]
                val fieldName = expression.identifiers[i].identifier.source
                ga!!.getField(Type.getType(current!!.descriptor), fieldName, Type.getType(next!!.descriptor))
            }
        }

        return Optional.empty()
    }

    /**
     * Обрабатывает лямбда-выражение. В данный момент не реализовано.
     * @param expression Лямбда-выражение
     * @return Пустой Optional
     */
    override fun visitLambda(expression: LambdaExpression): Optional<ClassWriter> {
        val lambdaType = expression.realType as LambdaType
        val owner = FilenameUtils.removeExtension(source.fullRelativePath)
        val lambdaMethodName = "lambda\$${lambdaCounter++}"
        val implementationReturnType = if (lambdaType.returnType == BuiltInType.VOID) {
            BuiltInType.ANY
        } else {
            lambdaType.returnType
        }

        val implementationDescriptor = CollectionUtil.getMethodDescriptor(lambdaType.parameters, implementationReturnType)
        val methodAccess = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC

        val mv = cw.visitMethod(methodAccess, lambdaMethodName, implementationDescriptor, null, null)
        val lambdaDef = DefType(lambdaMethodName, lambdaType.parameters)
        lambdaDef.returnType = implementationReturnType
        lambdaDef.bridgeVoidToObject = lambdaType.returnType == BuiltInType.VOID
        lambdaDef.ga = GeneratorAdapter(mv, methodAccess, lambdaMethodName, implementationDescriptor)
        for (i in lambdaType.parameters.indices) {
            val param = lambdaType.parameters[i]
            lambdaDef.argMap[param.first] = i
        }

        functionStack.add(lambdaDef)
        val previousContext = currentContext
        currentContext = expression.body.context
        expression.body.accept(this)
        lambdaDef.ga!!.endMethod()
        currentContext = previousContext
        functionStack.pop()

        val bsmHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false
        )
        val implementationHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            owner,
            lambdaMethodName,
            implementationDescriptor,
            false
        )
        functionStack.peek().ga!!.invokeDynamic(
            "apply",
            "()${lambdaType.descriptor}",
            bsmHandle,
            Type.getMethodType(lambdaType.erasedApplyDescriptor()),
            implementationHandle,
            Type.getMethodType(lambdaInstantiationDescriptor(lambdaType))
        )
        return Optional.empty()
    }

    /**
     * Обрабатывает доступ к перечислению. В данный момент не реализовано.
     * @param expression Выражение доступа к перечислению
     * @return Пустой Optional
     */
    override fun visitEnumAccess(expression: EnumAccessExpression): Optional<ClassWriter> {
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для оператора return, включая упаковку примитивных значений для функций с UnionType.
     * @param statement Оператор return
     * @return Пустой Optional
     */
    override fun visitReturnStmt(statement: ReturnStatement): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        if (statement.expression !is EmptyExpression) {
            statement.expression!!.accept(this)

            if (funcType.returnType is UnionType && statement.expression.realType is BuiltInType) {
                (statement.expression.realType as BuiltInType).doBoxing(funcType.ga!!)
            }

            var returnType: IxType? = funcType.returnType
            if (returnType is GenericType) {
                returnType = getSpecializedType(funcType.currentSpecialization!!, returnType.key)
            }

            funcType.ga!!.visitInsn(returnType!!.returnOpcode)
        } else {
            if (funcType.bridgeVoidToObject) {
                funcType.ga!!.visitInsn(Opcodes.ACONST_NULL)
                funcType.ga!!.visitInsn(Opcodes.ARETURN)
            } else {
                funcType.ga!!.visitInsn(Opcodes.RETURN)
            }
        }
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для объявления структуры: создает внутренний класс с полями, конструктором и методом toString.
     * @param statement Оператор структуры
     * @return Optional с ClassWriter внутреннего класса
     */
    override fun visitStruct(statement: StructStatement): Optional<ClassWriter> {
        val innerCw = ClassWriter(FLAGS)
        val structType = currentContext!!.getVariableTyped<StructType>(statement.name.source)

        val name = structType!!.name
        val innerName = source.fullRelativePath + "$" + name

        innerCw.visit(CLASS_VERSION, PublicStatic, innerName, null, "java/lang/Object", null)
        cw.visitInnerClass(innerName, source.fullRelativePath, name, PublicStatic)
        innerCw.visitOuterClass(source.fullRelativePath, name, "()V")

        val constructorDescriptor = StringBuilder()

        for (pair in structType.parameters) {
            val type = pair.second
            val descriptor: String = type.descriptor!!
            val n = pair.first

            val fieldVisitor = innerCw.visitField(Opcodes.ACC_PUBLIC, n, descriptor, null, null)
            fieldVisitor.visitEnd()

            constructorDescriptor.append(descriptor)
        }

        var descriptor = "($constructorDescriptor)V"
        val mv = innerCw.visitMethod(Opcodes.ACC_PUBLIC, Init, descriptor, null, null)
        val ga = GeneratorAdapter(mv, Opcodes.ACC_PUBLIC, Init, descriptor)

        val ownerInternalName = source.fullRelativePath + "$" + name

        ga.loadThis()
        ga.invokeConstructor(ObjectType, Method(Init, "()V"))

        for (i in structType.parameters.indices) {
            val type = structType.parameters[i].second
            descriptor = type.descriptor!!
            val n = structType.parameters[i].first
            ga.visitVarInsn(Opcodes.ALOAD, 0)
            ga.loadArg(i)

            ga.visitFieldInsn(Opcodes.PUTFIELD, ownerInternalName, n, descriptor)
        }

        ga.returnValue()
        ga.endMethod()

        BytecodeGenerator.addToString(innerCw, structType, constructorDescriptor.toString(), ownerInternalName)

        this.structWriters[structType] = innerCw

        return Optional.of(innerCw)
    }

    /**
     * Обрабатывает оператор типа. В данный момент не реализовано.
     * @param statement Оператор типа
     * @return Выбрасывает NotImplementedException
     */
    override fun visitTypeAlias(statement: TypeStatement): Optional<ClassWriter> {
        throw NotImplementedException("method not implemented")
    }

    /**
     * Обрабатывает объявление union-типа. Не требует генерации байт-кода.
     * @param statement Оператор union-типа
     * @return Пустой Optional
     */
    override fun visitUnionType(statement: UnionTypeStatement): Optional<ClassWriter> {
        return Optional.empty()
    }

    /**
     * Генерирует байт-код для объявления переменной: вычисляет значение выражения и сохраняет в локальную переменную.
     * @param statement Оператор переменной
     * @return Пустой Optional
     */
    override fun visitVariable(statement: VariableStatement): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        statement.expression.accept(this)

        var type = currentContext!!.getVariable(statement.identifier())
        if (type is GenericType) {
            type = funcType.currentSpecialization!![type.key]
        }
        statement.localIndex = funcType.ga!!.newLocal(Type.getType(type!!.descriptor))
        funcType.localMap[statement.identifier()] = statement.localIndex
        funcType.ga!!.storeLocal(statement.localIndex, Type.getType(type.descriptor))

        if (statement.expression is PostfixExpression) {
            statement.expression.localIndex = statement.localIndex
        }

        return Optional.empty()
    }

    /**
     * Генерирует байт-код для цикла while с проверкой условия в начале каждой итерации.
     * @param statement Оператор while
     * @return Пустой Optional
     */
    override fun visitWhile(statement: WhileStatement): Optional<ClassWriter> {
        val funcType = functionStack.peek()
        val ga = funcType.ga
        val endLabel = Label()
        val startLabel = Label()

        ga!!.mark(startLabel)
        statement.condition.accept(this)

        currentContext = statement.block.context
        ga.ifZCmp(GeneratorAdapter.EQ, endLabel)
        statement.block.accept(this)
        ga.goTo(startLabel)
        ga.mark(endLabel)
        currentContext = currentContext!!.parent
        return Optional.empty()
    }

    companion object {
        const val FLAGS: Int = ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS
        const val CLASS_VERSION: Int = 61

        /**
         * Приводит два выражения к общему типу и генерирует код для их вычисления.
         * Используется для операций сравнения и арифметики.
         * @param ga GeneratorAdapter для генерации байт-кода
         * @param left Левое выражение
         * @param right Правое выражение
         * @param visitor Посетитель для рекурсивного посещения выражений
         * @return Тип, к которому были приведены выражения
         */
        private fun castAndAccept(
            ga: GeneratorAdapter,
            left: Expression,
            right: Expression,
            visitor: CodegenVisitor
        ): Type? {
            val lWide: Int = widenings.getOrDefault(left.realType as BuiltInType, -1)!!
            val rWide: Int = widenings.getOrDefault(right.realType as BuiltInType, -1)!!
            val lType = Type.getType(left.realType.descriptor)
            val rType = Type.getType(right.realType.descriptor)

            var cmpType = lType

            if (lWide != -1 && rWide != -1) {
                if (lWide > rWide) {
                    left.accept(visitor)
                    right.accept(visitor)
                    ga.cast(rType, lType)
                } else if (lWide < rWide) {
                    left.accept(visitor)
                    ga.cast(lType, rType)
                    right.accept(visitor)
                    cmpType = rType
                } else {
                    left.accept(visitor)
                    right.accept(visitor)
                }
            } else {
                left.accept(visitor)
                right.accept(visitor)
            }
            return cmpType
        }

        /**
         * Генерирует байт-код для арифметических операций, включая приведение типов при необходимости.
         * @param ga GeneratorAdapter для генерации байт-кода
         * @param left Левое выражение
         * @param right Правое выражение
         * @param operator Токен оператора
         * @param goalType Ожидаемый тип результата
         * @param visitor Посетитель для рекурсивного посещения выражений
         */
        fun arithmetic(
            ga: GeneratorAdapter,
            left: Expression,
            right: Expression,
            operator: Token,
            goalType: IxType?,
            visitor: CodegenVisitor
        ) {
            var goalType = goalType
            if (left.realType == right.realType) {
                left.accept(visitor)
                right.accept(visitor)
            } else {
                if (left.realType === BuiltInType.INT && right.realType === BuiltInType.FLOAT) {
                    left.accept(visitor)
                    ga.visitInsn(Opcodes.I2F)
                    right.accept(visitor)
                    goalType = BuiltInType.FLOAT
                } else if (left.realType === BuiltInType.FLOAT && right.realType === BuiltInType.INT) {
                    left.accept(visitor)
                    right.accept(visitor)
                    ga.visitInsn(Opcodes.I2F)
                    goalType = BuiltInType.FLOAT
                } else if (left.realType === BuiltInType.INT && right.realType === BuiltInType.DOUBLE) {
                    left.accept(visitor)
                    ga.visitInsn(Opcodes.I2D)
                    right.accept(visitor)
                    goalType = BuiltInType.DOUBLE
                } else if (left.realType === BuiltInType.DOUBLE && right.realType === BuiltInType.INT) {
                    left.accept(visitor)
                    right.accept(visitor)
                    ga.visitInsn(Opcodes.I2D)
                    goalType = BuiltInType.DOUBLE
                }
            }
            if (goalType is BuiltInType) {
                val op = when (operator.type) {
                    TokenType.ADD -> goalType.addOpcode
                    TokenType.SUB -> goalType.subtractOpcode
                    TokenType.MUL -> goalType.multiplyOpcode
                    TokenType.DIV -> goalType.divideOpcode
                    TokenType.LT, TokenType.GT, TokenType.LE, TokenType.GE -> 0
                    else -> throw IllegalStateException("Unexpected value: " + operator.type)
                }
                ga.visitInsn(op)
            } else {
                exit("need a test case here", 452)
            }
        }

        private fun lambdaInstantiationDescriptor(lambdaType: LambdaType): String {
            val params = lambdaType.parameters.joinToString("") { boxedDescriptor(it.second) }
            val returnDescriptor = boxedDescriptor(lambdaType.returnType)
            return "($params)$returnDescriptor"
        }

        private fun boxedDescriptor(type: IxType): String {
            return when (type) {
                BuiltInType.INT -> "Ljava/lang/Integer;"
                BuiltInType.FLOAT -> "Ljava/lang/Float;"
                BuiltInType.DOUBLE -> "Ljava/lang/Double;"
                BuiltInType.BOOLEAN -> "Ljava/lang/Boolean;"
                BuiltInType.CHAR -> "Ljava/lang/Character;"
                BuiltInType.VOID -> "Ljava/lang/Object;"
                else -> type.descriptor!!
            }
        }
    }
}
