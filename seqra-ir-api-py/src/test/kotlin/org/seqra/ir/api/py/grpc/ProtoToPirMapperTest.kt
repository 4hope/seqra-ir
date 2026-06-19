package org.seqra.ir.api.py.grpc

import ir.BasicBlock
import ir.FuncDecl
import ir.FuncSignature
import ir.Function
import ir.Op
import ir.RType
import ir.Register
import ir.RuntimeArg as ProtoRuntimeArg
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.seqra.ir.api.py.ARG_NAMED_OPT
import org.seqra.ir.api.py.ARG_POS
import org.seqra.ir.api.py.ARG_STAR
import org.seqra.ir.api.py.ARG_STAR2
import org.seqra.ir.api.py.PIR_FUNC_CLASSMETHOD
import org.seqra.ir.api.py.PIR_FUNC_STATICMETHOD
import org.seqra.ir.api.py.PIRCapsule
import org.seqra.ir.api.py.PIRSourceDep
import org.seqra.ir.api.py.cfg.PIRAssignInst
import org.seqra.ir.api.py.cfg.PIREffectInst
import org.seqra.ir.api.py.cfg.PIRFloat
import org.seqra.ir.api.py.cfg.PIRCString
import org.seqra.ir.api.py.cfg.PIRGetElementExpr
import org.seqra.ir.api.py.cfg.PIRSetMemExpr
import org.seqra.ir.api.py.mapper.ProtoToPirMapper

class ProtoToPirMapperTest {

    private val objectType = RType.newBuilder().setName("builtins.object").build()
    private val intType = RType.newBuilder().setName("builtins.int").build()
    private val strType = RType.newBuilder().setName("builtins.str").build()
    private val boolType = RType.newBuilder().setName("builtins.bool").build()
    private val dictType = RType.newBuilder().setName("builtins.dict").build()

    @Test
    fun `mapFunction preserves runtime arg metadata from proto signature`() {
        val function = Function.newBuilder()
            .setDecl(
                FuncDecl.newBuilder()
                    .setName("target")
                    .setModuleName("sample.module")
                    .setSig(
                        FuncSignature.newBuilder()
                            .setRetType(objectType)
                            .addRuntimeArgs(
                                ProtoRuntimeArg.newBuilder()
                                    .setName("head")
                                    .setType(intType)
                                    .setKind(ARG_POS)
                                    .setPosOnly(true)
                            )
                            .addRuntimeArgs(
                                ProtoRuntimeArg.newBuilder()
                                    .setName("args")
                                    .setType(strType)
                                    .setKind(ARG_STAR)
                            )
                            .addRuntimeArgs(
                                ProtoRuntimeArg.newBuilder()
                                    .setName("flag")
                                    .setType(boolType)
                                    .setKind(ARG_NAMED_OPT)
                            )
                            .addRuntimeArgs(
                                ProtoRuntimeArg.newBuilder()
                                    .setName("kwargs")
                                    .setType(dictType)
                                    .setKind(ARG_STAR2)
                            )
                    )
            )
            .addArgRegs(Register.newBuilder().setName("ignored0").setType(objectType))
            .addArgRegs(Register.newBuilder().setName("ignored1").setType(objectType))
            .addBlocks(BasicBlock.newBuilder().setLabel(0))
            .build()

        val mapped = ProtoToPirMapper().mapFunction(function)

        assertEquals(listOf("head", "args", "flag", "kwargs"), mapped.decl.sig.args.map { it.name })
        assertEquals(listOf(ARG_POS, ARG_STAR, ARG_NAMED_OPT, ARG_STAR2), mapped.decl.sig.args.map { it.kind })
        assertEquals(listOf(true, false, false, false), mapped.decl.sig.args.map { it.posOnly })
        assertEquals(listOf("builtins.int", "builtins.str", "builtins.bool", "builtins.dict"), mapped.decl.sig.args.map { it.type.typeName })
    }

    @Test
    fun `mapFunction falls back to arg registers when runtime args are absent`() {
        val function = Function.newBuilder()
            .setDecl(
                FuncDecl.newBuilder()
                    .setName("target")
                    .setModuleName("sample.module")
                    .setSig(
                        FuncSignature.newBuilder()
                            .setRetType(objectType)
                            .addArgs(intType)
                            .addArgs(strType)
                    )
            )
            .addArgRegs(Register.newBuilder().setName("left").setType(intType))
            .addArgRegs(Register.newBuilder().setName("right").setType(strType))
            .addBlocks(BasicBlock.newBuilder().setLabel(0))
            .build()

        val mapped = ProtoToPirMapper().mapFunction(function)

        assertEquals(listOf("left", "right"), mapped.decl.sig.args.map { it.name })
        assertEquals(listOf(ARG_POS, ARG_POS), mapped.decl.sig.args.map { it.kind })
        assertEquals(listOf(false, false), mapped.decl.sig.args.map { it.posOnly })
    }

