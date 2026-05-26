package com.kingmang.ixion.runtime

import com.kingmang.ixion.exception.Panic
import kotlin.Pair
import java.util.*
import java.util.function.BiConsumer
import java.util.function.BiFunction
import java.util.stream.Stream
import javax.annotation.Nonnull

class CollectionUtil private constructor() {
    companion object {
        @JvmStatic
        fun <A, B, O> zipMap(a: List<A>, b: List<B>, lambda: BiFunction<A, B, out O>): Stream<O> {
            val l = ArrayList<O>()
            if (a.size == b.size) {
                val i1 = a.iterator()
                val i2 = b.iterator()
                while (i1.hasNext() && i2.hasNext()) {
                    val o = lambda.apply(i1.next(), i2.next())
                    l.add(o)
                }
            } else {
                throw ArrayIndexOutOfBoundsException("Can't zip two Lists with differing number of elements.")
            }
            return l.stream()
        }

        @JvmStatic
        fun <A, B> zip(a: List<out A>, b: List<out B>, lambda: BiConsumer<A, in B>) {
            if (a.size == b.size) {
                val i1 = a.iterator()
                val i2 = b.iterator()
                while (i1.hasNext() && i2.hasNext()) {
                    lambda.accept(i1.next(), i2.next())
                }
            } else {
                throw ArrayIndexOutOfBoundsException("Can't zip two Lists with differing number of elements.")
            }
        }

        @JvmStatic
        @SafeVarargs
        fun <T> set(vararg varargs: T): Set<T> = setOf(*varargs)

        @JvmStatic
        @SafeVarargs
        fun <T> countedSet(vararg varargs: T): Map<T, Long> =
            varargs.groupingBy { it }.eachCount().mapValues { it.value.toLong() }

        @JvmStatic
        fun <T> joinConjunction(collection: Collection<T>): String {
            val strList = collection.map { it.toString() }
            return when (strList.size) {
                0 -> ""
                1 -> strList[0]
                2 -> strList.joinToString(" and ")
                else -> strList.subList(0, strList.size - 1).joinToString(", ") + " and " + strList.last()
            }
        }

        @JvmStatic
        @SafeVarargs
        fun <T> list(vararg i: T): List<T> = i.toList()

        @JvmStatic
        fun convert(c: Class<*>): Class<*>? = when (c) {
            java.lang.Integer::class.java, Int::class.javaPrimitiveType -> Int::class.javaPrimitiveType
            java.lang.Float::class.java, Float::class.javaPrimitiveType -> Float::class.javaPrimitiveType
            java.lang.Double::class.java, Double::class.javaPrimitiveType -> Double::class.javaPrimitiveType
            java.lang.Boolean::class.java, Boolean::class.javaPrimitiveType -> Boolean::class.javaPrimitiveType
            java.lang.Character::class.java, Char::class.javaPrimitiveType -> Char::class.javaPrimitiveType
            java.lang.Long::class.java, Long::class.javaPrimitiveType -> Long::class.javaPrimitiveType
            java.lang.Short::class.java, Short::class.javaPrimitiveType -> Short::class.javaPrimitiveType
            java.lang.Byte::class.java, Byte::class.javaPrimitiveType -> Byte::class.javaPrimitiveType
            java.lang.Void::class.java, Void.TYPE -> Void.TYPE
            else -> null
        }

        @JvmStatic
        fun getMethodDescriptor(parameters: List<Pair<String, IxType>>, returnType: IxType): String {
            val parametersDescriptor = parameters.joinToString("", "(", ")") { it.second.descriptor.toString() }
            val returnDescriptor = returnType.descriptor
            return parametersDescriptor + returnDescriptor
        }
    }

    data class IxListWrapper(
        @get:JvmName("list")
        val list: ArrayList<*>,
        @get:JvmName("name")
        val name: String
    ) : Iterable<Any?>, Iterator<Any?> {

        companion object {
            @JvmStatic
            private var currentIndex = 0
        }

        constructor(name: String) : this(ArrayList<Any?>(), name)

        @Nonnull
        override fun iterator(): Iterator<Any?> {
            currentIndex = 0
            return this
        }

        override fun hasNext(): Boolean = currentIndex < list.size

        override fun next(): Any? {
            if (!hasNext()) {
                Panic("no such element").send()
            }
            return list[currentIndex++]
        }

        override fun toString(): String = list.toString()
    }
}
