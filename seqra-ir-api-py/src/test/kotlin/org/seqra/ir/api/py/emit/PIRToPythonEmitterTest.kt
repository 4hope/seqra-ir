package org.seqra.ir.api.py.emit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.seqra.ir.api.py.PIRClass
import org.seqra.ir.api.py.PIRFunc
import org.seqra.ir.api.py.PIRFuncDecl
import org.seqra.ir.api.py.PIRFuncSignature
import org.seqra.ir.api.py.PIRModule
import org.seqra.ir.api.py.PIR_FUNC_CLASSMETHOD
import org.seqra.ir.api.py.PIR_FUNC_STATICMETHOD
import org.seqra.ir.api.py.PIRPrimitiveType
import org.seqra.ir.api.py.PIRPrimitiveTypes
import org.seqra.ir.api.py.PIRTupleType
import org.seqra.ir.api.py.PIRType
import org.seqra.ir.api.py.PIR_VOID
import org.seqra.ir.api.py.cfg.PIRAssignInst
import org.seqra.ir.api.py.cfg.PIRBasicBlock
import org.seqra.ir.api.py.cfg.PIRBoxExpr
import org.seqra.ir.api.py.cfg.PIRInst
import org.seqra.ir.api.py.cfg.PIRInstLocation
import org.seqra.ir.api.py.cfg.PIRInstRef
import org.seqra.ir.api.py.cfg.PIRInteger
import org.seqra.ir.api.py.cfg.PIRLiteralExpr
import org.seqra.ir.api.py.cfg.PIRLiteralValue
import org.seqra.ir.api.py.cfg.PIRPrimitiveCallExpr
import org.seqra.ir.api.py.cfg.PIRRegister
import org.seqra.ir.api.py.cfg.PIRReturnInst
import org.seqra.ir.api.py.cfg.PIRTupleExpr

class PIRToPythonEmitterTest {

    @Test
    fun `emitModule renders module imports and method decorators`() {
        val moduleName = "sample.module"
        val owner = classOwner(moduleName, "Tools")
        val staticMethod = buildFunction(
            name = "twice",
            moduleName = moduleName,
            owner = owner,
            argRegs = listOf(PIRRegister("x", PIRPrimitiveTypes.INT, isArg = true)),
            declClassName = "Tools",
            kind = PIR_FUNC_STATICMETHOD
        ) { loc ->
            listOf(
                PIRReturnInst(loc(0, 10), PIRInteger(2, PIRPrimitiveTypes.INT, line = 10))
            )
        }
        val classMethod = buildFunction(
            name = "make",
            moduleName = moduleName,
            owner = owner,
            argRegs = listOf(
                PIRRegister("cls", PIRPrimitiveTypes.OBJECT, isArg = true),
                PIRRegister("x", PIRPrimitiveTypes.INT, isArg = true)
            ),
            declClassName = "Tools",
            kind = PIR_FUNC_CLASSMETHOD
        ) { loc ->
            listOf(
                PIRReturnInst(loc(0, 12), PIRInteger(3, PIRPrimitiveTypes.INT, line = 12))
            )
        }
        val cls = owner.copy(methods = mapOf("twice" to staticMethod, "make" to classMethod))

        val emitted = PIRToPythonEmitter().emitModule(
            PIRModule(
                fullname = moduleName,
                imports = listOf("builtins", "sample.helpers"),
                functions = emptyList(),
                classes = listOf(cls),
                finalNames = emptyList()
            )
        )

        assertTrue(emitted.contains("import builtins"), emitted)
        assertTrue(
            emitted.contains("helpers = __pir_import_module(\"sample.helpers_generated\", \"sample.helpers\")"),
            emitted
        )
        assertTrue(emitted.contains("@staticmethod"), emitted)
        assertTrue(emitted.contains("@classmethod"), emitted)
    }

    @Test
    fun `emitModule recovers degraded method calls into python syntax`() {
        val moduleName = "sample.module"
        val owner = moduleOwner(moduleName)
        val obj = PIRRegister("obj", PIRPrimitiveTypes.OBJECT, isArg = true)
        val arg = PIRRegister("x", PIRPrimitiveTypes.INT, isArg = true)
        val methodName = PIRRegister("method_name", PIRPrimitiveTypes.STR, line = 10)
        val callArgs = PIRRegister("call_args", PIRTupleType(listOf(PIRPrimitiveTypes.OBJECT, PIRPrimitiveTypes.INT)), line = 10)
        val result = PIRRegister("result", PIRPrimitiveTypes.OBJECT, line = 10)

        val fn = buildFunction(
            name = "target",
            moduleName = moduleName,
            owner = owner,
            argRegs = listOf(obj, arg)
        ) { loc ->
            listOf(
                PIRAssignInst(loc(0, 10), methodName, PIRLiteralExpr(stringLiteral("invoke"), PIRPrimitiveTypes.STR, line = 10)),
                PIRAssignInst(loc(1, 10), callArgs, PIRTupleExpr(listOf(obj, arg), callArgs.type, line = 10)),
                PIRAssignInst(loc(2, 10), result, degradedPrimitive(PIRPrimitiveTypes.OBJECT, line = 10)),
                PIRReturnInst(loc(3, 10), result)
            )
        }

        val emitted = PIRToPythonEmitter().emitModule(
            PIRModule(
                fullname = moduleName,
                imports = emptyList(),
                functions = listOf(fn),
                classes = emptyList(),
                finalNames = emptyList()
            )
        )

        assertTrue(emitted.contains("result = obj.invoke(x)"), emitted)
        assertFalse(emitted.contains("result = pir_primitive("), emitted)
    }

