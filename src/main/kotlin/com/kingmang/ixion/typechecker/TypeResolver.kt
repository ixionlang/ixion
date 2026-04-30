package com.kingmang.ixion.typechecker

import com.kingmang.ixion.runtime.*
import com.kingmang.ixion.runtime.CollectionUtil.IxListWrapper
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.Strings

object TypeResolver {
    fun getValueFromString(value: String, t: BuiltInType): Any? {
        var value = value
        val result: Any?
        when (t) {
            BuiltInType.BOOLEAN -> result = value.toBoolean()
            BuiltInType.INT -> result = value.toInt()
            BuiltInType.FLOAT -> result = value.toFloat()
            BuiltInType.DOUBLE -> result = value.toDouble()
            BuiltInType.STRING -> {
                value = Strings.CS.removeStart(value, "\"")
                value = Strings.CS.removeEnd(value, "\"")
                result = value
            }

            else -> throw AssertionError("Objects not yet implemented!")
        }
        return result
    }

    @JvmStatic
    fun typesMatch(par: IxType, arg: IxType): Boolean {
        // union
        when {
            par is UnionType && arg is UnionType -> return par.types.containsAll(arg.types) && arg.types.containsAll(par.types)
            par is UnionType && par.types.contains(arg) -> return true
        }

        // any
        val parTypeClass = par.typeClass
        val argTypeClass = arg.typeClass
        if ((parTypeClass != null && parTypeClass == Any::class.java) ||
            (argTypeClass != null && argTypeClass == Any::class.java)
        ) {
            return true
        }

        // builtin
        if (par is BuiltInType && arg is BuiltInType) {
            return par == arg
        }

        // list
        if (isList(par) && isList(arg)) {
            val parContentType = getContentType(par)
            if (parContentType === BuiltInType.ANY)
                return true
            val argContentType = getContentType(arg)
            if (argContentType === BuiltInType.ANY)
                return true
            return typesMatch(parContentType, argContentType)
        }

        return when {
            // struct
            arg is StructType   && par is StructType      -> arg.parameters == par.parameters && arg.name == par.name
            par is LambdaType   && arg is LambdaType      -> {
                if (par.parameters.size != arg.parameters.size) {
                    return false
                }
                for (i in par.parameters.indices) {
                    if (!typesMatch(par.parameters[i].second, arg.parameters[i].second)) {
                        return false
                    }
                }
                typesMatch(par.returnType, arg.returnType)
            }
            par is ExternalType && arg is LambdaType      -> par.typeClass!!.isAssignableFrom(arg.functionalInterface)
            par is LambdaType   && arg is ExternalType    -> arg.typeClass!!.isAssignableFrom(par.functionalInterface)
            // external
            par is ExternalType && arg is ExternalType    -> {
                val parClass = par.typeClass
                val argClass = arg.typeClass
                when {
                    parClass == null || argClass == null -> par.name == arg.name
                    else -> parClass.isAssignableFrom(argClass)
                }
            }
            // other
            isList(arg)     && par is ExternalType -> par.typeClass == IxListWrapper::class.java
            isList(par)     && arg is ExternalType -> arg.typeClass == IxListWrapper::class.java
            else -> false
        }
    }

    private fun isList(type: IxType): Boolean {
        return type.name == "java.util.List" || type is ListType
    }

    private fun getContentType(type: IxType): IxType {
        if (type is ListType)
            return type.contentType
        return BuiltInType.ANY
    }
}
