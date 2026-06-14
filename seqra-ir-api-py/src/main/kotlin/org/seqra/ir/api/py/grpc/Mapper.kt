package org.seqra.ir.api.py.mapper

import ir.*
import ir.Function
import org.seqra.ir.api.py.*
import org.seqra.ir.api.py.cfg.*
import java.util.IdentityHashMap

class ProtoToPirMapper {

    private data class LocalFunctionInfo(
        val enclosingFunction: String? = null,
        val isLocal: Boolean = false,
        val isLambda: Boolean = false
    )

    private val shallowClassCache = mutableMapOf<String, PIRClass>()
    private val moduleOwnerCache = mutableMapOf<String, PIRClass>()
    private val syntheticResultCache = IdentityHashMap<Op, PIRRegister>()
    private val syntheticResultAliasCache = mutableMapOf<String, MutableList<PIRRegister>>()
    private var nextSyntheticResultId = 0

    fun mapComplete(response: CompleteResponse): List<PIRModule> {
        return response.modules.modulesList.map(::mapModule)
    }

    fun mapModule(proto: Module): PIRModule {
        val owner = syntheticModuleOwner(proto.fullname)

        val classes = proto.classesList.map(::mapClass)
        val functions = proto.functionsList.map { mapFunction(it, owner) }

        return PIRModule(
            fullname = proto.fullname,
            imports = proto.importsList,
            functions = functions,
            classes = classes,
            finalNames = proto.finalNamesList.map { it.name to mapType(it.type) },
            typeVarNames = proto.typeVarNamesList,
            dependencies = proto.dependenciesList.mapNotNull(::mapDependency).toSet()
        )
    }

    fun mapClass(proto: ir.Class): PIRClass {
        val ownerRef = shallowClass(proto)

        return PIRClass(
            name = proto.name,
            moduleName = proto.moduleName,
            isTrait = proto.isTrait,
            isGenerated = proto.isGenerated,
            isAbstract = proto.isAbstract,
            isExtClass = proto.isExtClass,
            isFinalClass = proto.isFinalClass,
            isAugmented = proto.isAugmented,
            inheritsPython = proto.inheritsPython,
            hasDict = proto.hasDict,
            allowInterpretedSubclasses = proto.allowInterpretedSubclasses,
            needsGetters = proto.needsGetseters,
            serializable = proto.serializable,
            builtinBase = proto.builtinBase.takeIf { it.isNotBlank() },
            ctor = mapFuncDecl(proto.ctor, emptyList()),
            setup = mapFuncDecl(proto.setup, emptyList()),
            attributes = proto.attributesMap.mapValues { (_, v) -> mapType(v) },
            deletable = proto.deletableList,
            methodDecls = proto.methodDeclsMap.mapValues { (_, v) -> mapFuncDecl(v, emptyList()) },
            methods = proto.methodsMap.mapValues { (_, v) -> mapFunction(v, ownerRef) },
            glueMethods = proto.glueMethodsList.associate { mapGlueMethod(it, ownerRef) },
            properties = proto.propertiesMap.mapValues { (_, v) -> mapFuncFunc(v, ownerRef) },
            propertyTypes = proto.propertyTypesMap.mapValues { (_, v) -> mapType(v) },
            vtable = proto.vtableMap,
            vtableEntries = proto.vtableEntries.entriesList.map { mapVTableMethod(it, ownerRef) },
            traitVtables = proto.traitVtablesList.associate { entry ->
                shallowClass(entry.trait) to entry.vtable.entriesList.map { mapVTableMethod(it, ownerRef) }
            },
            base = if (proto.hasBase()) shallowClass(proto.base) else null,
            traits = proto.traitsList.map(::shallowClass),
            mro = proto.mroList.map(::shallowClass),
            baseMro = proto.baseMroList.map(::shallowClass),
            children = proto.childrenList.map(::shallowClass),
            childrenKnown = if (proto.hasChildrenKnown()) proto.childrenKnown else true,
            attrsWithDefaults = proto.attrsWithDefaultsList.toSet(),
            alwaysInitializedAttrs = proto.alwaysInitializedAttrsList.toSet(),
            sometimesInitializedAttrs = proto.sometimesInitializedAttrsList.toSet(),
            initSelfLeak = proto.initSelfLeak,
            bitmapAttrs = proto.bitmapAttrsList,
            envUserFunction = if (proto.hasEnvUserFunction()) mapFunction(proto.envUserFunction, ownerRef) else null,
            reuseFreedInstance = proto.reuseFreedInstance,
            isAcyclic = proto.isAcyclic,
            isEnum = proto.isEnum,
            coroutineName = proto.coroutineName.takeIf { it.isNotBlank() }
        )
    }

    fun mapFunction(proto: Function, owner: PIRClass? = null): PIRFunc {
        val resolvedOwner = owner ?: syntheticModuleOwner(proto.decl.moduleName)
        val decl = mapFuncDecl(proto.decl, proto.argRegsList, proto.tracebackName.takeIf { it.isNotBlank() })
        val argRegs = proto.argRegsList.map(::mapRegister)
        syntheticResultCache.clear()
        syntheticResultAliasCache.clear()
        nextSyntheticResultId = 0

        val blockRanges = computeBlockRanges(proto.blocksList)

        class Holder {
            lateinit var func: PIRFunc
        }
        val holder = Holder()

        val instructions = mutableListOf<PIRInst>()

        proto.blocksList.forEach { block ->
            val range = blockRanges.getValue(block.label)
            block.opsList.forEachIndexed { localIndex, op ->
                val globalIndex = range.first + localIndex
                instructions += mapOp(
                    op = op,
                    index = globalIndex,
                    methodProvider = { holder.func },
                    blockRanges = blockRanges,
                    instructionsProvider = { instructions }
                )
            }
        }

        val blocks = proto.blocksList.map { block ->
            val range = blockRanges.getValue(block.label)
            PIRBasicBlock(
                start = PIRInstRef(range.first, instructions.getOrNull(range.first)),
                end = PIRInstRef(range.last, instructions.getOrNull(range.last)),
                errorHandler = if (block.hasErrorHandler()) {
                    blockRanges[block.errorHandler.label]?.first?.let { handlerIndex ->
                        PIRInstRef(handlerIndex, instructions.getOrNull(handlerIndex))
                    }
                } else null
            )
        }

        val func = PIRFunc(
            decl = decl,
            argRegs = argRegs,
            instructions = instructions,
            blocks = blocks,
            tracebackName = proto.tracebackName.takeIf { it.isNotBlank() },
            enclosingClass = resolvedOwner
        )
        holder.func = func

        return func
    }

