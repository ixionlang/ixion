package com.kingmang.ixion.api

import com.kingmang.ixion.api.IxionConstant.Mutability
import com.kingmang.ixion.exception.RedeclarationException
import com.kingmang.ixion.parser.Node
import com.kingmang.ixion.runtime.IxType
import org.apache.commons.collections4.map.LinkedMap
import java.io.File

class Context {
    private val variables = LinkedMap<String?, IxType?>()
    private val mutability: MutableMap<String?, Mutability?> = HashMap()

    @JvmField
    var parent: Context? = null

    fun addVariable(name: String, type: IxType?) {
        variables[name] = type
        mutability[name] = Mutability.IMMUTABLE
    }

    fun addVariableOrError(ixApi: IxApi, name: String, type: IxType?, file: File, node: Node) {
        if (getVariable(name) != null) {
            RedeclarationException().send(ixApi, file, node, name)
        } else {
            addVariable(name, type)
        }
    }

    fun getVariable(name: String): IxType? {
        return when {
            variables[name] != null -> variables[name]
            parent != null -> parent!!.getVariable(name)
            else -> null
        }
    }

    fun getVariableMutability(name: String): Mutability? {
        return when {
            mutability[name] != null -> mutability[name]
            parent != null -> parent!!.getVariableMutability(name)
            else -> null
        }
    }

    inline fun <reified T> getVariableTyped(name: String): T? {
        val v = getVariable(name)
        if (T::class.isInstance(v))
            return v as T
        return null
    }

    fun setVariableMutability(name: String, m: Mutability?) {
        if (mutability[name] != null) {
            mutability[name] = m
        }
    }

    fun setVariableType(name: String, type: IxType?) {
        if (variables[name] != null) {
            variables[name] = type
        }
    }
}
