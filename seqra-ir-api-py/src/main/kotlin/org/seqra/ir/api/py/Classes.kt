package org.seqra.ir.api.py

import org.seqra.ir.api.py.cfg.PIRInst
import java.util.ArrayDeque

fun PIRClass.instanceType(): PIRInstanceType = PIRInstanceType(this)

fun PIRClass.allFunctions(): Set<PIRFunc> = linkedSetOf<PIRFunc>().apply {
    addAll(methods.values)
    addAll(glueMethods.values)
    envUserFunction?.let(::add)
    properties.values.forEach { (getter, setter) ->
        add(getter)
        setter?.let(::add)
    }
    vtableEntries.forEach {
        add(it.method)
        it.shadowMethod?.let(::add)
    }
    traitVtables.values.flatten().forEach {
        add(it.method)
        it.shadowMethod?.let(::add)
    }
}

fun PIRClass.hierarchy(includeSelf: Boolean = true): Sequence<PIRClass> = sequence {
    val queue = ArrayDeque<PIRClass>()
    val visited = hashSetOf<String>()

    if (includeSelf) {
        queue.add(this@hierarchy)
    } else {
        base?.let(queue::addLast)
        traits.forEach(queue::addLast)
        mro.filterNot { it.fullname == fullname }.forEach(queue::addLast)
    }

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current.fullname)) {
            continue
        }
        yield(current)
        current.base?.let(queue::addLast)
        current.traits.forEach(queue::addLast)
        current.mro.filterNot { it.fullname == current.fullname }.forEach(queue::addLast)
    }
}

fun PIRClass.findMethod(name: String): PIRFunc? =
    hierarchy().mapNotNull { it.methods[name] }.firstOrNull()

fun PIRClass.findMethods(name: String): Set<PIRFunc> =
    hierarchy().mapNotNullTo(linkedSetOf()) { it.methods[name] }

fun PIRClass.findAttributeType(name: String): PIRType? =
    hierarchy().mapNotNull { it.attributes[name] ?: it.propertyTypes[name] }.firstOrNull()

fun PIRClass.allChildren(): Set<PIRClass> = linkedSetOf<PIRClass>().apply {
    val queue = ArrayDeque(children)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!add(current)) {
            continue
        }
        queue.addAll(current.children)
    }
}

fun PIRClass.reachableInstructions(): List<PIRInst> =
    allFunctions().flatMapTo(linkedSetOf(), PIRFunc::reachableInstructions).toList()