    @Test
    fun `mapFunction preserves bound signature and bitmap arg count`() {
        val function = Function.newBuilder()
            .setDecl(
                FuncDecl.newBuilder()
                    .setName("method")
                    .setModuleName("sample.module")
                    .setClassName("Owner")
                    .setSig(
                        FuncSignature.newBuilder()
                            .setRetType(objectType)
                            .setNumBitmapArgs(1)
                            .addRuntimeArgs(
                                ProtoRuntimeArg.newBuilder()
                                    .setName("self")
                                    .setType(objectType)
                                    .setKind(ARG_POS)
                            )
                            .addRuntimeArgs(
                                ProtoRuntimeArg.newBuilder()
                                    .setName("flag")
                                    .setType(boolType)
                                    .setKind(ARG_NAMED_OPT)
                            )
                    )
                    .setBoundSig(
                        FuncSignature.newBuilder()
                            .setRetType(objectType)
                            .addRuntimeArgs(
                                ProtoRuntimeArg.newBuilder()
                                    .setName("flag")
                                    .setType(boolType)
                                    .setKind(ARG_NAMED_OPT)
                            )
                    )
            )
            .addBlocks(BasicBlock.newBuilder().setLabel(0))
            .build()

        val mapped = ProtoToPirMapper().mapFunction(function)

        assertEquals(1, mapped.decl.sig.numBitmapArgs)
        assertNotNull(mapped.decl.boundSig)
        assertEquals(listOf("flag"), mapped.decl.boundSig!!.args.map { it.name })
        assertEquals(0, mapped.decl.boundSig!!.numBitmapArgs)
    }

    @Test
    fun `mapFunction infers local function metadata from generated callable class`() {
        val mapped = ProtoToPirMapper().mapFunction(
            Function.newBuilder()
                .setDecl(
                    FuncDecl.newBuilder()
                        .setName("__call__")
                        .setClassName("inner_outer_obj")
                        .setModuleName("sample.module")
                        .setSig(FuncSignature.newBuilder().setRetType(objectType))
                )
                .setTracebackName("inner")
                .addBlocks(BasicBlock.newBuilder().setLabel(0))
                .build()
        )

        assertEquals("outer", mapped.enclosingFunction)
        assertTrue(mapped.isLocal)
        assertFalse(mapped.isLambda)
    }

    @Test
    fun `mapFunction infers lambda metadata from generated callable class`() {
        val nestedLambda = ProtoToPirMapper().mapFunction(
            Function.newBuilder()
                .setDecl(
                    FuncDecl.newBuilder()
                        .setName("__call__")
                        .setClassName("__mypyc_lambda__0_outer_obj")
                        .setModuleName("sample.module")
                        .setSig(FuncSignature.newBuilder().setRetType(objectType))
                )
                .setTracebackName("<lambda>")
                .addBlocks(BasicBlock.newBuilder().setLabel(0))
                .build()
        )
        val topLevelLambda = ProtoToPirMapper().mapFunction(
            Function.newBuilder()
                .setDecl(
                    FuncDecl.newBuilder()
                        .setName("__call__")
                        .setClassName("__mypyc_lambda__1_obj")
                        .setModuleName("sample.module")
                        .setSig(FuncSignature.newBuilder().setRetType(objectType))
                )
                .setTracebackName("<lambda>")
                .addBlocks(BasicBlock.newBuilder().setLabel(0))
                .build()
        )

        assertEquals("outer", nestedLambda.enclosingFunction)
        assertTrue(nestedLambda.isLocal)
        assertTrue(nestedLambda.isLambda)
        assertNull(topLevelLambda.enclosingFunction)
        assertFalse(topLevelLambda.isLocal)
        assertTrue(topLevelLambda.isLambda)
    }

    @Test
    fun `mapClass preserves final and acyclic flags`() {
        val mapped = ProtoToPirMapper().mapClass(
            ir.Class.newBuilder()
                .setName("Leaf")
                .setModuleName("sample.module")
                .setIsFinalClass(true)
                .setIsAcyclic(true)
                .build()
        )

        assertTrue(mapped.isFinalClass)
        assertTrue(mapped.isAcyclic)
    }

