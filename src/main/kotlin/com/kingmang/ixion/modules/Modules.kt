package com.kingmang.ixion.modules

import com.kingmang.ixion.runtime.BuiltInType
import com.kingmang.ixion.runtime.DefType
import com.kingmang.ixion.runtime.ExternalType
import com.kingmang.ixion.runtime.IxType
import java.lang.reflect.Method

object Modules {
    val modules: MutableMap<String?, Class<*>?> = HashMap()

    init {
        modules["std"] = Prelude::class.java
        modules["http"] = HttpModule::class.java
        modules["async"] = AsyncModule::class.java
        modules["strings"] = StringModule::class.java
    }

    @JvmStatic
    fun getExports(module: String?): MutableList<DefType?> {
        val result = ArrayList<DefType?>()
        if (modules.containsKey(module)) {
            val c: Class<*> = modules[module]!!
            val m = c.getMethods()
            for (method in m) {
                var name = method.name
                if (method.declaringClass == Any::class.java) {
                    continue
                }
                val parameters = getPairs(method)
                var isPrefixed = false
                if (name.startsWith("_")) {
                    name = name.substring(1)
                    isPrefixed = true
                }
                val funcType = DefType(name, parameters)
                funcType.isPrefixed = isPrefixed
                funcType.returnType = mapJavaType(method.returnType)

                funcType.glue = true
                funcType.owner = c.getName().replace('.', '/')
                result.add(funcType)
            }
        }
        return result
    }

    private fun getPairs(method: Method): ArrayList<Pair<String, IxType>> {
        val parameters = ArrayList<Pair<String, IxType>>()
        for (p in method.parameterTypes) {
            parameters.add(Pair("_", mapJavaType(p)))
        }
        return parameters
    }

    private fun mapJavaType(type: Class<*>): IxType {
        return when (type) {
            Integer.TYPE,
            Integer::class.java -> BuiltInType.INT

            java.lang.Float.TYPE,
            java.lang.Float::class.java -> BuiltInType.FLOAT

            java.lang.Double.TYPE,
            java.lang.Double::class.java -> BuiltInType.DOUBLE

            java.lang.Boolean.TYPE,
            java.lang.Boolean::class.java -> BuiltInType.BOOLEAN

            Character.TYPE,
            Character::class.java -> BuiltInType.CHAR

            java.lang.String::class.java -> BuiltInType.STRING

            Void.TYPE,
            Void::class.java -> BuiltInType.VOID

            else -> ExternalType(type)
        }
    }
}
