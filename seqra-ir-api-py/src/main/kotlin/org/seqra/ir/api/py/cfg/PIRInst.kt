package org.seqra.ir.api.py.cfg

import org.seqra.ir.api.common.cfg.*
import org.seqra.ir.api.py.PIRFunc

interface PIRInstLocation : CommonInstLocation {
    override val method: PIRFunc
    val index: Int
    val line: Int
}

interface PIRInst : CommonInst {
    override val location: PIRInstLocation
    val operands: List<PIRExpr>
    val line: Int get() = location.line

    fun <T> accept(visitor: PIRInstVisitor<T>): T
}

abstract class AbstractPIRInst(
    final override val location: PIRInstLocation
) : PIRInst {
    override fun hashCode(): Int = location.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AbstractPIRInst
        return location == other.location
    }
}

class PIRAssignInst(
    location: PIRInstLocation,
    override val lhv: PIRValue,
    override val rhv: PIRExpr
) : AbstractPIRInst(location), CommonAssignInst {
    override val operands: List<PIRExpr> = listOf(rhv)

    override fun <T> accept(visitor: PIRInstVisitor<T>): T =
        visitor.visitAssign(this)
}

class PIREffectInst(
    location: PIRInstLocation,
    val effect: PIREffectExpr
) : AbstractPIRInst(location) {
    override val operands: List<PIRExpr> = listOf(effect)

    override fun <T> accept(visitor: PIRInstVisitor<T>): T =
        visitor.visitEffect(this)
}

interface PIRBranchingInst : PIRInst {
    val successors: List<PIRInstRef>
}

class PIRGotoInst(
    location: PIRInstLocation,
    val target: PIRInstRef
) : AbstractPIRInst(location), PIRBranchingInst, CommonGotoInst {
    override val operands: List<PIRExpr> = emptyList()
    override val successors: List<PIRInstRef> = listOf(target)

    override fun <T> accept(visitor: PIRInstVisitor<T>): T =
        visitor.visitGoto(this)
}

class PIRIfInst(
    location: PIRInstLocation,
    val condition: PIRConditionExpr,
    val trueBranch: PIRInstRef,
    val falseBranch: PIRInstRef,
    val negated: Boolean = false,
    val tracebackEntry: Pair<String, Int>? = null,
    val rare: Boolean = false
) : AbstractPIRInst(location), PIRBranchingInst, CommonIfInst {
    override val operands: List<PIRExpr> = listOf(condition)
    override val successors: List<PIRInstRef> = listOf(trueBranch, falseBranch)

    override fun <T> accept(visitor: PIRInstVisitor<T>): T =
        visitor.visitIf(this)
}

class PIRReturnInst(
    location: PIRInstLocation,
    override val returnValue: PIRValue?,
    val yieldTarget: PIRInstRef? = null
) : AbstractPIRInst(location), CommonReturnInst {
    override val operands: List<PIRExpr> = listOfNotNull(returnValue)

    override fun <T> accept(visitor: PIRInstVisitor<T>): T =
        visitor.visitReturn(this)
}

class PIRUnreachableInst(
    location: PIRInstLocation
) : AbstractPIRInst(location) {
    override val operands: List<PIRExpr> = emptyList()

    override fun <T> accept(visitor: PIRInstVisitor<T>): T =
        visitor.visitUnreachable(this)
}