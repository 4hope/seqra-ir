package org.seqra.ir.api.py.cfg

interface PIRInstVisitor<T> {
    fun visitAssign(inst: PIRAssignInst): T
    fun visitEffect(inst: PIREffectInst): T
    fun visitGoto(inst: PIRGotoInst): T
    fun visitIf(inst: PIRIfInst): T
    fun visitReturn(inst: PIRReturnInst): T
    fun visitUnreachable(inst: PIRUnreachableInst): T
}