    @Test
    fun `mapModule preserves imports and type vars`() {
        val mapped = ProtoToPirMapper().mapModule(
            ir.Module.newBuilder()
                .setFullname("sample.module")
                .addImports("builtins")
                .addImports("pkg.helpers")
                .addTypeVarNames("T")
                .addTypeVarNames("U")
                .build()
        )

        assertEquals(listOf("builtins", "pkg.helpers"), mapped.imports)
        assertEquals(listOf("T", "U"), mapped.typeVarNames)
    }

    @Test
    fun `mapFunction preserves staticmethod and classmethod kinds`() {
        val staticMethod = ProtoToPirMapper().mapFunction(
            Function.newBuilder()
                .setDecl(
                    FuncDecl.newBuilder()
                        .setName("twice")
                        .setModuleName("sample.module")
                        .setClassName("Tools")
                        .setKind(ir.FunctionKind.FUNC_STATICMETHOD)
                        .setSig(FuncSignature.newBuilder().setRetType(objectType))
                )
                .addBlocks(BasicBlock.newBuilder().setLabel(0))
                .build()
        )
        val classMethod = ProtoToPirMapper().mapFunction(
            Function.newBuilder()
                .setDecl(
                    FuncDecl.newBuilder()
                        .setName("make")
                        .setModuleName("sample.module")
                        .setClassName("Tools")
                        .setKind(ir.FunctionKind.FUNC_CLASSMETHOD)
                        .setSig(FuncSignature.newBuilder().setRetType(objectType))
                )
                .addBlocks(BasicBlock.newBuilder().setLabel(0))
                .build()
        )

        assertEquals(PIR_FUNC_STATICMETHOD, staticMethod.decl.kind)
        assertEquals(PIR_FUNC_CLASSMETHOD, classMethod.decl.kind)
    }

    @Test
    fun `mapClass preserves unknown versus empty children state`() {
        val unknownChildren = ProtoToPirMapper().mapClass(
            ir.Class.newBuilder()
                .setName("UnknownChildren")
                .setModuleName("sample.module")
                .setChildrenKnown(false)
                .build()
        )
        val emptyChildren = ProtoToPirMapper().mapClass(
            ir.Class.newBuilder()
                .setName("EmptyChildren")
                .setModuleName("sample.module")
                .setChildrenKnown(true)
                .build()
        )

        assertFalse(unknownChildren.childrenKnown)
        assertTrue(unknownChildren.children.isEmpty())
        assertTrue(emptyChildren.childrenKnown)
        assertTrue(emptyChildren.children.isEmpty())
    }

    @Test
    fun `mapModule preserves dependencies`() {
        val mapped = ProtoToPirMapper().mapModule(
            ir.Module.newBuilder()
                .setFullname("sample.module")
                .addDependencies(
                    ir.Dependency.newBuilder()
                        .setCapsula(ir.Capsula.newBuilder().setName("librt.strings"))
                )
                .addDependencies(
                    ir.Dependency.newBuilder()
                        .setSourceDep(ir.SourceDep.newBuilder().setPath("bytes_extra_ops.c"))
                )
                .build()
        )

        assertTrue(mapped.dependencies.contains(PIRCapsule("librt.strings")))
        assertTrue(mapped.dependencies.contains(PIRSourceDep("bytes_extra_ops.c")))
        assertFalse(mapped.dependencies.isEmpty())
    }

