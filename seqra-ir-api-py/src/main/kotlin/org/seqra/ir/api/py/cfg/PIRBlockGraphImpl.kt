package org.seqra.ir.api.py.cfg

class PIRBlockGraphImpl(
    override val pIRGraph: PIRGraph,
) : PIRBlockGraph {

    override val instructions: List<PIRBasicBlock> = pIRGraph.entry.location.method.blocks
    override val entry: PIRBasicBlock
        get() = instructions.first()
    override val exits: List<PIRBasicBlock>
        get() = instructions.filter { successors(it).isEmpty() }

    private val blockByInst = pIRGraph.instructions.associateWith { inst ->
        instructions.firstOrNull { inst in it }
    }

    private val successorMap = instructions.associateWith { block ->
        pIRGraph.successors(block.end).mapTo(linkedSetOf()) { inst -> block(inst) }
    }
    private val predecessorMap = instructions.associateWith { block ->
        pIRGraph.predecessors(block.start).mapTo(linkedSetOf()) { inst -> block(inst) }
    }

    override fun instructions(block: PIRBasicBlock): List<PIRInst> =
        pIRGraph.instructions.filterTo(mutableListOf()) { it in block }

    override fun block(inst: PIRInst): PIRBasicBlock =
        when (inst) {
            is PIRCatchInst -> inst.handler
            else -> blockByInst[inst]
        } ?: error("Block not found for $inst in ${inst.location.method.fullname}")

    override fun successors(node: PIRBasicBlock): Set<PIRBasicBlock> = successorMap[node].orEmpty()

    override fun predecessors(node: PIRBasicBlock): Set<PIRBasicBlock> = predecessorMap[node].orEmpty()
}
