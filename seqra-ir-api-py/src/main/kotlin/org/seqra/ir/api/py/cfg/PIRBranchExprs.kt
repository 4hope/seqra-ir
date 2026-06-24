package org.seqra.ir.api.py.cfg

import org.seqra.ir.api.py.PIRType

data class PIRTruthExpr(
    val value: PIRValue,
    override val type: PIRType = value.type,
    override val line: Int = value.line,
    override val isBorrowed: Boolean = value.isBorrowed,
    override val errorKind: Int = ERR_NEVER
) : PIRConditionExpr {
    override val operands: List<PIRValue> = listOf(value)

    override fun <T> accept(visitor: PIRExprVisitor<T>): T =
        visitor.visitMove(PIRMoveExpr(value, type, line, isBorrowed, errorKind))
}

data class PIRErrorCheckExpr(
    val value: PIRValue,
    override val type: PIRType = value.type,
    override val line: Int = value.line,
    override val isBorrowed: Boolean = value.isBorrowed,
    override val errorKind: Int = ERR_NEVER
) : PIRConditionExpr {
    override val operands: List<PIRValue> = listOf(value)

    override fun <T> accept(visitor: PIRExprVisitor<T>): T =
        visitor.visitMove(PIRMoveExpr(value, type, line, isBorrowed, errorKind))
}