    private fun mapOp(
        op: Op,
        index: Int,
        methodProvider: () -> PIRFunc,
        blockRanges: Map<Int, IntRange>,
        instructionsProvider: () -> List<PIRInst>
    ): PIRInst {
        val line = opLine(op)

        val location = object : PIRInstLocation {
            override val method: PIRFunc
                get() = methodProvider()
            override val index: Int = index
            override val line: Int = line
        }

        return when (op.opCase) {
            Op.OpCase.BASE_ASSING -> mapBaseAssign(op.baseAssing, location)
            Op.OpCase.CONTROL_OP -> mapControlOp(op.controlOp, location, blockRanges, instructionsProvider)
            Op.OpCase.REGISTER_OP -> mapRegisterOp(op, op.registerOp, location)
            Op.OpCase.OP_NOT_SET,
            null -> noOpInst(location)
        }
    }

    private fun mapBaseAssign(
        proto: ir.BaseAssign,
        location: PIRInstLocation
    ): PIRInst {
        val dest = mapRegister(proto.dest)

        return when (proto.baseAssingCase) {
            BaseAssign.BaseAssingCase.ASSIGN -> PIRAssignInst(
                location = location,
                lhv = dest,
                rhv = PIRMoveExpr(
                    value = mapValue(proto.assign.src),
                    type = dest.type,
                    line = location.line
                )
            )

            BaseAssign.BaseAssingCase.ASSING_MULTI -> PIRAssignInst(
                location = location,
                lhv = dest,
                rhv = PIRTupleExpr(
                    items = proto.assingMulti.srcList.map(::mapValue),
                    type = dest.type,
                    line = location.line
                )
            )

            null,
            BaseAssign.BaseAssingCase.BASEASSING_NOT_SET -> assignExpr(
                location = location,
                result = dest,
                expr = undefinedExpr(dest.type, location.line, dest.isBorrowed)
            )
        }
    }

    private fun mapControlOp(
        proto: ControlOp,
        location: PIRInstLocation,
        blockRanges: Map<Int, IntRange>,
        instructionsProvider: () -> List<PIRInst>
    ): PIRInst {
        return when (proto.controlOpCase) {
            ControlOp.ControlOpCase.GOTO -> {
                val targetRange = blockRanges.getValue(proto.goto.label)
                PIRGotoInst(
                    location = location,
                    target = PIRInstRef(targetRange.first, instructionsProvider().getOrNull(targetRange.first))
                )
            }

            ControlOp.ControlOpCase.BRANCH -> {
                val branch = proto.branch
                val trueRange = blockRanges.getValue(branch.trueLabel.label)
                val falseRange = blockRanges.getValue(branch.falseLabel.label)

                val conditionValue = mapValue(branch.value)
                val condition: PIRConditionExpr =
                    if (branch.op == Branch.OpType.IS_ERROR) {
                        PIRErrorCheckExpr(
                            value = conditionValue,
                            line = location.line
                        )
                    } else {
                        PIRTruthExpr(
                            value = conditionValue,
                            line = location.line
                        )
                    }

                PIRIfInst(
                    location = location,
                    condition = condition,
                    trueBranch = PIRInstRef(trueRange.first, instructionsProvider().getOrNull(trueRange.first)),
                    falseBranch = PIRInstRef(falseRange.first, instructionsProvider().getOrNull(falseRange.first)),
                    negated = branch.negated,
                    tracebackEntry = if (branch.hasTracebackEntry()) {
                        branch.tracebackEntry.funcName to branch.tracebackEntry.line
                    } else null,
                    rare = branch.rare
                )
            }

            ControlOp.ControlOpCase.RETURN -> {
                val value = if (proto.`return`.hasValue()) mapValue(proto.`return`.value) else null
                val yieldRef = proto.`return`.yieldTarget.takeIf { it != 0 }?.let { label ->
                    val range = blockRanges.getValue(label)
                    PIRInstRef(range.first, instructionsProvider().getOrNull(range.first))
                }

                PIRReturnInst(
                    location = location,
                    returnValue = value,
                    yieldTarget = yieldRef
                )
            }

            ControlOp.ControlOpCase.UNREACHABLE -> PIRUnreachableInst(location)

            null,
            ControlOp.ControlOpCase.CONTROLOP_NOT_SET -> noOpInst(location)
        }
    }

