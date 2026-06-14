package org.seqra.ir.api.py.analysis

import org.seqra.ir.api.common.cfg.BytecodeGraph
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.py.PIRClass
import org.seqra.ir.api.py.PIRFunc
import org.seqra.ir.api.py.PIRFuncDecl
import org.seqra.ir.api.py.PIRInstanceType
import org.seqra.ir.api.py.PIRModule
import org.seqra.ir.api.py.PIRType
import org.seqra.ir.api.py.PIRUnionType
import org.seqra.ir.api.py.cfg.NAMESPACE_TYPE
import org.seqra.ir.api.py.cfg.PIRArgument
import org.seqra.ir.api.py.cfg.PIRAssignInst
import org.seqra.ir.api.py.cfg.PIRBoxExpr
import org.seqra.ir.api.py.cfg.PIRCallCExpr
import org.seqra.ir.api.py.cfg.PIRCastExpr
import org.seqra.ir.api.py.cfg.PIRDirectCallExpr
import org.seqra.ir.api.py.cfg.PIRExpr
import org.seqra.ir.api.py.cfg.PIRGetAttrExpr
import org.seqra.ir.api.py.cfg.PIRIfInst
import org.seqra.ir.api.py.cfg.PIRInst
import org.seqra.ir.api.py.cfg.PIRLoadGlobalExpr
import org.seqra.ir.api.py.cfg.PIRLoadStaticExpr
import org.seqra.ir.api.py.cfg.PIRMethodCallExpr
import org.seqra.ir.api.py.cfg.PIRMoveExpr
import org.seqra.ir.api.py.cfg.PIRPhiExpr
import org.seqra.ir.api.py.cfg.PIRPrimitiveCallExpr
import org.seqra.ir.api.py.cfg.PIRPrimitiveDescription
import org.seqra.ir.api.py.cfg.PIRRegister
import org.seqra.ir.api.py.cfg.PIRUnboxExpr
import org.seqra.ir.api.py.cfg.PIRValue
import java.util.ArrayDeque

sealed interface PIRResolvedCallTarget

data class PIRResolvedFunctionTarget(
    val name: String,
    val moduleName: String? = null,
    val className: String? = null,
    val decl: PIRFuncDecl? = null,
    val function: PIRFunc? = null,
) : PIRResolvedCallTarget {
    val fullname: String?
        get() = decl?.fullname ?: function?.fullname ?: moduleName?.let { module ->
            if (className.isNullOrBlank()) "$module.$name" else "$module.$className.$name"
        }

    companion object {
        fun fromDecl(decl: PIRFuncDecl, function: PIRFunc? = null): PIRResolvedFunctionTarget {
            return PIRResolvedFunctionTarget(
                name = decl.name,
                moduleName = decl.moduleName,
                className = decl.className,
                decl = decl,
                function = function
            )
        }

        fun fromFunction(function: PIRFunc): PIRResolvedFunctionTarget {
            return fromDecl(function.decl, function)
        }
    }
}

data class PIRResolvedPrimitiveTarget(
    val primitive: PIRPrimitiveDescription,
) : PIRResolvedCallTarget

data class PIRResolvedCTarget(
    val functionName: String,
) : PIRResolvedCallTarget

data class PIRInstructionCallResolution(
    val instruction: PIRInst,
    val callExpr: PIRExpr,
    val targets: Set<PIRResolvedCallTarget>,
    val precise: Boolean,
)

