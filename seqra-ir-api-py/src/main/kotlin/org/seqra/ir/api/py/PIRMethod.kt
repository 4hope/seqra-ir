package org.seqra.ir.api.py

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.CommonMethodParameter
import org.seqra.ir.api.common.CommonTypeName
import org.seqra.ir.api.py.cfg.*

const val PIR_FUNC_NORMAL = 0
const val PIR_FUNC_STATICMETHOD = 1
const val PIR_FUNC_CLASSMETHOD = 2

const val ARG_POS = 0
const val ARG_OPT = 1
const val ARG_STAR = 2
const val ARG_NAMED = 3
const val ARG_STAR2 = 4
const val ARG_NAMED_OPT = 5

data class PIRRuntimeArg(
    val name: String,
    override val type: CommonTypeName,
    val kind: Int = ARG_POS,
    val posOnly: Boolean = false
) : CommonMethodParameter {
    val optional: Boolean = kind == ARG_OPT || kind == ARG_NAMED_OPT
}

data class PIRFuncSignature(
    val args: List<PIRRuntimeArg>,
    val retType: PIRType,
    val numBitmapArgs: Int = 0
)

data class PIRFuncDecl(
    val name: String,
    val className: String?,
    val moduleName: String,
    val sig: PIRFuncSignature,
    val boundSig: PIRFuncSignature? = null,
    val kind: Int = PIR_FUNC_NORMAL,
    val isPropSetter: Boolean = false,
    val isPropGetter: Boolean = false,
    val isGenerator: Boolean = false,
    val isCoroutine: Boolean = false,
    val enclosingFunction: String? = null,
    val isLocal: Boolean = false,
    val isLambda: Boolean = false,
    val implicit: Boolean = false,
    val internal: Boolean = false,
    val line: Int? = null
) {
    val shortname: String = if (className != null) "$className.$name" else name
    val fullname: String = "$moduleName.$shortname"
}

data class PIRFunc(
    val decl: PIRFuncDecl,
    val argRegs: List<PIRRegister>,
    val instructions: List<PIRInst>,
    val blocks: List<PIRBasicBlock>,
    val tracebackName: String? = null,
    val enclosingClass: PIRClass
) : CommonMethod {
    override val name: String get() = decl.name
    override val parameters: List<CommonMethodParameter> get() = decl.sig.args
    override val returnType: CommonTypeName get() = decl.sig.retType

    override fun flowGraph(): PIRGraph = PIRGraphImpl(this)

    val line: Int? get() = decl.line
    val className: String? get() = decl.className
    val fullname: String get() = decl.fullname
    val enclosingFunction: String? get() = decl.enclosingFunction
    val isLocal: Boolean get() = decl.isLocal
    val isLambda: Boolean get() = decl.isLambda
}
