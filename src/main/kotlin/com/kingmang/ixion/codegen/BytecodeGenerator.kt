package com.kingmang.ixion.codegen

import com.kingmang.ixion.api.IxApi
import com.kingmang.ixion.api.IxFile
import com.kingmang.ixion.api.IxionConstant.Clinit
import com.kingmang.ixion.api.IxionConstant.Init
import com.kingmang.ixion.runtime.StructType
import org.apache.commons.io.FilenameUtils
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import java.util.stream.Collectors

class BytecodeGenerator {
    fun generate(compiler: IxApi, source: IxFile): Pair<ClassWriter, Map<StructType, ClassWriter>> {
        val cw = ClassWriter(CodegenVisitor.FLAGS)

        val qualifiedName = FilenameUtils.removeExtension(source.fullRelativePath)
        cw.visit(
            CodegenVisitor.CLASS_VERSION,
            Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
            qualifiedName,
            null,
            "java/lang/Object",
            null
        )

        val initMv = cw.visitMethod(Opcodes.ACC_PUBLIC, Init, "()V", null, null)
        var ga = GeneratorAdapter(initMv, Opcodes.ACC_PUBLIC, Init, "()V")
        ga.visitVarInsn(Opcodes.ALOAD, 0)
        ga.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            "java/lang/Object",
            Init,
            "()V",
            false
        )
        ga.returnValue()
        ga.endMethod()

        cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "instance", "L$qualifiedName;", null, null)

        val mvStatic = cw.visitMethod(Opcodes.ACC_STATIC, Clinit, "()V", null, null)
        ga = GeneratorAdapter(mvStatic, Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, Clinit, "()V")
        val t = Type.getType("L$qualifiedName;")
        ga.newInstance(t)
        ga.dup()
        ga.invokeConstructor(t, Method(Init, "()V"))

        mvStatic.visitFieldInsn(Opcodes.PUTSTATIC, qualifiedName, "instance", "L$qualifiedName;")
        mvStatic.visitInsn(Opcodes.RETURN)
        ga.endMethod()

        val codegenVisitor = CodegenVisitor(compiler, source.rootContext, source, cw)

        source.acceptVisitor(codegenVisitor)

        cw.visitEnd()

        return Pair(cw, codegenVisitor.getStructWriters())
    }

    companion object {
        @JvmStatic
        fun addToString(cw: ClassWriter, st: StructType, constructorDescriptor: String?, ownerInternalName: String?) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null)
            val ga = GeneratorAdapter(mv, Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;")

            if (!st.parameters.isEmpty()) {
                val handle = Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/StringConcatFactory",
                    "makeConcatWithConstants",
                    $$"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                    false
                )

                for (pair in st.parameters) {
                    val pName = pair.first
                    val pType = pair.second
                    ga.loadThis()
                    ga.getField(Type.getType("L$ownerInternalName;"), pName, Type.getType(pType.descriptor))
                }

                val recipe = st.name + "[" + st.parameters.stream()
                    .map { it!!.first + "=\u0001" }
                    .collect(Collectors.joining(", ")) + "]"

                ga.invokeDynamic(
                    "makeConcatWithConstants",
                    "($constructorDescriptor)Ljava/lang/String;",
                    handle,
                    recipe
                )
            } else {
                ga.push(st.name + "[]")
            }
            ga.visitInsn(Opcodes.ARETURN)
            ga.endMethod()
        }
    }
}