    private fun mapRegisterOp(
        op: Op,
        proto: RegisterOp,
        location: PIRInstLocation
    ): PIRInst {
        val errorKind = mapErrorKind(proto.errorKind)

        return when (proto.registerOpCase) {
            RegisterOp.RegisterOpCase.INC_REF -> PIREffectInst(
                location,
                PIRIncRefExpr(
                    src = mapValue(proto.incRef.src),
                    line = location.line
                )
            )

            RegisterOp.RegisterOpCase.DEC_REF -> PIREffectInst(
                location,
                PIRDecRefExpr(
                    src = mapValue(proto.decRef.src),
                    xdec = proto.decRef.isXdec,
                    line = location.line
                )
            )

            RegisterOp.RegisterOpCase.CALL -> assignRegisterOp(op, "call", location) {
                PIRDirectCallExpr(
                    funcDecl = mapFuncDecl(proto.call.fn, emptyList()),
                    args = mapValues(proto.call.argsList),
                    type = mapType(proto.call.type),
                    line = location.line,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.METHOD_CALL -> assignRegisterOp(op, "method_call", location) {
                PIRMethodCallExpr(
                    obj = mapValue(proto.methodCall.obj),
                    method = proto.methodCall.method,
                    args = mapValues(proto.methodCall.argsList),
                    receiverType = mapType(proto.methodCall.receiverType),
                    type = mapType(proto.methodCall.type),
                    line = location.line,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.PRIMITIVE_OP -> assignRegisterOp(op, "primitive_op", location) {
                PIRPrimitiveCallExpr(
                    primitive = mapPrimitiveDescription(proto.primitiveOp.desc),
                    args = mapValues(proto.primitiveOp.argsList),
                    type = mapType(proto.primitiveOp.type),
                    line = location.line,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.LOAD_ERROR_VALUE -> assignRegisterOp(op, "load_error_value", location) {
                PIRLoadErrorValueExpr(
                    undefines = proto.loadErrorValue.undefines,
                    type = mapType(proto.loadErrorValue.type),
                    line = location.line,
                    isBorrowed = proto.loadErrorValue.isBorrowed,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.LOAD_LITERAL -> assignRegisterOp(op, "load_literal", location) {
                PIRLiteralExpr(
                    literal = mapLiteralValue(proto.loadLiteral.value, mapType(proto.loadLiteral.type)),
                    type = mapType(proto.loadLiteral.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.GET_ATTR -> assignRegisterOp(op, "get_attr", location) {
                PIRGetAttrExpr(
                    obj = mapValue(proto.getAttr.obj),
                    attr = proto.getAttr.attr,
                    classType = mapType(proto.getAttr.classType),
                    allowErrorValue = proto.getAttr.allowErrorValue,
                    type = mapType(proto.getAttr.classType),
                    line = location.line,
                    isBorrowed = proto.getAttr.isBorrowed,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.SET_ATTR -> PIREffectInst(
                location,
                PIRSetAttrExpr(
                    obj = mapValue(proto.setAttr.obj),
                    attr = proto.setAttr.attr,
                    src = mapValue(proto.setAttr.src),
                    classType = mapType(proto.setAttr.classType),
                    init = proto.setAttr.isInit,
                    type = mapType(proto.setAttr.type),
                    line = location.line,
                    errorKind = errorKind
                )
            )

            RegisterOp.RegisterOpCase.LOAD_STATIC -> assignRegisterOp(op, "load_static", location) {
                PIRLoadStaticExpr(
                    identifier = proto.loadStatic.identifier,
                    moduleName = proto.loadStatic.moduleName.takeIf { it.isNotBlank() },
                    namespace = proto.loadStatic.namespace.ifBlank { NAMESPACE_STATIC },
                    ann = proto.loadStatic.ann,
                    type = mapType(proto.loadStatic.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.INIT_STATIC -> PIREffectInst(
                location,
                PIRInitStaticExpr(
                    identifier = proto.initStatic.identifier,
                    moduleName = proto.initStatic.moduleName.takeIf { it.isNotBlank() },
                    namespace = proto.initStatic.namespace.ifBlank { NAMESPACE_STATIC },
                    value = mapValue(proto.initStatic.value),
                    line = location.line
                )
            )

            RegisterOp.RegisterOpCase.TUPLE_SET -> assignRegisterOp(op, "tuple_set", location) {
                PIRTupleExpr(
                    items = mapValues(proto.tupleSet.itemsList),
                    type = mapType(proto.tupleSet.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.TUPLE_GET -> assignRegisterOp(op, "tuple_get", location) {
                PIRTupleGetExpr(
                    tuple = mapValue(proto.tupleGet.src),
                    index = proto.tupleGet.index,
                    type = mapType(proto.tupleGet.type),
                    line = location.line,
                    isBorrowed = proto.tupleGet.isBorrowed
                )
            }

            RegisterOp.RegisterOpCase.CAST -> assignRegisterOp(op, "cast", location) {
                PIRCastExpr(
                    operand = mapValue(proto.cast.src),
                    unchecked = proto.cast.isUnchecked,
                    type = mapType(proto.cast.type),
                    line = location.line,
                    isBorrowed = proto.cast.isBorrowed,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.BOX -> assignRegisterOp(op, "box", location) {
                PIRBoxExpr(
                    operand = mapValue(proto.box.src),
                    type = mapType(proto.box.type),
                    line = location.line,
                    isBorrowed = proto.box.isBorrowed
                )
            }

            RegisterOp.RegisterOpCase.UNBOX -> assignRegisterOp(op, "unbox", location) {
                PIRUnboxExpr(
                    operand = mapValue(proto.unbox.src),
                    type = mapType(proto.unbox.type),
                    line = location.line,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.RAISE_STANDARD_ERROR -> PIREffectInst(
                location,
                PIRRaiseStandardErrorExpr(
                    className = proto.raiseStandardError.className,
                    value = when (proto.raiseStandardError.valueTypeCase) {
                        RaiseStandardError.ValueTypeCase.STR_VALUE -> proto.raiseStandardError.strValue
                        RaiseStandardError.ValueTypeCase.VALUE -> mapValue(proto.raiseStandardError.value)
                        RaiseStandardError.ValueTypeCase.VALUETYPE_NOT_SET,
                        null -> null
                    },
                    type = mapPrimitiveEmbedded(proto.raiseStandardError.type, "raise_error_type"),
                    line = location.line,
                    errorKind = ERR_FALSE
                )
            )

            RegisterOp.RegisterOpCase.CALL_C -> assignRegisterOp(op, "call_c", location) {
                PIRCallCExpr(
                    functionName = proto.callC.functionName,
                    args = mapValues(proto.callC.argsList),
                    steals = mapSteals(proto.callC.steals),
                    varArgIdx = proto.callC.varArgIdx,
                    isPure = proto.callC.isPure,
                    returnsNull = proto.callC.returnsNull,
                    dependencies = proto.callC.dependenciesList.mapNotNull(::mapDependency),
                    type = mapType(proto.callC.type),
                    line = location.line,
                    isBorrowed = proto.callC.isBorrowed,
                    errorKind = errorKind
                )
            }

            RegisterOp.RegisterOpCase.TRUNCATE -> assignRegisterOp(op, "truncate", location) {
                PIRCastExpr(
                    operand = mapValue(proto.truncate.src),
                    unchecked = false,
                    type = mapType(proto.truncate.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.EXTEND -> assignRegisterOp(op, "extend", location) {
                PIRCastExpr(
                    operand = mapValue(proto.extend.src),
                    unchecked = false,
                    type = mapType(proto.extend.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.LOAD_GLOBAL -> assignRegisterOp(op, "load_global", location) {
                PIRLoadGlobalExpr(
                    identifier = proto.loadGlobal.identifier,
                    ann = proto.loadGlobal.ann,
                    type = mapType(proto.loadGlobal.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.INT_OP -> assignRegisterOp(op, "int_op", location) {
                PIRIntBinExpr(
                    lhs = mapValue(proto.intOp.lhs),
                    rhs = mapValue(proto.intOp.rhs),
                    op = mapIntOpKind(proto.intOp.op),
                    type = mapType(proto.intOp.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.COMPARISON_OP -> assignRegisterOp(op, "comparison_op", location) {
                PIRCmpExpr(
                    lhs = mapValue(proto.comparisonOp.lhs),
                    rhs = mapValue(proto.comparisonOp.rhs),
                    op = mapCmpKind(proto.comparisonOp.op),
                    type = mapType(proto.comparisonOp.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.FLOAT_OP -> assignRegisterOp(op, "float_op", location) {
                PIRFloatBinExpr(
                    lhs = mapValue(proto.floatOp.lhs),
                    rhs = mapValue(proto.floatOp.rhs),
                    op = mapFloatOpKind(proto.floatOp.op),
                    type = mapType(proto.floatOp.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.FLOAT_NEG -> assignRegisterOp(op, "float_neg", location) {
                PIRFloatNegExpr(
                    operand = mapValue(proto.floatNeg.src),
                    type = mapType(proto.floatNeg.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.FLOAT_COMPARISON_OP -> assignRegisterOp(op, "float_cmp", location) {
                PIRCmpExpr(
                    lhs = mapValue(proto.floatComparisonOp.lhs),
                    rhs = mapValue(proto.floatComparisonOp.rhs),
                    op = mapFloatCmpKind(proto.floatComparisonOp.op),
                    type = mapType(proto.floatComparisonOp.type),
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.LOAD_MEM -> assignRegisterOp(op, "load_mem", location) {
                PIRLoadMemExpr(
                    address = mapValue(proto.loadMem.src),
                    type = mapType(proto.loadMem.type),
                    line = location.line,
                    isBorrowed = proto.loadMem.isBorrowed
                )
            }

            RegisterOp.RegisterOpCase.GET_ELEMENT -> assignRegisterOp(op, "get_element", location) {
                PIRGetElementExpr(
                    src = mapValue(proto.getElement.src),
                    srcType = mapType(proto.getElement.srcType),
                    field = proto.getElement.field,
                    type = mapType(proto.getElement.type),
                    line = location.line,
                    isBorrowed = proto.getElement.isBorrowed
                )
            }

            RegisterOp.RegisterOpCase.GET_ELEMENT_PTR -> assignRegisterOp(op, "get_element_ptr", location) {
                PIRGetElementPtrExpr(
                    src = mapValue(proto.getElementPtr.src),
                    srcType = mapPrimitiveEmbedded(proto.getElementPtr.srcType, "gep_src"),
                    field = proto.getElementPtr.field,
                    type = PIRPrimitiveTypes.POINTER,
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.SET_ELEMENT -> PIREffectInst(
                location,
                PIRSetElementExpr(
                    src = mapValue(proto.setElement.src),
                    field = proto.setElement.field,
                    item = mapValue(proto.setElement.item),
                    line = location.line
                )
            )

            RegisterOp.RegisterOpCase.SET_MEM -> PIREffectInst(
                location,
                PIRSetMemExpr(
                    destType = mapType(proto.setMem.destType),
                    dest = mapValue(proto.setMem.dest),
                    src = mapValue(proto.setMem.src),
                    line = location.line
                )
            )

            RegisterOp.RegisterOpCase.LOAD_ADDRESS -> assignRegisterOp(op, "load_address", location) {
                PIRLoadAddressExpr(
                    target = when (proto.loadAddress.srcTypeCase) {
                        LoadAddress.SrcTypeCase.STR_SRC -> proto.loadAddress.strSrc
                        LoadAddress.SrcTypeCase.REG_SRC -> mapRegister(proto.loadAddress.regSrc)
                        LoadAddress.SrcTypeCase.STATIC_SRC -> PIRLoadStaticExpr(
                            identifier = proto.loadAddress.staticSrc.identifier,
                            moduleName = proto.loadAddress.staticSrc.moduleName.takeIf { it.isNotBlank() },
                            namespace = proto.loadAddress.staticSrc.namespace.ifBlank { NAMESPACE_STATIC },
                            ann = proto.loadAddress.staticSrc.ann,
                            type = mapType(proto.loadAddress.staticSrc.type),
                            line = location.line
                        )
                        LoadAddress.SrcTypeCase.SRCTYPE_NOT_SET,
                        null -> ""
                    },
                    type = PIRPrimitiveTypes.POINTER,
                    line = location.line
                )
            }

            RegisterOp.RegisterOpCase.KEEP_ALIVE -> PIREffectInst(
                location,
                PIRKeepAliveExpr(
                    src = mapValues(proto.keepAlive.srcList),
                    line = location.line
                )
            )

            RegisterOp.RegisterOpCase.UNBORROW -> PIREffectInst(
                location,
                PIRUnborrowExpr(
                    src = mapValue(proto.unborrow.src),
                    line = location.line
                )
            )

            null,
            RegisterOp.RegisterOpCase.REGISTEROP_NOT_SET -> unsupportedRegisterOp(op, location)
        }
    }

    private fun assignExpr(
        location: PIRInstLocation,
        result: PIRRegister,
        expr: PIRExpr
    ): PIRAssignInst {
        return PIRAssignInst(
            location = location,
            lhv = result,
            rhv = expr
        )
    }

    private fun assignRegisterOp(
        op: Op,
        fallbackName: String,
        location: PIRInstLocation,
        exprBuilder: () -> PIRExpr
    ): PIRAssignInst {
        val expr = exprBuilder()
        val result = resultRegisterOf(op, fallbackName, location.line)
        return assignExpr(location, result, expr)
    }

    fun mapValue(proto: ir.Value): PIRValue {
        return when (proto.valueCase) {
            ir.Value.ValueCase.REGISTER -> mapRegister(proto.register)

            ir.Value.ValueCase.INTEGER -> PIRInteger(
                value = proto.integer.value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                type = mapType(proto.integer.type),
                line = proto.integer.line,
                isBorrowed = proto.isBorrowed
            )

            ir.Value.ValueCase.FLOAT_VAL -> PIRFloat(
                value = proto.floatVal.value,
                type = mapType(proto.floatVal.type),
                line = proto.floatVal.line,
                isBorrowed = proto.isBorrowed
            )

            ir.Value.ValueCase.CSTRING -> PIRCString(
                value = proto.cstring.value.toByteArray(),
                type = mapType(proto.cstring.type),
                line = proto.cstring.line,
                isBorrowed = proto.isBorrowed
            )

            ir.Value.ValueCase.UNDEF -> PIRUndef(
                type = mapType(proto.undef.type),
                line = proto.line,
                isBorrowed = proto.isBorrowed
            )

            ir.Value.ValueCase.OP -> resolveValueOpRegister(proto.op, proto.line)

            ir.Value.ValueCase.VALUE_NOT_SET,
            null -> PIRUndef(
                type = mapType(proto.type),
                line = proto.line,
                isBorrowed = proto.isBorrowed
            )
        }
    }

    fun mapRegister(proto: Register): PIRRegister {
        return PIRRegister(
            name = proto.name,
            type = mapType(proto.type),
            line = proto.line,
            isBorrowed = proto.isBorrowed,
            isArg = proto.isArg
        )
    }

    fun mapType(proto: RType): PIRType {
        return when (proto.typeKindCase) {
            RType.TypeKindCase.RVOID -> PIRVoidType(
                name = proto.name.ifBlank { "void" },
                isUnboxed = proto.isUnboxed,
                isRefcounted = proto.isRefcounted,
                errorOverlap = proto.errorOverlap
            )

            RType.TypeKindCase.RPRIMITIVE -> mapPrimitiveType(proto)

            RType.TypeKindCase.RTUPLE -> PIRTupleType(
                types = proto.rtuple.typesList.map(::mapType),
                name = proto.name.ifBlank { "tuple" },
                isUnboxed = proto.isUnboxed,
                isRefcounted = proto.isRefcounted,
                errorOverlap = proto.errorOverlap,
                uniqueId = proto.rtuple.uniqueId,
                structName = proto.rtuple.structName
            )

            RType.TypeKindCase.RSTRUCT -> PIRStructType(
                name = proto.name.ifBlank { "struct" },
                names = proto.rstruct.namesList,
                types = proto.rstruct.typesList.map(::mapType),
                isUnboxed = proto.isUnboxed,
                isRefcounted = proto.isRefcounted,
                errorOverlap = proto.errorOverlap,
                offsets = proto.rstruct.offsetsList,
                size = proto.rstruct.size
            )

            RType.TypeKindCase.RINSTANCE -> PIRInstanceType(
                classIr = shallowClass(proto.rinstance.classIr),
                name = proto.name.ifBlank { shallowClass(proto.rinstance.classIr).fullname },
                isUnboxed = proto.isUnboxed,
                isRefcounted = proto.isRefcounted,
                errorOverlap = proto.errorOverlap,
                ctype = proto.ctype.ifBlank { "PyObject *" }
            )

            RType.TypeKindCase.RUNION -> {
                val items = proto.runion.itemsList.map(::mapType)
                PIRUnionType(
                    items = items,
                    name = proto.name.ifBlank { "union" },
                    isUnboxed = proto.isUnboxed,
                    isRefcounted = proto.isRefcounted,
                    errorOverlap = proto.errorOverlap,
                    itemsSet = if (proto.runion.itemsSetCount > 0) {
                        proto.runion.itemsSetList.map(::mapType).toSet()
                    } else {
                        items.toSet()
                    }
                )
            }

            RType.TypeKindCase.RARRAY -> {
                val itemType = mapType(proto.rarray.itemType)
                PIRArrayType(
                    itemType = itemType,
                    length = proto.rarray.length,
                    name = proto.name.ifBlank { "${itemType.name}[${proto.rarray.length}]" },
                    isUnboxed = proto.isUnboxed,
                    isRefcounted = proto.isRefcounted,
                    errorOverlap = proto.errorOverlap
                )
            }

            RType.TypeKindCase.TYPEKIND_NOT_SET,
            null -> PIRPrimitiveType(
                name = proto.name.ifBlank { "unknown" },
                isUnboxed = proto.isUnboxed,
                isRefcounted = proto.isRefcounted,
                errorOverlap = proto.errorOverlap,
                ctype = proto.ctype.ifBlank { "PyObject *" },
                cUndefined = proto.cUndefined.ifBlank { "NULL" }
            )
        }
    }

    private fun mapPrimitiveType(proto: RType): PIRType {
        val name = proto.name

        return when (name) {
            PIRPrimitiveTypes.OBJECT.name -> PIRPrimitiveTypes.OBJECT
            PIRPrimitiveTypes.INT.name -> PIRPrimitiveTypes.INT
            PIRPrimitiveTypes.STR.name -> PIRPrimitiveTypes.STR
            PIRPrimitiveTypes.LIST.name -> PIRPrimitiveTypes.LIST
            PIRPrimitiveTypes.DICT.name -> PIRPrimitiveTypes.DICT
            PIRPrimitiveTypes.BOOL.name -> PIRPrimitiveTypes.BOOL
            PIRPrimitiveTypes.NONE.name -> PIRPrimitiveTypes.NONE
            PIRPrimitiveTypes.FLOAT.name -> PIRPrimitiveTypes.FLOAT
            PIRPrimitiveTypes.BYTES.name -> PIRPrimitiveTypes.BYTES
            PIRPrimitiveTypes.TUPLE.name -> PIRPrimitiveTypes.TUPLE
            PIRPrimitiveTypes.INT32.name -> PIRPrimitiveTypes.INT32
            PIRPrimitiveTypes.INT64.name -> PIRPrimitiveTypes.INT64
            PIRPrimitiveTypes.BITMAP.name -> PIRPrimitiveTypes.BITMAP
            PIRPrimitiveTypes.POINTER.name -> PIRPrimitiveTypes.POINTER
            PIRPrimitiveTypes.C_POINTER.name -> PIRPrimitiveTypes.C_POINTER
            PIRPrimitiveTypes.C_STRING.name -> PIRPrimitiveTypes.C_STRING

            else -> PIRPrimitiveType(
                name = name.ifBlank { "primitive" },
                isUnboxed = proto.isUnboxed,
                isRefcounted = proto.isRefcounted,
                errorOverlap = proto.errorOverlap,
                mayBeImmortal = proto.rprimitive.mayBeImmortal,
                isNativeInt = proto.rprimitive.isNativeInt,
                isSigned = proto.rprimitive.isSigned,
                ctype = proto.ctype.ifBlank { "PyObject *" },
                size = proto.rprimitive.size,
                cUndefined = proto.cUndefined.ifBlank { "NULL" }
            )
        }
    }

    private fun mapPrimitiveEmbedded(proto: RPrimitive, fallbackName: String): PIRPrimitiveType {
        return PIRPrimitiveType(
            name = fallbackName,
            isUnboxed = true,
            isRefcounted = false,
            errorOverlap = false,
            mayBeImmortal = proto.mayBeImmortal,
            isNativeInt = proto.isNativeInt,
            isSigned = proto.isSigned,
            size = proto.size,
            ctype = "primitive",
            cUndefined = "0"
        )
    }

    private fun mapFuncDecl(proto: FuncDecl, argRegs: List<Register>, tracebackName: String? = null): PIRFuncDecl {
        val args = when {
            proto.sig.runtimeArgsCount > 0 -> proto.sig.runtimeArgsList.map(::mapRuntimeArg)
            argRegs.isNotEmpty() -> argRegs.map { reg ->
                PIRRuntimeArg(
                    name = reg.name,
                    type = mapType(reg.type),
                    kind = ARG_POS,
                    posOnly = false
                )
            }
            else -> proto.sig.argsList.mapIndexed { idx, argType ->
                PIRRuntimeArg(
                    name = "arg$idx",
                    type = mapType(argType),
                    kind = ARG_POS,
                    posOnly = false
                )
            }
        }

        val localInfo = inferLocalFunctionInfo(
            name = proto.name,
            className = proto.className.takeIf { proto.hasClassName() },
            tracebackName = tracebackName
        )

        return PIRFuncDecl(
            name = proto.name,
            className = if (proto.hasClassName()) proto.className else null,
            moduleName = proto.moduleName,
            sig = PIRFuncSignature(
                args = args,
                retType = mapType(proto.sig.retType),
                numBitmapArgs = proto.sig.numBitmapArgs
            ),
            boundSig = proto.takeIf { it.hasBoundSig() }?.boundSig?.let { boundSig ->
                PIRFuncSignature(
                    args = when {
                        boundSig.runtimeArgsCount > 0 -> boundSig.runtimeArgsList.map(::mapRuntimeArg)
                        else -> boundSig.argsList.mapIndexed { idx, argType ->
                            PIRRuntimeArg(
                                name = "arg$idx",
                                type = mapType(argType),
                                kind = ARG_POS,
                                posOnly = false
                            )
                        }
                    },
                    retType = mapType(boundSig.retType),
                    numBitmapArgs = boundSig.numBitmapArgs
                )
            },
            kind = when (proto.kind) {
                FunctionKind.FUNC_STATICMETHOD -> PIR_FUNC_STATICMETHOD
                FunctionKind.FUNC_CLASSMETHOD -> PIR_FUNC_CLASSMETHOD
                else -> PIR_FUNC_NORMAL
            },
            isPropSetter = proto.isPropSetter,
            isPropGetter = proto.isPropGetter,
            isGenerator = proto.isGenerator,
            isCoroutine = proto.isCoroutine,
            enclosingFunction = localInfo.enclosingFunction,
            isLocal = localInfo.isLocal,
            isLambda = localInfo.isLambda,
            implicit = proto.implicit,
            internal = proto.internal,
            line = if (proto.hasLine()) proto.line else null
        )
    }

    private fun inferLocalFunctionInfo(
        name: String,
        className: String?,
        tracebackName: String?
    ): LocalFunctionInfo {
        if (name != "__call__" || className.isNullOrBlank() || !className.endsWith("_obj")) {
            return LocalFunctionInfo()
        }

        val callableStem = className.removeSuffix("_obj")
        val lambdaMatch = LAMBDA_CALLABLE_NAME.matchEntire(callableStem)
        if (lambdaMatch != null) {
            val enclosing = lambdaMatch.groupValues.getOrNull(1).orEmpty().ifBlank { null }
            return LocalFunctionInfo(
                enclosingFunction = enclosing,
                isLocal = enclosing != null,
                isLambda = true
            )
        }

        val sourceName = tracebackName
            ?.takeIf { it.isNotBlank() && it != "<lambda>" && it != "<module>" }
            ?: return LocalFunctionInfo()
        val prefix = "${sourceName}_"
        if (!callableStem.startsWith(prefix)) {
            return LocalFunctionInfo()
        }

        val enclosing = callableStem.removePrefix(prefix).ifBlank { null }
        return LocalFunctionInfo(
            enclosingFunction = enclosing,
            isLocal = enclosing != null,
            isLambda = false
        )
    }

    private companion object {
        val LAMBDA_CALLABLE_NAME = Regex("""^__mypyc_lambda__\d+(?:_(.+))?$""")
    }

    private fun mapRuntimeArg(proto: ir.RuntimeArg): PIRRuntimeArg {
        return PIRRuntimeArg(
            name = proto.name,
            type = mapType(proto.type),
            kind = proto.kind,
            posOnly = proto.posOnly
        )
    }

    private fun mapFuncFunc(proto: FuncFunc, owner: PIRClass): Pair<PIRFunc, PIRFunc?> {
        val first = mapFunction(proto.func1, owner)
        val second = if (proto.hasFunc2()) mapFunction(proto.func2, owner) else null
        return first to second
    }

    private fun mapGlueMethod(entry: GlueMethodEntry, owner: PIRClass): Pair<Pair<PIRClass, String>, PIRFunc> {
        val keyClass = shallowClass(entry.key.class_)
        return (keyClass to entry.key.name) to mapFunction(entry.value, owner)
    }

    private fun mapVTableMethod(proto: VTableMethod, owner: PIRClass): PIRVTableMethod {
        return PIRVTableMethod(
            cls = shallowClass(proto.cls),
            name = proto.name,
            method = mapFunction(proto.method, owner),
            shadowMethod = if (proto.hasShadowMethod()) mapFunction(proto.shadowMethod, owner) else null
        )
    }

    private fun mapPrimitiveDescription(proto: PrimitiveDescription): PIRPrimitiveDescription {
        return PIRPrimitiveDescription(
            name = proto.name,
            argTypes = proto.argTypesList.map(::mapType),
            returnType = mapType(proto.returnType),
            varArgType = proto.takeIf { it.hasVarArgType() }?.let { mapType(it.varArgType) },
            truncatedType = proto.takeIf { it.hasTruncatedType() }?.let { mapType(it.truncatedType) },
            cFunctionName = proto.cFunctionName,
            errorKind = mapErrorKind(proto.errorKind),
            steals = if (proto.hasSteals()) mapSteals(proto.steals) else null,
            isBorrowed = proto.isBorrowed,
            ordering = proto.orderingList,
            extraIntConstants = proto.extraIntConstantsList.map { it.value to mapType(it.type) },
            priority = proto.priority,
            isPure = proto.isPure,
            experimental = proto.experimental,
            dependencies = proto.dependenciesList.mapNotNull(::mapDependency),
            isAmbiguous = proto.isAmbiguous
        )
    }

    private fun mapDependency(proto: Dependency): PIRDependency? {
        return when (proto.dependencyOneofCase) {
            Dependency.DependencyOneofCase.CAPSULA -> PIRCapsule(proto.capsula.name)
            Dependency.DependencyOneofCase.SOURCE_DEP -> PIRSourceDep(proto.sourceDep.path)
            Dependency.DependencyOneofCase.DEPENDENCYONEOF_NOT_SET,
            null -> null
        }
    }

    private fun mapSteals(proto: StealsDescription): Any {
        return when (proto.descriptionCase) {
            StealsDescription.DescriptionCase.ALL -> proto.all
            StealsDescription.DescriptionCase.LIST -> proto.list.stealsList
            StealsDescription.DescriptionCase.DESCRIPTION_NOT_SET,
            null -> false
        }
    }

    private fun mapLiteralValue(proto: LiteralValue, type: PIRType): PIRLiteralValue {
        val value: Any? = when (proto.valueTypeCase) {
            LiteralValue.ValueTypeCase.INT_VALUE -> proto.intValue
            LiteralValue.ValueTypeCase.STR_VALUE -> proto.strValue
            LiteralValue.ValueTypeCase.BOOL_VALUE -> proto.boolValue
            LiteralValue.ValueTypeCase.FLOAT_VALUE -> proto.floatValue
            LiteralValue.ValueTypeCase.VALUETYPE_NOT_SET,
            null -> null
        }

        return object : PIRLiteralValue {
            override val type: PIRType = type
            override val value: Any? = value
        }
    }

    private fun mapIntOpKind(op: IntOp.OpType): PIRIntOpKind =
        when (op) {
            IntOp.OpType.ADD -> PIRIntOpKind.ADD
            IntOp.OpType.SUB -> PIRIntOpKind.SUB
            IntOp.OpType.MUL -> PIRIntOpKind.MUL
            IntOp.OpType.DIV -> PIRIntOpKind.DIV
            IntOp.OpType.MOD -> PIRIntOpKind.MOD
            IntOp.OpType.AND -> PIRIntOpKind.AND
            IntOp.OpType.OR -> PIRIntOpKind.OR
            IntOp.OpType.XOR -> PIRIntOpKind.XOR
            IntOp.OpType.LEFT_SHIFT -> PIRIntOpKind.SHL
            IntOp.OpType.RIGHT_SHIFT -> PIRIntOpKind.SHR
            else -> error("Unsupported IntOp kind: $op")
        }

    private fun mapCmpKind(op: ComparisonOp.OpType): PIRCmpKind =
        when (op) {
            ComparisonOp.OpType.EQ -> PIRCmpKind.EQ
            ComparisonOp.OpType.NEQ -> PIRCmpKind.NEQ
            ComparisonOp.OpType.SLT -> PIRCmpKind.LT
            ComparisonOp.OpType.SGT -> PIRCmpKind.GT
            ComparisonOp.OpType.SLE -> PIRCmpKind.LE
            ComparisonOp.OpType.SGE -> PIRCmpKind.GE
            ComparisonOp.OpType.ULT -> PIRCmpKind.ULT
            ComparisonOp.OpType.UGT -> PIRCmpKind.UGT
            ComparisonOp.OpType.ULE -> PIRCmpKind.ULE
            ComparisonOp.OpType.UGE -> PIRCmpKind.UGE
            else -> error("Unsupported ComparisonOp kind: $op")
        }

    private fun mapFloatCmpKind(op: FloatComparisonOp.OpType): PIRCmpKind =
        when (op) {
            FloatComparisonOp.OpType.EQ -> PIRCmpKind.EQ
            FloatComparisonOp.OpType.NEQ -> PIRCmpKind.NEQ
            FloatComparisonOp.OpType.LT -> PIRCmpKind.LT
            FloatComparisonOp.OpType.GT -> PIRCmpKind.GT
            FloatComparisonOp.OpType.LE -> PIRCmpKind.LE
            FloatComparisonOp.OpType.GE -> PIRCmpKind.GE
            else -> error("Unsupported FloatComparisonOp kind: $op")
        }

    private fun mapFloatOpKind(op: FloatOp.OpType): PIRFloatOpKind =
        when (op) {
            FloatOp.OpType.ADD -> PIRFloatOpKind.ADD
            FloatOp.OpType.SUB -> PIRFloatOpKind.SUB
            FloatOp.OpType.MUL -> PIRFloatOpKind.MUL
            FloatOp.OpType.DIV -> PIRFloatOpKind.DIV
            FloatOp.OpType.MOD -> PIRFloatOpKind.MOD
            else -> error("Unsupported FloatOp kind: $op")
        }

    private fun mapErrorKind(kind: Error_kind): Int =
        when (kind) {
            Error_kind.ERR_MAGIC -> 1
            Error_kind.ERR_FALSE -> ERR_FALSE
            Error_kind.ERR_ALWAYS -> 3
            Error_kind.ERR_MAGIC_OVERLAPPING -> 4
            else -> ERR_NEVER
        }

    private fun opLine(op: Op): Int {
        return if (op.hasValue()) op.value.line else -1
    }

    private fun resultRegisterOf(op: Op, fallbackName: String, line: Int): PIRRegister {
        if (op.hasValue() && op.value.valueCase == ir.Value.ValueCase.REGISTER) {
            return mapRegister(op.value.register)
        }

        val resultType = if (op.hasValue()) mapType(op.value.type) else PIRPrimitiveTypes.OBJECT
        val opLine = if (op.hasValue()) op.value.line else line
        val aliasKey = syntheticResultAliasKey(op, fallbackName, opLine, resultType)

        syntheticResultCache[op]?.let { return it }

        return PIRRegister(
            name = if (op.name.isNotBlank()) "__${fallbackName}_${nextSyntheticResultId.toString(16)}" else "__tmp_${fallbackName}_${nextSyntheticResultId.toString(16)}",
            type = resultType,
            line = opLine,
            isBorrowed = op.hasValue() && op.value.isBorrowed,
            isArg = false
        ).also {
            nextSyntheticResultId += 1
            syntheticResultCache[op] = it
            syntheticResultAliasCache.getOrPut(aliasKey, ::mutableListOf).add(it)
        }
    }

    private fun resolveValueOpRegister(op: Op, line: Int): PIRRegister {
        if (op.hasValue() && op.value.valueCase == ir.Value.ValueCase.REGISTER) {
            return mapRegister(op.value.register)
        }

        syntheticResultCache[op]?.let { return it }

        val resultType = if (op.hasValue()) mapType(op.value.type) else PIRPrimitiveTypes.OBJECT
        val fallbackName = fallbackNameForOp(op.name)
        val aliasKey = syntheticResultAliasKey(op, fallbackName, if (op.hasValue()) op.value.line else line, resultType)
        syntheticResultAliasCache[aliasKey]?.lastOrNull()?.let { return it }

        return PIRRegister(
            name = if (op.name.isNotBlank()) "__${fallbackName}_${nextSyntheticResultId.toString(16)}" else "__tmp_${fallbackName}_${nextSyntheticResultId.toString(16)}",
            type = resultType,
            line = if (op.hasValue()) op.value.line else line,
            isBorrowed = op.hasValue() && op.value.isBorrowed,
            isArg = false
        ).also {
            nextSyntheticResultId += 1
            syntheticResultCache[op] = it
            syntheticResultAliasCache.getOrPut(aliasKey, ::mutableListOf).add(it)
        }
    }

    private fun syntheticResultAliasKey(op: Op, fallbackName: String, line: Int, type: PIRType): String {
        val name = fallbackNameForOp(op.name.ifBlank { fallbackName })
        val borrowed = op.hasValue() && op.value.isBorrowed
        return listOf(name, line.toString(), type.name, borrowed.toString()).joinToString("|")
    }

    private fun syntheticResultAliasKey(value: ir.Value): String? {
        if (value.valueCase != ir.Value.ValueCase.OP) return null
        val op = value.op
        val resultType = if (op.hasValue()) mapType(op.value.type) else mapType(value.type)
        val line = if (op.hasValue()) op.value.line else value.line
        return syntheticResultAliasKey(op, fallbackNameForOp(op.name), line, resultType)
    }

    private fun mapValues(values: List<ir.Value>): List<PIRValue> {
        val occurrenceCounts = mutableMapOf<String, Int>()
        values.forEach { value ->
            syntheticResultAliasKey(value)?.let { key ->
                occurrenceCounts[key] = occurrenceCounts.getOrDefault(key, 0) + 1
            }
        }

        val nextIndices = occurrenceCounts.mapValues { (key, count) ->
            val historySize = syntheticResultAliasCache[key]?.size ?: 0
            maxOf(0, historySize - count)
        }.toMutableMap()

        return values.map { value ->
            val aliasKey = syntheticResultAliasKey(value)
            val history = aliasKey?.let { syntheticResultAliasCache[it] }
            if (aliasKey != null && history != null && history.isNotEmpty()) {
                val nextIndex = nextIndices.getOrDefault(aliasKey, history.lastIndex)
                val boundedIndex = nextIndex.coerceIn(0, history.lastIndex)
                nextIndices[aliasKey] = boundedIndex + 1
                history[boundedIndex]
            } else {
                mapValue(value)
            }
        }
    }

    private fun fallbackNameForOp(name: String): String {
        if (name.isBlank()) return "nested_op"
        return name
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
    }

    private fun shallowClass(proto: ir.Class): PIRClass {
        val key = "${proto.moduleName}.${proto.name}"
        return shallowClassCache.getOrPut(key) {
            PIRClass(
                name = proto.name,
                moduleName = proto.moduleName,
                isTrait = proto.isTrait,
                isGenerated = proto.isGenerated,
                isAbstract = proto.isAbstract,
                isExtClass = proto.isExtClass,
                isFinalClass = proto.isFinalClass,
                isAugmented = proto.isAugmented,
                inheritsPython = proto.inheritsPython,
                hasDict = proto.hasDict,
                allowInterpretedSubclasses = proto.allowInterpretedSubclasses,
                needsGetters = proto.needsGetseters,
                serializable = proto.serializable,
                builtinBase = proto.builtinBase.takeIf { it.isNotBlank() },
                ctor = mapFuncDecl(proto.ctor, emptyList()),
                setup = mapFuncDecl(proto.setup, emptyList()),
                childrenKnown = if (proto.hasChildrenKnown()) proto.childrenKnown else true,
                isAcyclic = proto.isAcyclic
            )
        }
    }

    private fun syntheticModuleOwner(moduleName: String): PIRClass {
        return moduleOwnerCache.getOrPut(moduleName) {
            PIRClass(
                name = "__module__",
                moduleName = moduleName,
                isExtClass = false,
                ctor = PIRFuncDecl(
                    name = "__init__",
                    className = "__module__",
                    moduleName = moduleName,
                    sig = PIRFuncSignature(emptyList(), PIR_VOID)
                ),
                setup = PIRFuncDecl(
                    name = "__setup__",
                    className = "__module__",
                    moduleName = moduleName,
                    sig = PIRFuncSignature(emptyList(), PIR_VOID)
                )
            )
        }
    }

    private fun computeBlockRanges(blocks: List<BasicBlock>): Map<Int, IntRange> {
        val result = linkedMapOf<Int, IntRange>()
        var nextIndex = 0

        for (block in blocks) {
            val start = nextIndex
            val width = maxOf(1, block.opsCount)
            val end = nextIndex + width - 1
            result[block.label] = start..end
            nextIndex = end + 1
        }

        return result
    }

    private fun noOpInst(location: PIRInstLocation): PIRInst {
        return PIREffectInst(
            location,
            PIRKeepAliveExpr(
                src = emptyList(),
                line = location.line
            )
        )
    }

    private fun undefinedExpr(type: PIRType, line: Int, isBorrowed: Boolean): PIRExpr {
        return PIRMoveExpr(
            value = PIRUndef(
                type = type,
                line = line,
                isBorrowed = isBorrowed
            ),
            type = type,
            line = line,
            isBorrowed = isBorrowed
        )
    }

    private fun unsupportedRegisterOp(op: Op, location: PIRInstLocation): PIRInst {
        if (!op.hasValue()) {
            return noOpInst(location)
        }

        val result = resultRegisterOf(op, fallbackNameForOp(op.name), location.line)
        return assignExpr(
            location = location,
            result = result,
            expr = undefinedExpr(result.type, location.line, result.isBorrowed)
        )
    }
}
