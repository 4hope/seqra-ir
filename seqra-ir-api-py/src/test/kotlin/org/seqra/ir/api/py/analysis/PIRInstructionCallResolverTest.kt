package org.seqra.ir.api.py.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.seqra.ir.api.py.PIRClass
import org.seqra.ir.api.py.PIRFunc
import org.seqra.ir.api.py.PIRFuncDecl
import org.seqra.ir.api.py.PIRFuncSignature
import org.seqra.ir.api.py.PIRModule
import org.seqra.ir.api.py.PIRPrimitiveTypes
import org.seqra.ir.api.py.PIRInstanceType
import org.seqra.ir.api.py.PIRRuntimeArg
import org.seqra.ir.api.py.PIR_VOID
import org.seqra.ir.api.py.cfg.PIRArgument
import org.seqra.ir.api.py.cfg.PIRAssignInst
import org.seqra.ir.api.py.cfg.PIRBasicBlock
import org.seqra.ir.api.py.cfg.PIRDirectCallExpr
import org.seqra.ir.api.py.cfg.PIRGotoInst
import org.seqra.ir.api.py.cfg.PIRIfInst
import org.seqra.ir.api.py.cfg.PIRInst
import org.seqra.ir.api.py.cfg.PIRInstLocation
import org.seqra.ir.api.py.cfg.PIRInstRef
import org.seqra.ir.api.py.cfg.PIRLoadGlobalExpr
import org.seqra.ir.api.py.cfg.PIRMethodCallExpr
import org.seqra.ir.api.py.cfg.PIRRegister
import org.seqra.ir.api.py.cfg.PIRReturnInst
import org.seqra.ir.api.py.cfg.PIRTruthExpr

class PIRInstructionCallResolverTest {

    @Test
    fun `resolver should resolve direct pir function call`() {
        val moduleName = "sample.module"
        val owner = moduleOwner(moduleName)
        val callee = buildFunction(
            name = "callee",
            moduleName = moduleName,
            owner = owner
        ) { loc ->
            listOf(PIRReturnInst(loc(0, 10), null))
        }
        val result = PIRRegister("result", PIRPrimitiveTypes.OBJECT, line = 20)
        val caller = buildFunction(
            name = "caller",
            moduleName = moduleName,
            owner = owner
        ) { loc ->
            listOf(
                PIRAssignInst(
                    loc(0, 20),
                    result,
                    PIRDirectCallExpr(
                        funcDecl = callee.decl,
                        args = emptyList(),
                        type = PIRPrimitiveTypes.OBJECT,
                        line = 20
                    )
                ),
                PIRReturnInst(loc(1, 20), result)
            )
        }

        val module = PIRModule(
            fullname = moduleName,
            imports = emptyList(),
            functions = listOf(callee, caller),
            classes = emptyList(),
            finalNames = emptyList()
        )

        val resolution = caller.callResolver(module.callTargetIndex())
            .resolve(caller.instructions.first())

        assertNotNull(resolution)
        assertTrue(resolution!!.precise)
        val target = resolution.targets.single() as PIRResolvedFunctionTarget
        assertEquals(callee.fullname, target.fullname)
        assertEquals(callee, target.function)
    }

