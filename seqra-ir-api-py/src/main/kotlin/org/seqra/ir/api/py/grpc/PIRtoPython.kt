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
        out.appendLine("    if name == \"CPyType_FromTemplate\" and args:")
        out.appendLine("        return args[0]")
        out.appendLine("    if name == \"CPy_InitSubclass\":")
        out.appendLine("        return 0")
        out.appendLine("    if name in {\"PyObject_RichCompare\", \"PyObject_RichCompareBool\"}:")
        out.appendLine("        return __pir_rich_compare(*args)")
        out.appendLine("    if name == \"PyObject_IsTrue\":")
        out.appendLine("        return __pir_bool_to_int(args[0] if args else None)")
        out.appendLine("    if name == \"PyObject_Not\":")
        out.appendLine("        return __pir_bool_to_int(not (args[0] if args else None))")
        out.appendLine("    if name == \"PyNumber_Negative\":")
        out.appendLine("        return -(args[0] if args else 0)")
        out.appendLine("    if name == \"PyNumber_Positive\":")
        out.appendLine("        return +(args[0] if args else 0)")
        out.appendLine("    if name == \"PyNumber_Absolute\":")
        out.appendLine("        return abs(args[0] if args else 0)")
        out.appendLine("    if name == \"PyNumber_Remainder\" and len(args) == 2:")
        out.appendLine("        return args[0] % args[1]")
        out.appendLine("    if name == \"PyNumber_FloorDivide\" and len(args) == 2:")
        out.appendLine("        return args[0] // args[1]")
        out.appendLine("    if name == \"PyNumber_Lshift\" and len(args) == 2:")
        out.appendLine("        return args[0] << args[1]")
        out.appendLine("    if name == \"PyNumber_Rshift\" and len(args) == 2:")
        out.appendLine("        return args[0] >> args[1]")
        out.appendLine("    if name == \"PyNumber_And\" and len(args) == 2:")
        out.appendLine("        return args[0] & args[1]")
        out.appendLine("    if name == \"PyNumber_Or\" and len(args) == 2:")
        out.appendLine("        return args[0] | args[1]")
        out.appendLine("    if name == \"PyNumber_Xor\" and len(args) == 2:")
        out.appendLine("        return args[0] ^ args[1]")
        out.appendLine("    if name == \"PyNumber_InPlaceAdd\" and len(args) == 2:")
        out.appendLine("        return args[0] + args[1]")
        out.appendLine("    if name == \"PyNumber_InPlaceSubtract\" and len(args) == 2:")
        out.appendLine("        return args[0] - args[1]")
        out.appendLine("    if name == \"PyNumber_InPlaceMultiply\" and len(args) == 2:")
        out.appendLine("        return args[0] * args[1]")
        out.appendLine("    if name in {\"PyObject_GetAttr\", \"CPyObject_GetAttr\"} and len(args) >= 2:")
        out.appendLine("        return getattr(args[0], str(args[1]))")
        out.appendLine("    if name in {\"PyObject_SetAttr\", \"CPyObject_SetAttr\"} and len(args) >= 3:")
        out.appendLine("        setattr(args[0], str(args[1]), args[2])")
        out.appendLine("        return 0")
        out.appendLine("    if name in {\"PyObject_GetItem\", \"PySequence_GetItem\"} and len(args) >= 2:")
        out.appendLine("        return args[0][args[1]]")
        out.appendLine("    if name == \"PyObject_SetItem\" and len(args) >= 3:")
        out.appendLine("        args[0][args[1]] = args[2]")
        out.appendLine("        return 0")
        out.appendLine("    if name in {\"PyList_GetItem\", \"PyTuple_GetItem\"} and len(args) >= 2:")
        out.appendLine("        return args[0][args[1]]")
        out.appendLine("    if name == \"PyList_SetItem\" and len(args) >= 3:")
        out.appendLine("        args[0][args[1]] = args[2]")
        out.appendLine("        return 0")
        out.appendLine("    if name == \"PyList_Append\" and len(args) >= 2:")
        out.appendLine("        args[0].append(args[1])")
        out.appendLine("        return 0")
        out.appendLine("    if name == \"PyList_New\" and args:")
        out.appendLine("        return [None] * int(args[0])")
        out.appendLine("    if name == \"PyTuple_Pack\" and args:")
        out.appendLine("        count = int(args[0])")
        out.appendLine("        return tuple(args[1:1 + count])")
        out.appendLine("    if name in {\"PyDict_GetItem\", \"CPyDict_GetItem\"} and len(args) >= 2:")
        out.appendLine("        return __pir_dict_get_item(args[0], args[1])")
        out.appendLine("    if name in {\"PyDict_SetItem\", \"CPyDict_SetItem\"} and len(args) >= 3:")
        out.appendLine("        return __pir_dict_set_item(args[0], args[1], args[2])")
        out.appendLine("    if name == \"CPyDict_Build\" and args:")
        out.appendLine("        return __pir_dict_build(*args)")
        out.appendLine("    if name == \"PyObject_Length\" and args:")
        out.appendLine("        return len(args[0])")
        out.appendLine("    if name == \"PyObject_IsInstance\" and len(args) >= 2:")
        out.appendLine("        return __pir_bool_to_int(isinstance(args[0], args[1]))")
        out.appendLine("    if name == \"PyObject_Type\" and args:")
        out.appendLine("        return type(args[0])")
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

        out.appendLine("def __pir_dict_get_item(mapping, key):")
        out.appendLine("    try:")
        out.appendLine("        return mapping[key]")
        out.appendLine("    except Exception:")
        out.appendLine("        return __PIR_ERROR")
        out.appendLine()

        out.appendLine("def __pir_dict_set_item(mapping, key, value):")
        out.appendLine("    try:")
        out.appendLine("        mapping[key] = value")
        out.appendLine("        return 0")
        out.appendLine("    except Exception:")
        out.appendLine("        return -1")
        out.appendLine()

        out.appendLine("def __pir_dict_build(*args):")
        out.appendLine("    if not args:")
        out.appendLine("        return {}")
        out.appendLine("    count = int(args[0])")
        out.appendLine("    result = {}")
        out.appendLine("    for index in range(count):")
        out.appendLine("        offset = 1 + index * 2")
        out.appendLine("        if offset + 1 >= len(args):")
        out.appendLine("            break")
        out.appendLine("        result[args[offset]] = args[offset + 1]")
        out.appendLine("    return result")
        out.appendLine()

        out.appendLine("def __pir_bool_to_int(value):")
        out.appendLine("    try:")
        out.appendLine("        return 1 if value else 0")
        out.appendLine("    except Exception:")
        out.appendLine("        return -1")
        out.appendLine()

        out.appendLine("def __pir_rich_compare(lhs, rhs, op):")
        out.appendLine("    if op == 0:")
        out.appendLine("        return lhs < rhs")
        out.appendLine("    if op == 1:")
        out.appendLine("        return lhs <= rhs")
        out.appendLine("    if op == 2:")
        out.appendLine("        return lhs == rhs")
        out.appendLine("    if op == 3:")
        out.appendLine("        return lhs != rhs")
        out.appendLine("    if op == 4:")
        out.appendLine("        return lhs > rhs")
        out.appendLine("    if op == 5:")
        out.appendLine("        return lhs >= rhs")
        out.appendLine("    raise ValueError(f'unsupported rich compare opcode: {op}')")
        out.appendLine()

        out.appendLine("def __pir_primitive(name, *args):")
        out.appendLine("    if name in {\"int_eq\", \"bool_eq\"} and len(args) == 2:")
        out.appendLine("        return args[0] == args[1]")
        out.appendLine("    if name in {\"int_ne\", \"bool_ne\"} and len(args) == 2:")
        out.appendLine("        return args[0] != args[1]")
        out.appendLine("    if name == \"int_lt\" and len(args) == 2:")
        out.appendLine("        return args[0] < args[1]")
        out.appendLine("    if name == \"int_le\" and len(args) == 2:")
        out.appendLine("        return args[0] <= args[1]")
        out.appendLine("    if name == \"int_gt\" and len(args) == 2:")
        out.appendLine("        return args[0] > args[1]")
        out.appendLine("    if name == \"int_ge\" and len(args) == 2:")
        out.appendLine("        return args[0] >= args[1]")
        out.appendLine("    if name in {\"int_add\", \"CPyTagged_Add\"} and len(args) == 2:")
        out.appendLine("        return args[0] + args[1]")
        out.appendLine("    if name in {\"int_sub\", \"CPyTagged_Subtract\"} and len(args) == 2:")
        out.appendLine("        return args[0] - args[1]")
        out.appendLine("    if name in {\"int_mul\", \"CPyTagged_Multiply\"} and len(args) == 2:")
        out.appendLine("        return args[0] * args[1]")
        out.appendLine("    if name in {\"int_floordiv\", \"int_div\"} and len(args) == 2:")
        out.appendLine("        return args[0] // args[1]")
        out.appendLine("    if name == \"int_mod\" and len(args) == 2:")
        out.appendLine("        return args[0] % args[1]")
        out.appendLine("    if name == \"int_neg\" and len(args) == 1:")
        out.appendLine("        return -args[0]")
        out.appendLine("    if name == \"float_add\" and len(args) == 2:")
        out.appendLine("        return args[0] + args[1]")
        out.appendLine("    if name == \"float_sub\" and len(args) == 2:")
        out.appendLine("        return args[0] - args[1]")
        out.appendLine("    if name == \"float_mul\" and len(args) == 2:")
        out.appendLine("        return args[0] * args[1]")
        out.appendLine("    if name == \"float_div\" and len(args) == 2:")
        out.appendLine("        return args[0] / args[1]")
        out.appendLine("    if name == \"float_neg\" and len(args) == 1:")
        out.appendLine("        return -args[0]")
        out.appendLine("    if name == \"list_get_item_unsafe\" and len(args) == 2:")
        out.appendLine("        return args[0][args[1]]")
        out.appendLine("    if name == \"list_items\" and len(args) == 1:")
        out.appendLine("        return args[0]")
        out.appendLine("    if name == \"buf_init_item\" and len(args) == 3:")
        out.appendLine("        args[0][int(args[1])] = args[2]")
        out.appendLine("        return 0")
        out.appendLine("    if name == \"dict_get_item\" and len(args) == 2:")
        out.appendLine("        return __pir_dict_get_item(args[0], args[1])")
        out.appendLine("    raise NotImplementedError(f'primitive op not implemented: {name}')")
        out.appendLine()

        out.appendLine("def __pir_load_address(x):")
        out.appendLine("    if x == \"_Py_NoneStruct\":")
        out.appendLine("        return None")
        out.appendLine("    if x == \"_Py_TrueStruct\":")
        out.appendLine("        return True")
        out.appendLine("    if x == \"_Py_FalseStruct\":")
        out.appendLine("        return False")
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
        out.appendLine("PIR_ERROR = __PIR_ERROR")
        out.appendLine("pir_is_error = __pir_is_error")
        out.appendLine("pir_call_c = __pir_call_c")
        out.appendLine("pir_dict_get_item = __pir_dict_get_item")
        out.appendLine("pir_dict_set_item = __pir_dict_set_item")
        out.appendLine("pir_primitive = __pir_primitive")
        out.appendLine("pir_load_address = __pir_load_address")
        out.appendLine("pir_load_mem = __pir_load_mem")
        out.appendLine("pir_set_mem = __pir_set_mem")
        out.appendLine("pir_get_element_ptr = __pir_get_element_ptr")
        out.appendLine("pir_set_element = __pir_set_element")
        out.appendLine("pir_keep_alive = __pir_keep_alive")
        out.appendLine("pir_raise_standard_error = __pir_raise_standard_error")
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
        val className = pySafeName(cls.name)
        val base = cls.base?.name?.takeIf { it.isNotBlank() } ?: "object"
        out.appendLine("class $className($base):")

        val methods = cls.methods.values.sortedBy { it.decl.name }

        if (methods.isEmpty()) {
            out.appendLine("    pass")
        } else {
            out.appendLine()
            methods.forEachIndexed { idx, fn ->
                emitFunction(fn, out, indent = "    ")
                if (idx != methods.lastIndex) {
                    out.appendLine()
                }
            }
        }

        out.appendLine()
        out.appendLine("${className}_template = $className")
        out.appendLine("def ${className}_trait_vtable_setup():")
        out.appendLine("    return None")
        out.appendLine()
        out.appendLine("def ${className}_coroutine_setup(cls):")
        out.appendLine("    return cls")
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
                val initReturnsNone = fn.decl.className != null && fn.decl.name == "__init__"
                emitBlockInstructions(fn, blockInstructions, out, blockIndent, initReturnsNone)

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

    private fun emitInst(inst: PIRInst, out: StringBuilder, indent: String, initReturnsNone: Boolean = false) {
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
                if (initReturnsNone) {
                    out.appendLine("${indent}return None")
                } else if (inst.returnValue == null) {
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

    private fun emitBlockInstructions(
        fn: PIRFunc,
        blockInstructions: List<PIRInst>,
        out: StringBuilder,
        indent: String,
        initReturnsNone: Boolean
    ) {
        var index = 0
        while (index < blockInstructions.size) {
            val inst = blockInstructions[index]
            val recoveredExpr = recoverDegradedAssign(fn, blockInstructions, index)
            if (inst is PIRAssignInst && recoveredExpr != null) {
                out.appendLine("${indent}${emitValue(inst.lhv)} = $recoveredExpr")
            } else {
                emitInst(inst, out, indent, initReturnsNone)
            }
            index += 1
        }
    }

    private fun recoverDegradedAssign(
        fn: PIRFunc,
        blockInstructions: List<PIRInst>,
        index: Int
    ): String? {
        val inst = blockInstructions.getOrNull(index) as? PIRAssignInst ?: return null
        val primitive = inst.rhv as? PIRPrimitiveCallExpr ?: return null
        if (primitive.primitive.name.isNotBlank() || primitive.args.isNotEmpty()) return null

        return recoverDegradedMethodCall(blockInstructions, index)
            ?: recoverDegradedTuple(fn, blockInstructions, index)
    }

    private fun recoverDegradedMethodCall(
        blockInstructions: List<PIRInst>,
        index: Int
    ): String? {
        val window = blockInstructions.subList(maxOf(0, index - 4), index)
        val tupleExpr = window
            .asReversed()
            .mapNotNull { (it as? PIRAssignInst)?.rhv as? PIRTupleExpr }
            .firstOrNull()
            ?: return null
        val methodName = window
            .asReversed()
            .mapNotNull { ((it as? PIRAssignInst)?.rhv as? PIRLiteralExpr)?.literal?.value as? String }
            .firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val receiver = emitValue(tupleExpr.items.first())
        val args = tupleExpr.items.drop(1).joinToString(", ") { emitValue(it) }
        return "$receiver.${pySafeName(methodName)}($args)"
    }

    private fun recoverDegradedTuple(
        fn: PIRFunc,
        blockInstructions: List<PIRInst>,
        index: Int
    ): String? {
        val tupleAssign = blockInstructions.getOrNull(index) as? PIRAssignInst ?: return null
        val tupleType = (tupleAssign.lhv.type as? PIRTupleType) ?: return null
        val boxAssign = blockInstructions.getOrNull(index + 1) as? PIRAssignInst ?: return null
        val boxExpr = boxAssign.rhv as? PIRBoxExpr ?: return null
        if (boxExpr.operand != tupleAssign.lhv) return null

        val sameLineValues = blockInstructions
            .take(index)
            .mapNotNull { previous ->
                val assign = previous as? PIRAssignInst ?: return@mapNotNull null
                if (assign.location.line != tupleAssign.location.line) return@mapNotNull null
                assign.lhv
            }
        if (sameLineValues.isEmpty()) return null

        val arity = tupleType.types.size
        val prefixCount = arity - sameLineValues.size
        if (prefixCount < 0 || prefixCount > fn.argRegs.size) return null

        val recoveredItems = fn.argRegs.take(prefixCount) + sameLineValues.takeLast(arity - prefixCount)
        if (recoveredItems.size != arity) return null

        val items = recoveredItems.joinToString(", ") { emitValue(it) }
        return if (recoveredItems.size == 1) "($items,)" else "($items)"
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
                out.appendLine("${indent}pir_set_mem(${emitValue(effect.dest)}, ${emitValue(effect.src)})")
            }

            is PIRSetElementExpr -> {
                out.appendLine("${indent}pir_set_element(${emitValue(effect.src)}, ${quote(effect.field)}, ${emitValue(effect.item)})")
            }

            is PIRKeepAliveExpr -> {
                val args = effect.src.joinToString(", ") { emitValue(it) }
                out.appendLine("${indent}pir_keep_alive($args)")
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
                out.appendLine("${indent}pir_raise_standard_error(${quote(effect.className)}, $valueExpr)")
            }

            else -> {
                out.appendLine("${indent}raise NotImplementedError(${quote(effect::class.simpleName ?: "UnknownEffect")})")
            }
        }
    }

    private fun emitCondition(expr: PIRConditionExpr): String {
        return when (expr) {
            is PIRTruthExpr -> emitValue(expr.value)
            is PIRErrorCheckExpr -> "pir_is_error(${emitValue(expr.value)})"
            else -> emitExpr(expr)
        }
    }

    private fun emitCallC(expr: PIRCallCExpr): String {
        val args = expr.args.map(::emitValue)
        val taggedArgs = expr.args.map(::emitTaggedValue)

        return when (expr.functionName) {
            "CPyTagged_Add" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} + ${taggedArgs[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "CPyTagged_Subtract" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} - ${taggedArgs[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "CPyTagged_Multiply" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} * ${taggedArgs[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_Add" ->
                if (args.size == 2) "(${args[0]} + ${args[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_Subtract" ->
                if (args.size == 2) "(${args[0]} - ${args[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_Multiply" ->
                if (args.size == 2) "(${args[0]} * ${args[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "PyNumber_TrueDivide" ->
                if (args.size == 2) "(${args[0]} / ${args[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "CPyDict_GetItem" ->
                if (args.size == 2) "pir_dict_get_item(${args[0]}, ${args[1]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            "CPyDict_SetItem" ->
                if (args.size == 3) "pir_dict_set_item(${args[0]}, ${args[1]}, ${args[2]})" else "pir_call_c(${quote(expr.functionName)}, ${args.joinToString(", ")})"

            else ->
                "pir_call_c(${quote(expr.functionName)}${if (args.isNotEmpty()) ", ${args.joinToString(", ")}" else ""})"
        }
    }

    private fun emitPrimitive(expr: PIRPrimitiveCallExpr): String {
        val args = expr.args.joinToString(", ") { emitValue(it) }
        val taggedArgs = expr.args.map(::emitTaggedValue)

        return when (expr.primitive.name) {
            "int_eq" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} == ${taggedArgs[1]})" else "pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_ne" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} != ${taggedArgs[1]})" else "pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_lt" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} < ${taggedArgs[1]})" else "pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_le" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} <= ${taggedArgs[1]})" else "pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_gt" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} > ${taggedArgs[1]})" else "pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            "int_ge" ->
                if (taggedArgs.size == 2) "(${taggedArgs[0]} >= ${taggedArgs[1]})" else "pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"

            else -> "pir_primitive(${quote(expr.primitive.name)}${if (args.isNotEmpty()) ", $args" else ""})"
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

            is PIRLoadErrorValueExpr -> "PIR_ERROR"

            is PIRGetAttrExpr -> "${emitValue(expr.obj)}.${pySafeName(expr.attr)}"

            is PIRLoadStaticExpr -> emitLoadStatic(expr)

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

            is PIRLoadMemExpr -> "pir_load_mem(${emitValue(expr.address)})"

            is PIRGetElementExpr -> "pir_get_element(${emitValue(expr.src)}, ${quote(expr.field)})"

            is PIRGetElementPtrExpr -> "pir_get_element_ptr(${emitValue(expr.src)}, ${quote(expr.field)})"

            is PIRLoadAddressExpr -> "pir_load_address(${emitLoadAddressTarget(expr.target)})"

            is PIRLoadGlobalExpr -> pySafeName(expr.identifier)

            is PIRPhiExpr -> {
                if (expr.values.isEmpty()) "None" else emitValue(expr.values.first())
            }

            is PIRTruthExpr -> emitValue(expr.value)

            is PIRErrorCheckExpr -> "pir_is_error(${emitValue(expr.value)})"

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
            is PIRLoadStaticExpr -> emitLoadStatic(target)
            else -> quote(target.toString())
        }
    }

    private fun emitLoadStatic(expr: PIRLoadStaticExpr): String {
        if (expr.identifier == "globals") {
            return "globals()"
        }

        if (!expr.moduleName.isNullOrBlank() && expr.moduleName != currentModule?.fullname) {
            val moduleAlias = pySafeName(expr.moduleName.substringAfterLast('.'))
            val identifier = pySafeName(expr.identifier)
            return if (moduleAlias == identifier) moduleAlias else "$moduleAlias.$identifier"
        }

        return pySafeName(expr.identifier)
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
