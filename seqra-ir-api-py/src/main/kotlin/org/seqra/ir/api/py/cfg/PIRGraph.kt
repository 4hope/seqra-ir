package org.seqra.ir.api.py.cfg

import org.seqra.ir.api.common.cfg.BytecodeGraph
import org.seqra.ir.api.py.PIRClass



interface PIRGraph : PIRBytecodeGraph<PIRInst>, BytecodeGraph<PIRInst> {
    override val instructions: List<PIRInst>
    val entry: PIRInst

    override val entries: List<PIRInst>
        get() = if (instructions.isEmpty()) emptyList() else listOf(entry)

    fun index(inst: PIRInst): Int
    fun ref(inst: PIRInst): PIRInstRef
    fun inst(ref: PIRInstRef): PIRInst
    fun previous(inst: PIRInst): PIRInst?
    fun next(inst: PIRInst): PIRInst?

    override fun throwers(node: PIRInst): Set<PIRInst>
    override fun catchers(node: PIRInst): Set<PIRCatchInst>

    fun previous(inst: PIRInstRef): PIRInst?
    fun next(inst: PIRInstRef): PIRInst?
    fun successors(inst: PIRInstRef): Set<PIRInst>
    fun predecessors(inst: PIRInstRef): Set<PIRInst>
    fun throwers(inst: PIRInstRef): Set<PIRInst>
    fun catchers(inst: PIRInstRef): Set<PIRCatchInst>

    fun exceptionExits(inst: PIRInst): Set<PIRClass>
    fun exceptionExits(ref: PIRInstRef): Set<PIRClass>

    fun blockGraph(): PIRBlockGraph
}

class PIRCatchInst(
    override val location: PIRInstLocation,
    val exceptionClass: PIRClass? = null,
    val handler: PIRBasicBlock,
    val throwable: PIRValue,
    val throwers: List<PIRInstRef>,
    override val line: Int = -1,
    val rare: Boolean = false
) : PIRInst {
    override val operands: List<PIRExpr> = emptyList()

    override fun <T> accept(visitor: PIRInstVisitor<T>): T =
        error("Catch instruction is not part of the minimal visitor set")
}