    @Test
    fun `resolver should resolve callable targets across cfg branches`() {
        val moduleName = "sample.module"
        val owner = moduleOwner(moduleName)

        val leftCallable = classOwner(moduleName, "left_outer_obj")
        val rightCallable = classOwner(moduleName, "right_outer_obj")
        val leftCall = buildFunction(
            name = "__call__",
            moduleName = moduleName,
            owner = leftCallable,
            declClassName = leftCallable.name,
            isLocal = true,
            enclosingFunction = "outer"
        ) { loc ->
            listOf(PIRReturnInst(loc(0, 30), null))
        }
        val rightCall = buildFunction(
            name = "__call__",
            moduleName = moduleName,
            owner = rightCallable,
            declClassName = rightCallable.name,
            isLocal = true,
            enclosingFunction = "outer"
        ) { loc ->
            listOf(PIRReturnInst(loc(0, 31), null))
        }

        val leftClass = leftCallable.copy(methods = mapOf("__call__" to leftCall))
        val rightClass = rightCallable.copy(methods = mapOf("__call__" to rightCall))

        val flag = PIRArgument(0, "flag", PIRPrimitiveTypes.BOOL, line = 40)
        val receiver = PIRRegister("receiver", PIRPrimitiveTypes.OBJECT, line = 40)
        val result = PIRRegister("result", PIRPrimitiveTypes.OBJECT, line = 44)
        val leftType = PIRInstanceType(leftClass)
        val rightType = PIRInstanceType(rightClass)

        val caller = buildFunction(
            name = "outer",
            moduleName = moduleName,
            owner = owner,
            argRegs = listOf(PIRRegister("flag", PIRPrimitiveTypes.BOOL, line = 40, isArg = true))
        ) { loc ->
            listOf(
                PIRIfInst(
                    location = loc(0, 40),
                    condition = PIRTruthExpr(flag, line = 40),
                    trueBranch = PIRInstRef(1),
                    falseBranch = PIRInstRef(3)
                ),
                PIRAssignInst(
                    loc(1, 41),
                    receiver,
                    PIRLoadGlobalExpr("left_callable", type = leftType, line = 41)
                ),
                PIRGotoInst(loc(2, 42), PIRInstRef(4)),
                PIRAssignInst(
                    loc(3, 43),
                    receiver,
                    PIRLoadGlobalExpr("right_callable", type = rightType, line = 43)
                ),
                PIRAssignInst(
                    loc(4, 44),
                    result,
                    PIRMethodCallExpr(
                        obj = receiver,
                        method = "__call__",
                        args = emptyList(),
                        receiverType = PIRPrimitiveTypes.OBJECT,
                        type = PIRPrimitiveTypes.OBJECT,
                        line = 44
                    )
                ),
                PIRReturnInst(loc(5, 45), result)
            )
        }

        val module = PIRModule(
            fullname = moduleName,
            imports = emptyList(),
            functions = listOf(caller),
            classes = listOf(leftClass, rightClass),
            finalNames = emptyList()
        )

        val callInst = caller.instructions[4]
        val resolution = caller.callResolver(module.callTargetIndex()).resolve(callInst)

        assertNotNull(resolution)
        assertTrue(resolution!!.precise)
        val targets = resolution.targets.map { it as PIRResolvedFunctionTarget }.mapNotNull { it.function }.toSet()
        assertEquals(setOf(leftCall, rightCall), targets)
    }

    private fun buildFunction(
        name: String,
        moduleName: String,
        owner: PIRClass,
        argRegs: List<PIRRegister> = emptyList(),
        declClassName: String? = null,
        isLocal: Boolean = false,
        enclosingFunction: String? = null,
        instructionsBuilder: (((Int, Int) -> PIRInstLocation)) -> List<PIRInst>
    ): PIRFunc {
        val decl = PIRFuncDecl(
            name = name,
            className = declClassName,
            moduleName = moduleName,
            sig = PIRFuncSignature(
                args = argRegs.mapIndexed { index, register ->
                    PIRRuntimeArg(register.name, register.type, kind = 0, posOnly = false).also { _ -> index }
                },
                retType = PIRPrimitiveTypes.OBJECT
            ),
            isLocal = isLocal,
            enclosingFunction = enclosingFunction
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
        val blocks = buildBlocks(instructions)

        return PIRFunc(
            decl = decl,
            argRegs = argRegs,
            instructions = instructions,
            blocks = blocks,
            enclosingClass = owner
        ).also { holder.func = it }
    }

    private fun buildBlocks(instructions: List<PIRInst>): List<PIRBasicBlock> {
        val blockStarts = linkedSetOf(0)
        instructions.forEachIndexed { index, inst ->
            when (inst) {
                is PIRIfInst -> {
                    blockStarts += inst.trueBranch.index
                    blockStarts += inst.falseBranch.index
                    if (index + 1 < instructions.size) {
                        blockStarts += index + 1
                    }
                }

                is PIRGotoInst -> {
                    blockStarts += inst.target.index
                    if (index + 1 < instructions.size) {
                        blockStarts += index + 1
                    }
                }

                else -> Unit
            }
        }

        val sortedStarts = blockStarts.filter { it in instructions.indices }.sorted()
        return sortedStarts.mapIndexed { idx, start ->
            val end = (sortedStarts.getOrNull(idx + 1)?.minus(1)) ?: instructions.lastIndex
            PIRBasicBlock(
                start = PIRInstRef(start, instructions[start]),
                end = PIRInstRef(end, instructions[end])
            )
        }
    }

    private fun moduleOwner(moduleName: String): PIRClass = classOwner(moduleName, "__module__")

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
}
