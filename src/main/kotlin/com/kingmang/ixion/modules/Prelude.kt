package com.kingmang.ixion.modules

import com.kingmang.ixion.exception.Panic
import com.kingmang.ixion.runtime.CollectionUtil.IxListWrapper
import com.kingmang.ixion.runtime.ixfunction.IxFunction1
import java.util.*
import java.util.stream.IntStream

//std
@Suppress("unused")
object Prelude {

    @JvmStatic
    fun panic(msg: Any?) {
        Panic(msg as String?).send()
    }

    @JvmStatic
    fun println(arg: Any) {
        kotlin.io.println(arg.toString())
    }

    @JvmStatic
    fun print(arg: Any) {
        kotlin.io.print(arg.toString())
    }

    @JvmStatic
    fun readLine(): String? {
        val scanner = Scanner(System.`in`)
        if (scanner.hasNextLine()) {
            val s = scanner.nextLine()
            return s
        }
        scanner.close()
        return null
    }

    @JvmStatic
    fun <T> range(start: Int, stop: Int): MutableIterator<Int?> {
        return IntStream.range(start, stop).boxed().iterator()
    }

    @JvmStatic
    fun <T> push(r: MutableList<T?>, a: T?) {
        r.add(a)
    }

    @JvmStatic
    fun <T> pop(r: MutableList<T?>): T? {
        return r.removeAt(r.size - 1)
    }

    @JvmStatic
    fun len(r: Any?): Int {
        return when (r) {
            is IxListWrapper -> r.list.size + 1
            is String -> r.length
            else -> -1
        }
    }
}
