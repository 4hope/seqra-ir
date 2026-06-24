package org.seqra.ir.api.py

import org.seqra.ir.api.py.cfg.PIRInst

fun PIRModules.callTargetIndex(): PIRCallTargetIndex =
    PIRCallTargetIndex.fromModules(values)

fun PIRModule.allFunctions(): Set<PIRFunc> = linkedSetOf<PIRFunc>().apply {
    addAll(functions)
    classes.forEach { addAll(it.allFunctions()) }
}

fun PIRModules.allFunctions(): Set<PIRFunc> =
    values.flatMapTo(linkedSetOf()) { it.allFunctions() }

fun PIRFunc.reachableInstructions(): List<PIRInst> =
    callResolver().reachableInstructions()

fun PIRModule.reachableInstructions(): List<PIRInst> =
    allFunctions().flatMapTo(linkedSetOf(), PIRFunc::reachableInstructions).toList()

fun PIRModules.reachableInstructions(): List<PIRInst> =
    allFunctions().flatMapTo(linkedSetOf(), PIRFunc::reachableInstructions).toList()

fun PIRModule.resolvedCalls(): List<PIRInstructionCallResolution> {
    val index = callTargetIndex()
    return allFunctions().flatMap { it.resolvedCalls(index) }
}

fun PIRModules.resolvedCalls(): List<PIRInstructionCallResolution> {
    val index = callTargetIndex()
    return allFunctions().flatMap { it.resolvedCalls(index) }
}

fun PIRModule.calledFunctions(): Set<PIRFunc> {
    val index = callTargetIndex()
    return allFunctions().flatMapTo(linkedSetOf()) { it.calledFunctions(index) }
}

fun PIRModules.calledFunctions(): Set<PIRFunc> {
    val index = callTargetIndex()
    return allFunctions().flatMapTo(linkedSetOf()) { it.calledFunctions(index) }
}

fun PIRModule.calledClasses(): Set<PIRClass> {
    val index = callTargetIndex()
    return allFunctions().flatMapTo(linkedSetOf()) { it.calledClasses(index) }
}

fun PIRModules.calledClasses(): Set<PIRClass> {
    val index = callTargetIndex()
    return allFunctions().flatMapTo(linkedSetOf()) { it.calledClasses(index) }
}
