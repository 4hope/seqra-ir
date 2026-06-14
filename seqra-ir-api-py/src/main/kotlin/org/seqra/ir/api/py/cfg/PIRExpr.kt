package org.seqra.ir.api.py.cfg

import org.seqra.ir.api.common.cfg.CommonCallExpr
import org.seqra.ir.api.common.cfg.CommonExpr
import org.seqra.ir.api.common.cfg.CommonInstanceCallExpr
import org.seqra.ir.api.py.*
import java.util.Collections.emptyList

const val ERR_NEVER = 0
const val ERR_FALSE = 2

const val NAMESPACE_STATIC = "static"
const val NAMESPACE_TYPE = "type"

interface PIRExpr : CommonExpr {
    val type: PIRType
    val line: Int
    val isBorrowed: Boolean
    val errorKind: Int
    val operands: List<PIRValue>

    override val typeName: String get() = type.typeName

    fun <T> accept(visitor: PIRExprVisitor<T>): T
}

interface PIRConditionExpr : PIRExpr

interface PIRBinaryExpr : PIRExpr {
    val lhs: PIRValue
    val rhs: PIRValue
    override val operands: List<PIRValue> get() = listOf(lhs, rhs)
}

interface PIRUnaryExpr : PIRExpr {
    val operand: PIRValue
    override val operands: List<PIRValue> get() = listOf(operand)
}

interface PIRLiteralValue {
    val type: PIRType
    val value: Any?
}

data class PIRMoveExpr(
    val value: PIRValue,
    override val type: PIRType = value.type,
    override val line: Int = value.line,
    override val isBorrowed: Boolean = value.isBorrowed,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = listOf(value)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitMove(this)
}

data class PIRLiteralExpr(
    val literal: PIRLiteralValue,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = emptyList()
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitLoadLiteral(this)
}

