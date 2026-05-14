package org.seqra.ir.api.py.emit

import org.seqra.ir.api.py.*
import org.seqra.ir.api.py.cfg.*
import java.io.File

enum class EmitMode {
    DEBUG_IR,
    FUZZ;

    val cliName: String
        get() = name.lowercase().replace('_', '-')

    companion object {
        fun fromCli(value: String): EmitMode =
            entries.firstOrNull { it.cliName == value.lowercase() }
                ?: error("Unsupported emit mode: $value")
    }
}

data class EmitOptions(
    val mode: EmitMode = EmitMode.FUZZ,
    val failOnUnsupported: Boolean = true
)

class PIRToPythonEmitter(
    private val options: EmitOptions = EmitOptions()
) {
    private var currentModule: PIRModule? = null

    fun emitModule(module: PIRModule): String {
        currentModule = module
        val out = StringBuilder()

        out.appendLine("from __future__ import annotations")
        out.appendLine("# emitter mode: ${options.mode.cliName}")
        out.appendLine()
        emitRuntimeHelpers(out)
        emitModuleImports(module, out)

        val topLevelFunctions = module.functions.filter { it.decl.className == null }

        for (fn in topLevelFunctions) {
            emitFunction(fn, out, indent = "")
            out.appendLine()
        }

        for (cls in module.classes) {
            emitClass(cls, out)
            out.appendLine()
        }

        emitTopLevelInit(out)

        return out.toString()
    }

    fun writeModule(module: PIRModule, file: File) {
        file.writeText(emitModule(module))
    }

    private fun emitRuntimeHelpers(out: StringBuilder) {
        out.appendLine("__PIR_ERROR = object()")
        out.appendLine()

        out.appendLine("import importlib as __pir_importlib")
        out.appendLine()

        out.appendLine("def __pir_is_error(x):")
        out.appendLine("    return x is __PIR_ERROR")
        out.appendLine()

        out.appendLine("def __pir_call_c(name, *args):")
        out.appendLine("    if name == \"PyImport_Import\" and args:")
        out.appendLine("        return __pir_import_module(str(args[0]))")
        out.appendLine("    if name == \"CPyImport_ImportNative\":")
        out.appendLine("        module_name = __pir_guess_module_name(args)")
        out.appendLine("        if module_name is not None:")
        out.appendLine("            return __pir_import_module(module_name + \"_generated\", module_name)")
        out.appendLine("        return None")
        out.appendLine("    if name == \"CPyImport_GetNativeAttrs\":")
        out.appendLine("        return None")
        out.appendLine("    return None")
        out.appendLine()

        out.appendLine("def __pir_import_module(primary, fallback=None):")
        out.appendLine("    try:")
        out.appendLine("        return __pir_importlib.import_module(primary)")
        out.appendLine("    except ImportError:")
        out.appendLine("        if fallback is None:")
        out.appendLine("            return None")
        out.appendLine("        try:")
        out.appendLine("            return __pir_importlib.import_module(fallback)")
        out.appendLine("        except ImportError:")
        out.appendLine("            return None")
        out.appendLine()

        out.appendLine("def __pir_guess_module_name(args):")
        out.appendLine("    for arg in reversed(args):")
        out.appendLine("        if isinstance(arg, str) and arg and not arg.startswith('.'):")
        out.appendLine("            parts = arg.split('.')")
        out.appendLine("            if all(part.isidentifier() for part in parts):")
        out.appendLine("                return arg")
        out.appendLine("    return None")
        out.appendLine()

        out.appendLine("def __pir_primitive(name, *args):")
        out.appendLine("    # runtime stub for mypyc primitive op")
        out.appendLine("    raise NotImplementedError(f'primitive op not implemented: {name}')")
        out.appendLine()

        out.appendLine("def __pir_load_address(x):")
        out.appendLine("    return x")
        out.appendLine()

        out.appendLine("def __pir_load_mem(x):")
        out.appendLine("    return x")
        out.appendLine()

        out.appendLine("def __pir_set_mem(dest, src):")
        out.appendLine("    return None")
        out.appendLine()

        out.appendLine("def __pir_get_element_ptr(src, field):")
        out.appendLine("    try:")
        out.appendLine("        return getattr(src, field)")
        out.appendLine("    except Exception:")
        out.appendLine("        return None")
        out.appendLine()

        out.appendLine("def __pir_set_element(src, field, item):")
        out.appendLine("    try:")
        out.appendLine("        setattr(src, field, item)")
        out.appendLine("    except Exception:")
        out.appendLine("        pass")
        out.appendLine()

        out.appendLine("def __pir_keep_alive(*args):")
        out.appendLine("    return None")
        out.appendLine()

        out.appendLine("def __pir_raise_standard_error(class_name, value=None):")
        out.appendLine("    if value is None:")
        out.appendLine("        raise RuntimeError(class_name)")
        out.appendLine("    raise RuntimeError(f'{class_name}: {value}')")
        out.appendLine()
    }

    private fun emitModuleImports(module: PIRModule, out: StringBuilder) {
        module.imports.distinct().sorted().forEach { importName ->
            when (importName) {
                "builtins" -> out.appendLine("import builtins")
                else -> {
                    val alias = pySafeName(importName.substringAfterLast('.'))
                    val generatedName = generatedModuleImportName(importName)
                    out.appendLine("$alias = __pir_import_module(${quote(generatedName)}, ${quote(importName)})")
                }
            }
        }

        collectExternalFunctionBindings(module).forEach { (alias, qualifiedName) ->
            val moduleAlias = qualifiedName.substringBefore('.')
            val attrName = qualifiedName.substringAfter('.', "")
            out.appendLine("if $moduleAlias is not None and hasattr($moduleAlias, ${quote(attrName)}):")
            out.appendLine("    $alias = $qualifiedName")
        }

        if (module.imports.isNotEmpty() || collectExternalFunctionBindings(module).isNotEmpty()) {
            out.appendLine()
        }
    }

    private fun emitTopLevelInit(out: StringBuilder) {
        out.appendLine("__pir_module_initialized = False")
        out.appendLine()
        out.appendLine("def __pir_init_module():")
        out.appendLine("    global __pir_module_initialized")
        out.appendLine("    if __pir_module_initialized:")
        out.appendLine("        return")
        out.appendLine("    __pir_module_initialized = True")
        out.appendLine("    if \"__top_level__\" in globals():")
        out.appendLine("        __top_level__()")
        out.appendLine()
        out.appendLine("__pir_init_module()")
    }

    private fun emitClass(cls: PIRClass, out: StringBuilder) {
        val base = cls.base?.name?.takeIf { it.isNotBlank() } ?: "object"
        out.appendLine("class ${pySafeName(cls.name)}($base):")

        val methods = cls.methods.values.sortedBy { it.decl.name }

        if (methods.isEmpty()) {
            out.appendLine("    pass")
            return
        }

        out.appendLine()
        methods.forEachIndexed { idx, fn ->
            emitFunction(fn, out, indent = "    ")
            if (idx != methods.lastIndex) {
                out.appendLine()
            }
        }
    }

    private fun emitFunction(fn: PIRFunc, out: StringBuilder, indent: String) {
        when (fn.decl.kind) {
            PIR_FUNC_STATICMETHOD -> out.appendLine("${indent}@staticmethod")
            PIR_FUNC_CLASSMETHOD -> out.appendLine("${indent}@classmethod")
        }

        val params = fn.argRegs.joinToString(", ") { pyName(it.name) }
        out.appendLine("${indent}def ${pySafeName(fn.decl.name)}($params):")

        val inner = indent + "    "

        if (fn.instructions.isEmpty() || fn.blocks.isEmpty()) {
            out.appendLine("${inner}return None")
            return
        }

        if (fn.decl.name == "__top_level__") {
            val globals = collectTopLevelGlobals()
            if (globals.isNotEmpty()) {
                out.appendLine("${inner}global ${globals.joinToString(", ")}")
            }
        }

        val entryPc = fn.blocks.first().start.index
        out.appendLine("${inner}__pc = $entryPc")
        out.appendLine("${inner}while True:")

        val loopIndent = inner + "    "

        fn.blocks.forEachIndexed { idx, block ->
            val keyword = if (idx == 0) "if" else "elif"
            out.appendLine("${loopIndent}$keyword __pc == ${block.start.index}:")

            val blockIndent = loopIndent + "    "
            val blockInstructions = instructionsOf(fn, block)

            if (blockInstructions.isEmpty()) {
                out.appendLine("${blockIndent}return None")
            } else {
                blockInstructions.forEach { inst ->
                    emitInst(inst, out, blockIndent)
                }

                val last = blockInstructions.last()
                if (last !is PIRGotoInst &&
                    last !is PIRIfInst &&
                    last !is PIRReturnInst &&
                    last !is PIRUnreachableInst
                ) {
                    val nextBlock = nextBlock(fn, block)
                    if (nextBlock != null) {
                        out.appendLine("${blockIndent}__pc = ${nextBlock.start.index}")
                        out.appendLine("${blockIndent}continue")
                    } else {
                        out.appendLine("${blockIndent}return None")
                    }
                }
            }
        }

        out.appendLine("${loopIndent}else:")
        out.appendLine("${loopIndent}    raise RuntimeError(f'bad pc: {__pc}')")
    }

    private fun emitInst(inst: PIRInst, out: StringBuilder, indent: String) {
        when (inst) {
            is PIRAssignInst -> {
                out.appendLine("${indent}${emitValue(inst.lhv)} = ${emitExpr(inst.rhv)}")
            }

            is PIREffectInst -> {
                emitEffect(inst.effect, out, indent)
            }

            is PIRGotoInst -> {
                out.appendLine("${indent}__pc = ${inst.target.index}")
                out.appendLine("${indent}continue")
            }

            is PIRIfInst -> {
                val cond = emitCondition(inst.condition)
                val actual = if (inst.negated) "(not ($cond))" else cond

                out.appendLine("${indent}if $actual:")
                out.appendLine("${indent}    __pc = ${inst.trueBranch.index}")
                out.appendLine("${indent}else:")
                out.appendLine("${indent}    __pc = ${inst.falseBranch.index}")
                out.appendLine("${indent}continue")
            }

            is PIRReturnInst -> {
                if (inst.returnValue == null) {
                    out.appendLine("${indent}return None")
                } else {
                    out.appendLine("${indent}return ${emitValue(inst.returnValue)}")
                }
            }

            is PIRUnreachableInst -> {
                out.appendLine("${indent}raise RuntimeError('unreachable')")
            }

            else -> {
                out.appendLine("${indent}raise NotImplementedError(${quote(inst::class.simpleName ?: "UnknownInst")})")
            }
        }
    }

    private fun emitEffect(effect: PIREffectExpr, out: StringBuilder, indent: String) {
        when (effect) {
            is PIRSetAttrExpr -> {
                out.appendLine("${indent}${emitValue(effect.obj)}.${pySafeName(effect.attr)} = ${emitValue(effect.src)}")
            }

            is PIRInitStaticExpr -> {
                out.appendLine("${indent}${pySafeName(effect.identifier)} = ${emitValue(effect.value)}")
            }

            is PIRSetMemExpr -> {
                out.appendLine("${indent}__pir_set_mem(${emitValue(effect.dest)}, ${emitValue(effect.src)})")
            }

            is PIRSetElementExpr -> {
                out.appendLine("${indent}__pir_set_element(${emitValue(effect.src)}, ${quote(effect.field)}, ${emitValue(effect.item)})")
            }

            is PIRKeepAliveExpr -> {
                val args = effect.src.joinToString(", ") { emitValue(it) }
                out.appendLine("${indent}__pir_keep_alive($args)")
            }

            is PIRIncRefExpr -> {
                out.appendLine("${indent}# incref ${emitValue(effect.src)}")
            }

            is PIRDecRefExpr -> {
                out.appendLine("${indent}# decref ${emitValue(effect.src)}")
            }

            is PIRUnborrowExpr -> {
                out.appendLine("${indent}${emitValue(effect.src)}  # unborrow")
            }

            is PIRRaiseStandardErrorExpr -> {
                val valueExpr = when (val v = effect.value) {
                    null -> "None"
                    is PIRValue -> emitValue(v)
                    is String -> quote(v)
                    else -> quote(v.toString())
                }
                out.appendLine("${indent}__pir_raise_standard_error(${quote(effect.className)}, $valueExpr)")
            }

            else -> {
                out.appendLine("${indent}raise NotImplementedError(${quote(effect::class.simpleName ?: "UnknownEffect")})")
            }
        }
    }

    private fun emitCondition(expr: PIRConditionExpr): String {
        return when (expr) {
            is PIRTruthExpr -> emitValue(expr.value)
            is PIRErrorCheckExpr -> "__pir_is_error(${emitValue(expr.value)})"
            else -> emitExpr(expr)
        }
    }

    private fun emitCallC(expr: PIRCallCExpr): String {
        val args = expr.args.map(::emitValue)
        val taggedArgs = expr.args.map(::emitTaggedValue)

        return when (expr.functionName) {
            "CPyTagged_Add" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} + ${taggedArgs[1]})" else "__pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "CPyTagged_Subtract" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} - ${taggedArgs[1]})" else "__pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "CPyTagged_Multiply" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} * ${taggedArgs[1]})" else "__pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_Add" ->
                if (args.size == 2) "(${args[0]} + ${args[1]})" else "__pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_Subtract" ->
                if (args.size == 2) "(${args[0]} - ${args[1]})" else "__pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_Multiply" ->
                if (args.size == 2) "(${args[0]} * ${args[1]})" else "__pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_TrueDivide" ->
                if (args.size == 2) "(${args[0]} / ${args[1]})" else "__pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            else ->
                "__pir_call_c(${quote(expr.functionName)}${if (args.isNotEmpty()) ", ${args.joinToString(", ")}" else ""})"
        }
    }

    private fun emitPrimitive(expr: PIRPrimitiveCallExpr): String {
        val args = expr.args.joinToString(", ") { emitValue(it) }
        val taggedArgs = expr.args.map(::emitTaggedValue)

        return when (expr.primitive.name) {
            "int_eq" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} == ${taggedArgs[1]})" else "__pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_ne" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} != ${taggedArgs[1]})" else "__pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_lt" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} < ${taggedArgs[1]})" else "__pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_le" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} <= ${taggedArgs[1]})" else "__pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_gt" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} > ${taggedArgs[1]})" else "__pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_ge" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} >= ${taggedArgs[1]})" else "__pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            else -> "__pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"
        }
    }

    private fun emitExpr(expr: PIRExpr): String {
        return when (expr) {
            is PIRMoveExpr -> emitValue(expr.value)

            is PIRLiteralExpr -> emitLiteral(expr.literal.value)

            is PIRDirectCallExpr -> {
                val callee = emitCallableName(expr.funcDecl)
                val args = expr.args.joinToString(", ") { emitValue(it) }
                "$callee($args)"
            }

            is PIRMethodCallExpr -> {
                val obj = emitValue(expr.obj)
                val args = expr.args.joinToString(", ") { emitValue(it) }
                "$obj.${pySafeName(expr.method)}($args)"
            }

            is PIRPrimitiveCallExpr -> emitPrimitive(expr)

            is PIRCallCExpr -> emitCallC(expr)

            is PIRLoadErrorValueExpr -> "__PIR_ERROR"

            is PIRGetAttrExpr -> "${emitValue(expr.obj)}.${pySafeName(expr.attr)}"

            is PIRLoadStaticExpr -> pySafeName(expr.identifier)

            is PIRTupleExpr -> {
                val items = expr.items.joinToString(", ") { emitValue(it) }
                if (expr.items.size == 1) "($items,)" else "($items)"
            }

            is PIRTupleGetExpr -> "${emitValue(expr.tuple)}[${expr.index}]"

            is PIRCastExpr -> emitValue(expr.operand)

            is PIRBoxExpr -> emitValue(expr.operand)

            is PIRUnboxExpr -> emitValue(expr.operand)

            is PIRIntBinExpr -> {
                val op = when (expr.op) {
                    PIRIntOpKind.ADD -> "+"
                    PIRIntOpKind.SUB -> "-"
                    PIRIntOpKind.MUL -> "*"
                    PIRIntOpKind.DIV -> "/"
                    PIRIntOpKind.MOD -> "%"
                    PIRIntOpKind.AND -> "&"
                    PIRIntOpKind.OR -> "|"
                    PIRIntOpKind.XOR -> "^"
                    PIRIntOpKind.SHL -> "<<"
                    PIRIntOpKind.SHR -> ">>"
                }
                "(${emitValue(expr.lhs)} $op ${emitValue(expr.rhs)})"
            }

            is PIRCmpExpr -> {
                val op = when (expr.op) {
                    PIRCmpKind.EQ -> "=="
                    PIRCmpKind.NEQ -> "!="
                    PIRCmpKind.LT -> "<"
                    PIRCmpKind.GT -> ">"
                    PIRCmpKind.LE -> "<="
                    PIRCmpKind.GE -> ">="
                    PIRCmpKind.ULT -> "<"
                    PIRCmpKind.UGT -> ">"
                    PIRCmpKind.ULE -> "<="
                    PIRCmpKind.UGE -> ">="
                }
                "(${emitValue(expr.lhs)} $op ${emitValue(expr.rhs)})"
            }

            is PIRFloatBinExpr -> {
                val op = when (expr.op) {
                    PIRFloatOpKind.ADD -> "+"
                    PIRFloatOpKind.SUB -> "-"
                    PIRFloatOpKind.MUL -> "*"
                    PIRFloatOpKind.DIV -> "/"
                    PIRFloatOpKind.MOD -> "%"
                }
                "(${emitValue(expr.lhs)} $op ${emitValue(expr.rhs)})"
            }

            is PIRFloatNegExpr -> "(-${emitValue(expr.operand)})"

            is PIRLoadMemExpr -> "__pir_load_mem(${emitValue(expr.address)})"

            is PIRGetElementPtrExpr -> "__pir_get_element_ptr(${emitValue(expr.src)}, ${quote(expr.field)})"

            is PIRLoadAddressExpr -> "__pir_load_address(${emitLoadAddressTarget(expr.target)})"

            is PIRLoadGlobalExpr -> pySafeName(expr.identifier)

            is PIRPhiExpr -> {
                if (expr.values.isEmpty()) "None" else emitValue(expr.values.first())
            }

            is PIRTruthExpr -> emitValue(expr.value)

            is PIRErrorCheckExpr -> "__pir_is_error(${emitValue(expr.value)})"

            else -> "None"
        }
    }

    private fun emitValue(value: PIRValue): String {
        return when (value) {
            is PIRRegister -> pyName(value.name)
            is PIRArgument -> pyName(value.name)
            is PIRInteger -> value.value.toString()
            is PIRFloat -> value.value.toString()
            is PIRCString -> quote(value.value.decodeToString())
            is PIRUndef -> "None"
            else -> "None"
        }
    }

    private fun emitTaggedValue(value: PIRValue): String {
        return when (value) {
            is PIRInteger -> {
                if (value.value % 2 == 0) {
                    (value.value / 2).toString()
                } else {
                    value.value.toString()
                }
            }
            else -> emitValue(value)
        }
    }

    private fun emitLoadAddressTarget(target: Any): String {
        return when (target) {
            is String -> quote(target)
            is PIRRegister -> pyName(target.name)
            is PIRLoadStaticExpr -> pySafeName(target.identifier)
            else -> quote(target.toString())
        }
    }

    private fun emitCallableName(decl: PIRFuncDecl): String {
        return if (decl.className != null) {
            "${pySafeName(decl.className)}.${pySafeName(decl.name)}"
        } else if (decl.moduleName.isNotBlank() && decl.moduleName != currentModule?.fullname) {
            "${pySafeName(decl.moduleName.substringAfterLast('.'))}.${pySafeName(decl.name)}"
        } else {
            pySafeName(decl.name)
        }
    }

    private fun collectTopLevelGlobals(): List<String> {
        val module = currentModule ?: return emptyList()
        val importGlobals = module.imports.map { pySafeName(it.substringAfterLast('.')) }
        val boundFunctions = collectExternalFunctionBindings(module).map { it.first }
        return (importGlobals + boundFunctions).distinct().sorted()
    }

    private fun collectExternalFunctionBindings(module: PIRModule): List<Pair<String, String>> {
        val bindings = linkedMapOf<String, String>()
        val collisions = mutableSetOf<String>()

        module.functions.forEach { fn ->
            fn.instructions.forEach { inst ->
                val expr = (inst as? PIRAssignInst)?.rhv ?: return@forEach
                if (expr is PIRDirectCallExpr &&
                    expr.funcDecl.className == null &&
                    expr.funcDecl.moduleName.isNotBlank() &&
                    expr.funcDecl.moduleName != module.fullname
                ) {
                    val alias = pySafeName(expr.funcDecl.name)
                    val moduleAlias = pySafeName(expr.funcDecl.moduleName.substringAfterLast('.'))
                    val qualified = "$moduleAlias.${pySafeName(expr.funcDecl.name)}"
                    val previous = bindings[alias]
                    if (previous == null) {
                        bindings[alias] = qualified
                    } else if (previous != qualified) {
                        collisions += alias
                    }
                }
            }
        }

        collisions.forEach(bindings::remove)
        return bindings.entries.map { it.toPair() }
    }

    private fun emitLiteral(value: Any?): String {
        return when (value) {
            null -> "None"
            is String -> quote(value)
            is Boolean -> if (value) "True" else "False"
            is Float -> value.toString()
            is Double -> value.toString()
            else -> value.toString()
        }
    }

    private fun instructionsOf(fn: PIRFunc, block: PIRBasicBlock): List<PIRInst> {
        return fn.instructions.filter { block.contains(it) }
    }

    private fun nextBlock(fn: PIRFunc, current: PIRBasicBlock): PIRBasicBlock? {
        val idx = fn.blocks.indexOf(current)
        return if (idx >= 0 && idx + 1 < fn.blocks.size) fn.blocks[idx + 1] else null
    }

    private fun pyName(name: String): String {
        val raw = name.ifBlank { "tmp" }
        val cleaned = raw.replace(Regex("[^A-Za-z0-9_]"), "_")
        val safe = if (cleaned.firstOrNull()?.isDigit() == true) "_$cleaned" else cleaned
        return if (safe.isBlank()) "tmp" else safe
    }

    private fun pySafeName(name: String): String = pyName(name)

    private fun generatedModuleImportName(name: String): String {
        val parts = name.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return name
        return parts.dropLast(1).plus(parts.last() + "_generated").joinToString(".")
    }

    private fun quote(s: String): String {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t") + "\""
    }
}