class PIRCallTargetIndex private constructor(
    modules: Iterable<PIRModule>,
) {
    private val functionsByFullname: Map<String, PIRFunc>
    private val classesByFullname: Map<String, PIRClass>
    private val classesBySimpleName: Map<String, Set<PIRClass>>

    init {
        val allFunctions = linkedMapOf<String, PIRFunc>()
        val allClasses = linkedMapOf<String, PIRClass>()
        val simpleClasses = linkedMapOf<String, LinkedHashSet<PIRClass>>()

        modules.forEach { module ->
            module.functions.forEach { function ->
                allFunctions[function.fullname] = function
            }
            module.classes.forEach { clazz ->
                allClasses[clazz.fullname] = clazz
                simpleClasses.getOrPut(clazz.name) { linkedSetOf() }.add(clazz)
                collectClassFunctions(clazz).forEach { function ->
                    allFunctions[function.fullname] = function
                }
            }
        }

        functionsByFullname = allFunctions
        classesByFullname = allClasses
        classesBySimpleName = simpleClasses
    }

    fun resolveFunction(decl: PIRFuncDecl): PIRResolvedFunctionTarget {
        return PIRResolvedFunctionTarget.fromDecl(decl, functionsByFullname[decl.fullname])
    }

    fun resolveMethod(className: String, methodName: String): Set<PIRResolvedFunctionTarget> {
        val classes = resolveClasses(className)
        if (classes.isEmpty()) {
            return linkedSetOf(PIRResolvedFunctionTarget(name = methodName, className = className))
        }

        return classes.mapTo(linkedSetOf()) { clazz ->
            resolveMethodInHierarchy(clazz, methodName)
        }
    }

    private fun resolveMethodInHierarchy(clazz: PIRClass, methodName: String): PIRResolvedFunctionTarget {
        hierarchy(clazz).forEach { current ->
            current.methods[methodName]?.let { return PIRResolvedFunctionTarget.fromFunction(it) }
            current.methodDecls[methodName]?.let { return PIRResolvedFunctionTarget.fromDecl(it) }
        }

        return PIRResolvedFunctionTarget(
            name = methodName,
            moduleName = clazz.moduleName,
            className = clazz.name
        )
    }

    private fun resolveClasses(className: String): Set<PIRClass> {
        classesByFullname[className]?.let { return linkedSetOf(it) }
        return classesBySimpleName[className].orEmpty()
    }

    private fun hierarchy(root: PIRClass): Sequence<PIRClass> = sequence {
        val queue = ArrayDeque<PIRClass>()
        val visited = hashSetOf<String>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current.fullname)) {
                continue
            }
            yield(current)
            current.mro.forEach(queue::addLast)
            current.base?.let(queue::addLast)
            current.traits.forEach(queue::addLast)
        }
    }

    private fun collectClassFunctions(clazz: PIRClass): Set<PIRFunc> {
        return buildSet {
            addAll(clazz.methods.values)
            addAll(clazz.glueMethods.values)
            clazz.envUserFunction?.let(::add)
            clazz.properties.values.forEach { (getter, setter) ->
                add(getter)
                setter?.let(::add)
            }
            clazz.vtableEntries.forEach {
                add(it.method)
                it.shadowMethod?.let(::add)
            }
            clazz.traitVtables.values.flatten().forEach {
                add(it.method)
                it.shadowMethod?.let(::add)
            }
        }
    }

    companion object {
        fun fromModule(module: PIRModule): PIRCallTargetIndex = PIRCallTargetIndex(listOf(module))
        fun fromModules(modules: Iterable<PIRModule>): PIRCallTargetIndex = PIRCallTargetIndex(modules)
        val EMPTY: PIRCallTargetIndex = PIRCallTargetIndex(emptyList())
    }
}