data class PIRDirectCallExpr(
    val funcDecl: PIRFuncDecl,
    override val args: List<PIRValue>,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr, CommonCallExpr {
    override val operands: List<PIRValue> = args
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitDirectCall(this)
}

data class PIRMethodCallExpr(
    val obj: PIRValue,
    val method: String,
    override val args: List<PIRValue>,
    val receiverType: PIRType,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr, CommonInstanceCallExpr {
    override val instance: PIRValue get() = obj
    override val operands: List<PIRValue> = listOf(obj) + args
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitMethodCall(this)
}

data class PIRPrimitiveDescription(
    val name: String,
    val argTypes: List<PIRType> = emptyList(),
    val returnType: PIRType,
    val varArgType: PIRType? = null,
    val truncatedType: PIRType? = null,
    val cFunctionName: String = "",
    val errorKind: Int = ERR_NEVER,
    val steals: Any? = null,
    val isBorrowed: Boolean = false,
    val ordering: List<Int> = emptyList(),
    val extraIntConstants: List<Pair<Int, PIRType>> = emptyList(),
    val priority: Int = 0,
    val isPure: Boolean = false,
    val experimental: Boolean = false,
    val dependencies: List<PIRDependency> = emptyList(),
    val isAmbiguous: Boolean = false
)

data class PIRPrimitiveCallExpr(
    val primitive: PIRPrimitiveDescription,
    val args: List<PIRValue>,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = primitive.errorKind
) : PIRExpr {
    override val operands: List<PIRValue> = args
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitPrimitiveCall(this)
}

data class PIRCallCExpr(
    val functionName: String,
    val args: List<PIRValue>,
    val steals: Any,
    val varArgIdx: Int,
    val isPure: Boolean,
    val returnsNull: Boolean,
    val dependencies: List<PIRDependency>?,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = args
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitCCall(this)
}

data class PIRLoadErrorValueExpr(
    val undefines: Boolean = false,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = emptyList()
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitLoadErrorValue(this)
}

data class PIRGetAttrExpr(
    val obj: PIRValue,
    val attr: String,
    val classType: PIRType,
    val allowErrorValue: Boolean = false,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = listOf(obj)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitGetAttr(this)
}

data class PIRLoadStaticExpr(
    val identifier: String,
    val moduleName: String? = null,
    val namespace: String = NAMESPACE_STATIC,
    val ann: Any? = null,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = emptyList()
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitLoadStatic(this)
}

data class PIRTupleExpr(
    val items: List<PIRValue>,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = items
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitTuple(this)
}

data class PIRTupleGetExpr(
    val tuple: PIRValue,
    val index: Int,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = listOf(tuple)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitTupleGet(this)
}

data class PIRCastExpr(
    override val operand: PIRValue,
    val unchecked: Boolean = false,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRUnaryExpr {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitCast(this)
}

data class PIRBoxExpr(
    override val operand: PIRValue,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRUnaryExpr {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitBox(this)
}

data class PIRUnboxExpr(
    override val operand: PIRValue,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRUnaryExpr {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitUnbox(this)
}

enum class PIRIntOpKind { ADD, SUB, MUL, DIV, MOD, AND, OR, XOR, SHL, SHR }
enum class PIRCmpKind { EQ, NEQ, LT, GT, LE, GE, ULT, UGT, ULE, UGE }
enum class PIRFloatOpKind { ADD, SUB, MUL, DIV, MOD }

data class PIRIntBinExpr(
    override val lhs: PIRValue,
    override val rhs: PIRValue,
    val op: PIRIntOpKind,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRBinaryExpr {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitIntBin(this)
}

data class PIRCmpExpr(
    override val lhs: PIRValue,
    override val rhs: PIRValue,
    val op: PIRCmpKind,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRBinaryExpr, PIRConditionExpr {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitCmp(this)
}

data class PIRFloatBinExpr(
    override val lhs: PIRValue,
    override val rhs: PIRValue,
    val op: PIRFloatOpKind,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRBinaryExpr {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitFloatBin(this)
}

data class PIRFloatNegExpr(
    override val operand: PIRValue,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRUnaryExpr {
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitFloatNeg(this)
}

data class PIRLoadMemExpr(
    val address: PIRValue,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = listOf(address)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitLoadMem(this)
}

data class PIRGetElementExpr(
    val src: PIRValue,
    val srcType: PIRType,
    val field: String,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = listOf(src)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitGetElement(this)
}

data class PIRGetElementPtrExpr(
    val src: PIRValue,
    val srcType: PIRType,
    val field: String,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = listOf(src)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitGetElementPtr(this)
}

data class PIRLoadAddressExpr(
    val target: Any,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = emptyList()
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitLoadAddress(this)
}

data class PIRLoadGlobalExpr(
    val identifier: String,
    val ann: Any? = null,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = emptyList()
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitLoadGlobal(this)
}

data class PIRPhiExpr(
    override val type: PIRType,
    val values: List<PIRValue>,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIRExpr {
    override val operands: List<PIRValue> = values
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitPhi(this)
}

/** Side-effect expressions used by PIREffectInst **/
interface PIREffectExpr : PIRExpr

data class PIRSetAttrExpr(
    val obj: PIRValue,
    val attr: String,
    val src: PIRValue,
    val classType: PIRType,
    val init: Boolean = false,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOf(obj, src)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitSetAttr(this)
}

data class PIRInitStaticExpr(
    val identifier: String,
    val moduleName: String? = null,
    val namespace: String = NAMESPACE_STATIC,
    val value: PIRValue,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOf(value)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitInitStatic(this)
}

data class PIRSetMemExpr(
    val destType: PIRType,
    val dest: PIRValue,
    val src: PIRValue,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOf(src, dest)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitSetMem(this)
}

data class PIRSetElementExpr(
    val src: PIRValue,
    val field: String,
    val item: PIRValue,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOf(src, item)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitSetElement(this)
}

data class PIRKeepAliveExpr(
    val src: List<PIRValue>,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = src
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitKeepAlive(this)
}

data class PIRIncRefExpr(
    val src: PIRValue,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOf(src)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitIncRef(this)
}

data class PIRDecRefExpr(
    val src: PIRValue,
    val xdec: Boolean = false,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOf(src)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitDecRef(this)
}

data class PIRUnborrowExpr(
    val src: PIRValue,
    override val type: PIRType = PIRVoidType(),
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_NEVER
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOf(src)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitUnborrow(this)
}

data class PIRRaiseStandardErrorExpr(
    val className: String,
    val value: Any?,
    override val type: PIRType,
    override val line: Int = -1,
    override val isBorrowed: Boolean = false,
    override val errorKind: Int = ERR_FALSE
) : PIREffectExpr {
    override val operands: List<PIRValue> = listOfNotNull(value as? PIRValue)
    override fun <T> accept(visitor: PIRExprVisitor<T>): T = visitor.visitRaiseStandardError(this)
}
