package org.seqra.ir.api.py.cfg

import org.seqra.ir.api.py.PIRClass
import org.seqra.ir.api.py.PIRFunc
import org.seqra.ir.api.py.PIRPrimitiveTypes

class PIRGraphImpl(
    private val function: PIRFunc,
) : PIRGraph {

    override val instructions: List<PIRInst> = function.instructions
    override val entry: PIRInst
        get() = instructions.first()

    override val exits: List<PIRInst>

    private val instByIndex = instructions.associateBy { it.location.index }
    private val instPosition = instructions.withIndex().associate { (index, inst) -> inst to index }
    private val blockByInst = instructions.associateWith { inst -> function.blocks.firstOrNull { inst in it } }

    private val normalSuccessors: Map<PIRInst, Set<PIRInst>>
    private val normalPredecessors: Map<PIRInst, Set<PIRInst>>
    private val catchersByBlock: Map<PIRBasicBlock, PIRCatchInst>
    private val throwersByCatcher: Map<PIRCatchInst, Set<PIRInst>>

    init {
        normalSuccessors = instructions.associateWith { inst ->
            when (inst) {
                is PIRBranchingInst -> inst.successors.mapNotNullTo(linkedSetOf(), ::instOrNull)
                is PIRReturnInst, is PIRUnreachableInst -> emptySet()
                else -> next(inst)?.let(::setOf) ?: emptySet()
            }
        }
        normalPredecessors = instructions.associateWith { target ->
            instructions.filterTo(linkedSetOf()) { candidate ->
                normalSuccessors[candidate].orEmpty().contains(target)
            }
        }
        exits = instructions.filterTo(mutableListOf()) { normalSuccessors[it].isNullOrEmpty() }

        val catchers = linkedMapOf<PIRBasicBlock, PIRCatchInst>()
        val throwers = linkedMapOf<PIRCatchInst, Set<PIRInst>>()
        function.blocks.forEach { block ->
            val handlerStart = block.errorHandler?.let(::instOrNull) ?: return@forEach
            val handlerBlock = function.blocks.firstOrNull { handlerStart in it } ?: return@forEach
            val protectedThrowers = instructions.filterTo(linkedSetOf()) { inst ->
                inst in block && mayThrow(inst)
            }
            val catcher = PIRCatchInst(
                location = handlerStart.location,
                handler = handlerBlock,
                throwable = PIRUndef(PIRPrimitiveTypes.OBJECT, handlerStart.line),
                throwers = protectedThrowers.map(::ref),
                line = handlerStart.line
            )
            catchers[block] = catcher
            throwers[catcher] = protectedThrowers
        }
        catchersByBlock = catchers
        throwersByCatcher = throwers
    }

    override fun index(inst: PIRInst): Int = inst.location.index

    override fun ref(inst: PIRInst): PIRInstRef = PIRInstRef(index(inst), inst)

    override fun inst(ref: PIRInstRef): PIRInst =
        instOrNull(ref) ?: error("Instruction not found for ref $ref in ${function.fullname}")

    override fun previous(inst: PIRInst): PIRInst? =
        instPosition[inst]?.takeIf { it > 0 }?.let { instructions[it - 1] }

    override fun next(inst: PIRInst): PIRInst? =
        instPosition[inst]?.takeIf { it + 1 < instructions.size }?.let { instructions[it + 1] }

    override fun successors(node: PIRInst): Set<PIRInst> =
        when (node) {
            is PIRCatchInst -> setOf(inst(node.handler.start))
            else -> normalSuccessors[node].orEmpty()
        }

    override fun predecessors(node: PIRInst): Set<PIRInst> =
        if (node is PIRCatchInst) emptySet() else normalPredecessors[node].orEmpty()

    override fun throwers(node: PIRInst): Set<PIRInst> =
        (node as? PIRCatchInst)?.let { throwersByCatcher[it].orEmpty() } ?: emptySet()

    override fun catchers(node: PIRInst): Set<PIRCatchInst> {
        if (node is PIRCatchInst || !mayThrow(node)) {
            return emptySet()
        }
        return blockByInst[node]?.let(catchersByBlock::get)?.let(::setOf) ?: emptySet()
    }

    override fun previous(inst: PIRInstRef): PIRInst? = previous(inst(inst))
    override fun next(inst: PIRInstRef): PIRInst? = next(inst(inst))

    override fun successors(inst: PIRInstRef): Set<PIRInst> = successors(inst(inst))
    override fun predecessors(inst: PIRInstRef): Set<PIRInst> = predecessors(inst(inst))
    override fun throwers(inst: PIRInstRef): Set<PIRInst> = throwers(inst(inst))
    override fun catchers(inst: PIRInstRef): Set<PIRCatchInst> = catchers(inst(inst))

    override fun exceptionExits(inst: PIRInst): Set<PIRClass> = emptySet()
    override fun exceptionExits(ref: PIRInstRef): Set<PIRClass> = exceptionExits(inst(ref))

    override fun blockGraph(): PIRBlockGraph = PIRBlockGraphImpl(this)

    private fun instOrNull(ref: PIRInstRef): PIRInst? = ref.inst ?: instByIndex[ref.index]

    private fun mayThrow(inst: PIRInst): Boolean =
        when (inst) {
            is PIRAssignInst -> inst.rhv.errorKind != ERR_NEVER
            is PIREffectInst -> inst.effect.errorKind != ERR_NEVER
            else -> false
        }
}