class PIRInstructionCallResolver(
    private val function: PIRFunc,
    private val index: PIRCallTargetIndex = PIRCallTargetIndex.EMPTY,
    private val graph: BytecodeGraph<CommonInst> = function.flowGraph() as BytecodeGraph<CommonInst>,
) {

    fun resolve(instruction: PIRInst): PIRInstructionCallResolution? {
        val callExpr = instruction.callExpr ?: return null
        val (targets, precise) = resolveTargets(callExpr, instruction)
        return PIRInstructionCallResolution(
            instruction = instruction,
            callExpr = callExpr,
            targets = targets,
            precise = precise
        )
    }

    fun resolveTargets(instruction: PIRInst): Set<PIRResolvedCallTarget> =
        resolve(instruction)?.targets.orEmpty()

    private fun resolveTargets(expr: PIRExpr, instruction: PIRInst): Pair<Set<PIRResolvedCallTarget>, Boolean> {
        return when (expr) {
            is PIRDirectCallExpr -> linkedSetOf(index.resolveFunction(expr.funcDecl)) to true
            is PIRPrimitiveCallExpr -> linkedSetOf(PIRResolvedPrimitiveTarget(expr.primitive)) to !expr.primitive.isAmbiguous
            is PIRCallCExpr -> linkedSetOf(PIRResolvedCTarget(expr.functionName)) to true
            is PIRMethodCallExpr -> resolveMethodTargets(expr, instruction)
            else -> emptySet<PIRResolvedCallTarget>() to false
        }
    }

    private fun resolveMethodTargets(
        expr: PIRMethodCallExpr,
        instruction: PIRInst,
    ): Pair<Set<PIRResolvedCallTarget>, Boolean> {
        val receiverCandidates = resolveReceiverCandidates(ExprState(expr.obj, instruction))
            .ifEmpty { candidateFromType(expr.receiverType, exact = false) }

        if (receiverCandidates.isEmpty()) {
            return linkedSetOf(
                PIRResolvedFunctionTarget(
                    name = expr.method,
                    className = classNameFromType(expr.receiverType)
                )
            ) to false
        }

        val targets = linkedSetOf<PIRResolvedCallTarget>()
        var precise = true

        receiverCandidates.forEach { candidate ->
            if (!candidate.exact) {
                precise = false
            }
            targets += index.resolveMethod(candidate.className, expr.method)
        }

        return targets to precise
    }

    private fun resolveReceiverCandidates(
        state: ExprState,
        visited: MutableSet<ExprState> = hashSetOf(),
    ): Set<ReceiverCandidate> {
        if (!visited.add(state)) {
            return emptySet()
        }

        return when (val expr = state.expr) {
            is PIRRegister -> reachingExpressions(expr, state.at)
                .flatMapTo(linkedSetOf()) { resolveReceiverCandidates(it, visited) }
                .ifEmpty { candidateFromType(expr.type, exact = false) }

            is PIRArgument -> candidateFromType(expr.type, exact = false)
            is PIRMoveExpr -> resolveReceiverCandidates(ExprState(expr.value, state.at), visited)
            is PIRCastExpr -> resolveReceiverCandidates(ExprState(expr.operand, state.at), visited)
                .ifEmpty { candidateFromType(expr.type, exact = false) }

            is PIRBoxExpr -> resolveReceiverCandidates(ExprState(expr.operand, state.at), visited)
            is PIRUnboxExpr -> resolveReceiverCandidates(ExprState(expr.operand, state.at), visited)
            is PIRPhiExpr -> expr.values.flatMapTo(linkedSetOf()) {
                resolveReceiverCandidates(ExprState(it, state.at), visited)
            }

            is PIRLoadStaticExpr -> resolveStaticCandidate(expr).ifEmpty {
                candidateFromType(expr.type, exact = false)
            }

            is PIRLoadGlobalExpr -> candidateFromType(expr.type, exact = expr.type is PIRInstanceType)
            is PIRGetAttrExpr -> candidateFromType(expr.type, exact = false)
            is PIRDirectCallExpr -> candidateFromType(expr.type, exact = expr.type is PIRInstanceType)
            is PIRValue -> candidateFromType(expr.type, exact = false)
            else -> candidateFromType(expr.type, exact = false)
        }
    }

    private fun resolveStaticCandidate(expr: PIRLoadStaticExpr): Set<ReceiverCandidate> {
        if (expr.namespace != NAMESPACE_TYPE) {
            return emptySet()
        }

        val className = when {
            !expr.moduleName.isNullOrBlank() -> "${expr.moduleName}.${expr.identifier}"
            else -> expr.identifier
        }

        return linkedSetOf(ReceiverCandidate(className, exact = true))
    }

    private fun candidateFromType(type: PIRType, exact: Boolean): Set<ReceiverCandidate> {
        return when (type) {
            is PIRInstanceType -> classNameFromType(type)?.let { className ->
                linkedSetOf(ReceiverCandidate(className, exact))
            }.orEmpty()
            is PIRUnionType -> type.items.flatMapTo(linkedSetOf()) { candidateFromType(it, exact = false) }
            else -> emptySet()
        }
    }

    private fun classNameFromType(type: PIRType): String? {
        return when (type) {
            is PIRInstanceType -> type.name
            is PIRUnionType -> type.items.asSequence().mapNotNull(::classNameFromType).singleOrNull()
            else -> null
        }
    }

    private fun reachingExpressions(register: PIRRegister, at: PIRInst): Set<ExprState> {
        val result = linkedSetOf<ExprState>()
        val queue = ArrayDeque<PIRInst>()
        val visited = hashSetOf<PIRInst>()

        predecessorsOf(at).forEach(queue::addLast)
        if (queue.isEmpty()) {
            result += ExprState(register, at)
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) {
                continue
            }

            val assignedRegister = (current as? PIRAssignInst)?.lhv as? PIRRegister
            if (assignedRegister != null && assignedRegister.matches(register)) {
                result += ExprState(current.rhv, current)
                continue
            }

            val predecessors = predecessorsOf(current)
            if (predecessors.isEmpty()) {
                result += ExprState(register, current)
                continue
            }

            predecessors.forEach(queue::addLast)
        }

        return result
    }

    private fun predecessorsOf(inst: PIRInst): Set<PIRInst> {
        return graph.predecessors(inst)
            .mapNotNullTo(linkedSetOf()) { it as? PIRInst }
    }

    private fun PIRRegister.matches(other: PIRRegister): Boolean {
        return name == other.name &&
            type.typeName == other.type.typeName &&
            isArg == other.isArg
    }

    private data class ExprState(
        val expr: PIRExpr,
        val at: PIRInst,
    )

    private data class ReceiverCandidate(
        val className: String,
        val exact: Boolean,
    )
}

val PIRInst.callExpr: PIRExpr?
    get() = when (this) {
        is PIRAssignInst -> this.rhv.takeIf { it is PIRDirectCallExpr || it is PIRMethodCallExpr || it is PIRPrimitiveCallExpr || it is PIRCallCExpr }
        else -> null
    }

fun PIRModule.callTargetIndex(): PIRCallTargetIndex = PIRCallTargetIndex.fromModule(this)

fun PIRFunc.callResolver(index: PIRCallTargetIndex = PIRCallTargetIndex.EMPTY): PIRInstructionCallResolver =
    PIRInstructionCallResolver(this, index)