    @Test
    fun `mapValue preserves float and cstring payloads`() {
        val mapper = ProtoToPirMapper()

        val floatValue = mapper.mapValue(
            ir.Value.newBuilder()
                .setFloatVal(
                    ir.Float.newBuilder()
                        .setValue(3.25)
                        .setType(objectType)
                        .setLine(7)
                )
                .build()
        ) as PIRFloat

        val cstringValue = mapper.mapValue(
            ir.Value.newBuilder()
                .setCstring(
                    ir.CString.newBuilder()
                        .setValue(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x41, 0x00, 0x42)))
                        .setType(strType)
                        .setLine(9)
                )
                .build()
        ) as PIRCString

        assertEquals(3.25, floatValue.value)
        assertTrue(cstringValue.value.contentEquals(byteArrayOf(0x41, 0x00, 0x42)))
    }

    @Test
    fun `mapFunction preserves get_element and set_mem operations`() {
        val function = Function.newBuilder()
            .setDecl(
                FuncDecl.newBuilder()
                    .setName("target")
                    .setModuleName("sample.module")
                    .setSig(FuncSignature.newBuilder().setRetType(objectType))
            )
            .addBlocks(
                BasicBlock.newBuilder()
                    .setLabel(0)
                    .addOps(
                        Op.newBuilder()
                            .setName("GetElement")
                            .setValue(
                                ir.Value.newBuilder()
                                    .setType(intType)
                                    .setIsBorrowed(true)
                                    .build()
                            )
                            .setRegisterOp(
                                ir.RegisterOp.newBuilder()
                                    .setGetElement(
                                        ir.GetElement.newBuilder()
                                            .setType(intType)
                                            .setSrc(
                                                ir.Value.newBuilder()
                                                    .setRegister(Register.newBuilder().setName("src").setType(objectType))
                                                    .setType(objectType)
                                                    .build()
                                            )
                                            .setSrcType(objectType)
                                            .setField("x")
                                            .setIsBorrowed(true)
                                    )
                            )
                    )
                    .addOps(
                        Op.newBuilder()
                            .setName("SetMem")
                            .setRegisterOp(
                                ir.RegisterOp.newBuilder()
                                    .setSetMem(
                                        ir.SetMem.newBuilder()
                                            .setDestType(intType)
                                            .setSrc(
                                                ir.Value.newBuilder()
                                                    .setRegister(Register.newBuilder().setName("rhs").setType(intType))
                                                    .setType(intType)
                                                    .build()
                                            )
                                            .setDest(
                                                ir.Value.newBuilder()
                                                    .setRegister(Register.newBuilder().setName("ptr").setType(objectType))
                                                    .setType(objectType)
                                                    .build()
                                            )
                                    )
                            )
                    )
            )
            .build()

        val mapped = ProtoToPirMapper().mapFunction(function)

        val getElement = mapped.instructions[0] as PIRAssignInst
        val setMem = mapped.instructions[1] as PIREffectInst

        assertTrue(getElement.rhv is PIRGetElementExpr)
        assertEquals("x", (getElement.rhv as PIRGetElementExpr).field)
        assertTrue((getElement.rhv as PIRGetElementExpr).isBorrowed)
        assertTrue(setMem.effect is PIRSetMemExpr)
        assertEquals("rhs", (setMem.effect as PIRSetMemExpr).src.toString())
        assertEquals("ptr", (setMem.effect as PIRSetMemExpr).dest.toString())
    }

    @Test
    fun `mapFunction preserves exceptional cfg edges and block handlers`() {
        val function = Function.newBuilder()
            .setDecl(
                FuncDecl.newBuilder()
                    .setName("target")
                    .setModuleName("sample.module")
                    .setSig(FuncSignature.newBuilder().setRetType(objectType))
            )
            .addBlocks(
                BasicBlock.newBuilder()
                    .setLabel(0)
                    .addOps(
                        Op.newBuilder()
                            .setName("LoadErrorValue")
                            .setRegisterOp(
                                ir.RegisterOp.newBuilder()
                                    .setErrorKind(ir.Error_kind.ERR_MAGIC)
                                    .setLoadErrorValue(
                                        ir.LoadErrorValue.newBuilder()
                                            .setType(objectType)
                                            .setIsBorrowed(false)
                                            .setUndefines(false)
                                    )
                            )
                    )
                    .setErrorHandler(BasicBlock.newBuilder().setLabel(1))
            )
            .addBlocks(
                BasicBlock.newBuilder()
                    .setLabel(1)
                    .addOps(
                        Op.newBuilder()
                            .setName("Unreachable")
                            .setControlOp(
                                ir.ControlOp.newBuilder()
                                    .setUnreachable(ir.Unreachable.newBuilder())
                            )
                    )
            )
            .build()

        val mapped = ProtoToPirMapper().mapFunction(function)
        val firstBlock = mapped.blocks.first()
        val handlerBlock = mapped.blocks[1]

        assertNotNull(firstBlock.errorHandler)
        assertEquals(handlerBlock.start.index, firstBlock.errorHandler!!.index)

        val graph = mapped.flowGraph()
        val throwingInst = mapped.instructions.first()
        val handlerInst = mapped.instructions[1]
        val catcher = graph.catchers(throwingInst).single()

        assertTrue(graph.successors(throwingInst).contains(handlerInst))
        assertEquals(handlerInst.location.index, catcher.handler.start.index)
        assertTrue(graph.throwers(catcher).contains(throwingInst))
        assertTrue(graph.exits.contains(handlerInst))
        assertEquals(firstBlock, graph.blockGraph().block(throwingInst))
        assertEquals(handlerBlock, graph.blockGraph().block(handlerInst))
        assertNull(mapped.blocks.last().errorHandler)
    }
}
