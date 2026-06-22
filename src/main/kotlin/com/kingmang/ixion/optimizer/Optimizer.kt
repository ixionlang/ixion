package com.kingmang.ixion.optimizer

import com.kingmang.ixion.api.IxFile

class Optimizer {
    private val passes: List<(IxFile) -> Unit> = listOf(
        { ConstantFoldingVisitor().optimize(it) },
        { DeadCodeEliminationVisitor().optimize(it) }
    )

    fun optimize(compilationSet: Map<String?, IxFile>) {
        for (source in compilationSet.values) {
            for (pass in passes) {
                pass(source)
            }
        }
    }
}
