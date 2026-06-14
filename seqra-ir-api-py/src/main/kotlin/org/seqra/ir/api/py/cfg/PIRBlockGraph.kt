package org.seqra.ir.api.py.cfg

import org.seqra.ir.api.py.*

data class PIRInstRef(
    val index: Int,
    val inst: PIRInst? = null
)

data class PIRBasicBlock(
    val start: PIRInstRef,
    val end: PIRInstRef,
    val errorHandler: PIRInstRef? = null
) {
    operator fun contains(inst: PIRInst): Boolean =
        inst.location.index in start.index..end.index

    operator fun contains(inst: PIRInstRef): Boolean =
        inst.index in start.index..end.index
}

interface PIRBlockGraph : PIRBytecodeGraph<PIRBasicBlock> {
    val pIRGraph: PIRGraph
    val entry: PIRBasicBlock

    fun instructions(block: PIRBasicBlock): List<PIRInst>
    fun block(inst: PIRInst): PIRBasicBlock
}
