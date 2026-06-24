package org.seqra.ir.api.py

import org.seqra.ir.api.py.cfg.PIRInst

fun PIRProject.modules(): PIRModules =
    allModules().associateBy { it.fullname }

fun PIRProject.allFunctions(): Set<PIRFunc> =
    allModules().flatMapTo(linkedSetOf()) { it.allFunctions() }

fun PIRProject.allClasses(): Set<PIRClass> =
    allModules().flatMapTo(linkedSetOf()) { it.classes }

fun PIRProject.reachableInstructions(): List<PIRInst> =
    allFunctions().flatMapTo(linkedSetOf(), PIRFunc::reachableInstructions).toList()

fun PIRProject.resolvedCalls(): List<PIRInstructionCallResolution> {
    val index = modules().callTargetIndex()
    return allFunctions().flatMap { it.resolvedCalls(index) }
}

fun PIRProject.calledFunctions(): Set<PIRFunc> {
    val index = modules().callTargetIndex()
    return allFunctions().flatMapTo(linkedSetOf()) { it.calledFunctions(index) }
}

fun PIRProject.calledClasses(): Set<PIRClass> {
    val index = modules().callTargetIndex()
    return allFunctions().flatMapTo(linkedSetOf()) { it.calledClasses(index) }
}
