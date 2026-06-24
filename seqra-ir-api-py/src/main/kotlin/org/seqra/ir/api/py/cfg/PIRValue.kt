package org.seqra.ir.api.py.cfg

import org.seqra.ir.api.common.cfg.CommonArgument
import org.seqra.ir.api.common.cfg.CommonValue
import org.seqra.ir.api.py.PIRType
import java.util.Collections.emptyList

interface PIRValue : CommonValue, PIRExpr {
    override val type: PIRType
    override val operands: List<PIRValue> get() = emptyList()
    override val typeName: String get() = type.typeName
}

data class PIRRegister(
    val name: String,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    val isArg: Boolean = false
) : PIRValue {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitRegister(this)
    override fun toString(): String = name
    override val errorKind: Int = ERR_NEVER
}

data class PIRInteger(
    val value: Int,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false
) : PIRValue {
    override val errorKind: Int = ERR_NEVER

    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitInteger(this)
}

data class PIRFloat(
    val value: Double,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false
) : PIRValue {
    override val errorKind: Int = ERR_NEVER

    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitFloat(this)
}

data class PIRCString(
    val value: ByteArray,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false
) : PIRValue {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitCString(this)

    override fun equals(other: Any?): Boolean =
        other is PIRCString &&
                value.contentEquals(other.value) &&
                type == other.type &&
                line == other.line &&
                isBorrowed == other.isBorrowed

    override fun hashCode(): Int =
        31 * value.contentHashCode() + type.hashCode()

    override val errorKind: Int = ERR_NEVER
}

data class PIRUndef(
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false
) : PIRValue {
    override val errorKind: Int = ERR_NEVER

    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitUndef(this)
}

data class PIRArgument(
    val index: Int,
    val name: String,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false
) : PIRValue, CommonArgument {
    override val errorKind: Int = ERR_NEVER

    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitArgument(this)
}