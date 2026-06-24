package org.seqra.ir.api.py.cfg

interface PIRExprVisitor<T> {
    fun visitRegister(value: PIRRegister): T
    fun visitInteger(value: PIRInteger): T
    fun visitFloat(value: PIRFloat): T
    fun visitCString(value: PIRCString): T
    fun visitUndef(value: PIRUndef): T
    fun visitArgument(value: PIRArgument): T

    fun visitMove(expr: PIRMoveExpr): T
    fun visitLoadLiteral(expr: PIRLiteralExpr): T
    fun visitDirectCall(expr: PIRDirectCallExpr): T
    fun visitMethodCall(expr: PIRMethodCallExpr): T
    fun visitPrimitiveCall(expr: PIRPrimitiveCallExpr): T
    fun visitCCall(expr: PIRCallCExpr): T
    fun visitLoadErrorValue(expr: PIRLoadErrorValueExpr): T
    fun visitGetAttr(expr: PIRGetAttrExpr): T
    fun visitLoadStatic(expr: PIRLoadStaticExpr): T
    fun visitTuple(expr: PIRTupleExpr): T
    fun visitTupleGet(expr: PIRTupleGetExpr): T
    fun visitCast(expr: PIRCastExpr): T
    fun visitBox(expr: PIRBoxExpr): T
    fun visitUnbox(expr: PIRUnboxExpr): T
    fun visitIntBin(expr: PIRIntBinExpr): T
    fun visitCmp(expr: PIRCmpExpr): T
    fun visitFloatBin(expr: PIRFloatBinExpr): T
    fun visitFloatNeg(expr: PIRFloatNegExpr): T
    fun visitLoadMem(expr: PIRLoadMemExpr): T
    fun visitGetElement(expr: PIRGetElementExpr): T
    fun visitGetElementPtr(expr: PIRGetElementPtrExpr): T
    fun visitLoadAddress(expr: PIRLoadAddressExpr): T
    fun visitLoadGlobal(expr: PIRLoadGlobalExpr): T
    fun visitPhi(expr: PIRPhiExpr): T

    fun visitSetAttr(expr: PIRSetAttrExpr): T
    fun visitInitStatic(expr: PIRInitStaticExpr): T
    fun visitSetMem(expr: PIRSetMemExpr): T
    fun visitSetElement(expr: PIRSetElementExpr): T
    fun visitKeepAlive(expr: PIRKeepAliveExpr): T
    fun visitIncRef(expr: PIRIncRefExpr): T
    fun visitDecRef(expr: PIRDecRefExpr): T
    fun visitUnborrow(expr: PIRUnborrowExpr): T
    fun visitRaiseStandardError(expr: PIRRaiseStandardErrorExpr): T
}
