package org.seqra.ir.api.py

import org.seqra.ir.api.common.CommonMethod
import org.seqra.ir.api.common.CommonMethodParameter
import org.seqra.ir.api.common.CommonTypeName
import org.seqra.ir.api.common.cfg.BytecodeGraph
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.common.cfg.ControlFlowGraph
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

    override fun flowGraph(): ControlFlowGraph<CommonInst> {
        val insts = instructions
        val instByIndex = insts.associateBy { it.location.index }
        val blockByInst = insts.associateWith { inst -> blocks.firstOrNull { inst in it } }

        fun mayThrow(inst: PIRInst): Boolean =
            when (inst) {
                is PIRAssignInst -> inst.rhv.errorKind != ERR_NEVER
                is PIREffectInst -> inst.effect.errorKind != ERR_NEVER
                else -> false
            }

        val regularSuccessors = insts.associateWith { inst ->
            when (inst) {
                is PIRGotoInst -> setOfNotNull(instByIndex[inst.target.index])
                is PIRIfInst -> setOfNotNull(
                    instByIndex[inst.trueBranch.index],
                    instByIndex[inst.falseBranch.index]
                )
                is PIRReturnInst, is PIRUnreachableInst -> emptySet()
                else -> instByIndex[inst.location.index + 1]?.let(::setOf) ?: emptySet()
            }
        }
        val exceptionalSuccessors = insts.associateWith { inst ->
            val block = blockByInst[inst]
            val handler = block?.errorHandler?.inst ?: block?.errorHandler?.let { instByIndex[it.index] }
            if (handler != null && mayThrow(inst)) setOf(handler) else emptySet()
        }
        val successors = insts.associateWith { inst ->
            linkedSetOf<PIRInst>().apply {
                addAll(regularSuccessors[inst].orEmpty())
                addAll(exceptionalSuccessors[inst].orEmpty())
            }
        }
        val predecessors = insts.associateWith { target ->
            insts.filterTo(linkedSetOf()) { candidate ->
                successors[candidate].orEmpty().contains(target)
            }
        }

        return object : BytecodeGraph<CommonInst> {
            override val instructions: List<CommonInst> = insts
            override val entries: List<CommonInst> = insts.firstOrNull()?.let(::listOf) ?: emptyList()
            override val exits: List<CommonInst> =
                insts.filterTo(mutableListOf()) { regularSuccessors[it].isNullOrEmpty() }

            override fun successors(node: CommonInst): Set<CommonInst> =
                successors[node as? PIRInst].orEmpty()

            override fun predecessors(node: CommonInst): Set<CommonInst> =
                predecessors[node as? PIRInst].orEmpty()

            override fun throwers(node: CommonInst): Set<CommonInst> =
                predecessors[node as? PIRInst].orEmpty()
                    .filterTo(linkedSetOf()) { exceptionalSuccessors[it].orEmpty().contains(node) }

            override fun catchers(node: CommonInst): Set<CommonInst> =
                exceptionalSuccessors[node as? PIRInst].orEmpty()
        }
    }

    val line: Int? get() = decl.line
    val className: String? get() = decl.className
    val fullname: String get() = decl.fullname
    val enclosingFunction: String? get() = decl.enclosingFunction
    val isLocal: Boolean get() = decl.isLocal
    val isLambda: Boolean get() = decl.isLambda
}