    @Test
    fun `emitModule reconstructs degraded tuple packing from args and same-line values`() {
        val moduleName = "sample.module"
        val owner = moduleOwner(moduleName)
        val arg = PIRRegister("a", PIRPrimitiveTypes.INT, isArg = true)
        val computed = PIRRegister("c", PIRPrimitiveTypes.INT, line = 20)
        val packed = PIRRegister("packed", PIRTupleType(listOf(PIRPrimitiveTypes.INT, PIRPrimitiveTypes.INT)), line = 20)
        val boxed = PIRRegister("boxed", PIRPrimitiveTypes.OBJECT, line = 20)

        val fn = buildFunction(
            name = "pack",
            moduleName = moduleName,
            owner = owner,
            argRegs = listOf(arg)
        ) { loc ->
            listOf(
                PIRAssignInst(loc(0, 20), computed, PIRLiteralExpr(intLiteral(7), PIRPrimitiveTypes.INT, line = 20)),
                PIRAssignInst(loc(1, 20), packed, degradedPrimitive(packed.type, line = 20)),
                PIRAssignInst(loc(2, 20), boxed, PIRBoxExpr(packed, PIRPrimitiveTypes.OBJECT, line = 20)),
                PIRReturnInst(loc(3, 20), boxed)
            )
        }

        val emitted = PIRToPythonEmitter().emitModule(
            PIRModule(
                fullname = moduleName,
                imports = emptyList(),
                functions = listOf(fn),
                classes = emptyList(),
                finalNames = emptyList()
            )
        )

        assertTrue(emitted.contains("packed = (a, c)"), emitted)
        assertFalse(emitted.contains("packed = pir_primitive("), emitted)
    }

    private fun buildFunction(
        name: String,
        moduleName: String,
        owner: PIRClass,
        argRegs: List<PIRRegister>,
        declClassName: String? = null,
        kind: Int = 0,
        instructionsBuilder: (((Int, Int) -> PIRInstLocation)) -> List<PIRInst>
    ): PIRFunc {
        val decl = PIRFuncDecl(
            name = name,
            className = declClassName,
            moduleName = moduleName,
            sig = PIRFuncSignature(
                args = emptyList(),
                retType = PIRPrimitiveTypes.OBJECT
            ),
            kind = kind
        )

        class Holder {
            lateinit var func: PIRFunc
        }

        val holder = Holder()
        val locationFactory = { index: Int, line: Int ->
            object : PIRInstLocation {
                override val method: PIRFunc
                    get() = holder.func
                override val index: Int = index
                override val line: Int = line
            }
        }

        val instructions = instructionsBuilder(locationFactory)
        val first = instructions.first()
        val last = instructions.last()
        val blocks = listOf(
            PIRBasicBlock(
                start = PIRInstRef(first.location.index, first),
                end = PIRInstRef(last.location.index, last)
            )
        )

        return PIRFunc(
            decl = decl,
            argRegs = argRegs,
            instructions = instructions,
            blocks = blocks,
            enclosingClass = owner
        ).also { holder.func = it }
    }

    private fun moduleOwner(moduleName: String): PIRClass {
        return classOwner(moduleName, "__module__")
    }

    private fun classOwner(moduleName: String, className: String): PIRClass {
        return PIRClass(
            name = className,
            moduleName = moduleName,
            isExtClass = false,
            ctor = PIRFuncDecl(
                name = "__init__",
                className = className,
                moduleName = moduleName,
                sig = PIRFuncSignature(emptyList(), PIR_VOID)
            ),
            setup = PIRFuncDecl(
                name = "__setup__",
                className = className,
                moduleName = moduleName,
                sig = PIRFuncSignature(emptyList(), PIR_VOID)
            )
        )
    }

    private fun degradedPrimitive(type: PIRType, line: Int): PIRPrimitiveCallExpr {
        return PIRPrimitiveCallExpr(
            primitive = org.seqra.ir.api.py.cfg.PIRPrimitiveDescription(
                name = "",
                returnType = type
            ),
            args = emptyList(),
            type = type,
            line = line
        )
    }

    private fun stringLiteral(value: String): PIRLiteralValue {
        return object : PIRLiteralValue {
            override val type: PIRType = PIRPrimitiveTypes.STR
            override val value: Any? = value
        }
    }

    private fun intLiteral(value: Int): PIRLiteralValue {
        return object : PIRLiteralValue {
            override val type: PIRType = PIRPrimitiveTypes.INT
            override val value: Any? = value
        }
    }
}
