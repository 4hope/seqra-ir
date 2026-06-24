package org.seqra.ir.api.py.grpc

import ir.BasicBlock
import ir.FuncDecl
import ir.FuncSignature
import ir.Function
import ir.RType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.seqra.ir.api.py.cfg.PIRInteger
import org.seqra.ir.api.py.mapper.ProtoToPirMapper

class ProtoToPirMapperEdgeCasesTest {

    private val objectType = RType.newBuilder().setName("builtins.object").build()
    private val intType = RType.newBuilder().setName("builtins.int").build()

    @Test
    fun `mapClass preserves accessors glue methods trait vtables and env function`() {
        val traitProto = ir.Class.newBuilder()
            .setName("Trait")
            .setModuleName("sample.module")
            .build()

        val mapped = ProtoToPirMapper().mapClass(
            ir.Class.newBuilder()
                .setName("Owner")
                .setModuleName("sample.module")
                .setCtor(simpleFuncDecl("__init__", className = "Owner"))
                .setSetup(simpleFuncDecl("__setup__", className = "Owner"))
                .putProperties(
                    "value",
                    ir.FuncFunc.newBuilder()
                        .setFunc1(simpleFunction("value", className = "Owner", isPropGetter = true))
                        .setFunc2(simpleFunction("value", className = "Owner", isPropSetter = true))
                        .build()
                )
                .putPropertyTypes("value", intType)
                .addGlueMethods(
                    ir.GlueMethodEntry.newBuilder()
                        .setKey(
                            ir.ClassString.newBuilder()
                                .setClass_(traitProto)
                                .setName("adapt")
                        )
                        .setValue(simpleFunction("adapt_value", className = "Owner"))
                )
                .setVtableEntries(
                    ir.VTableEntries.newBuilder()
                        .addEntries(
                            ir.VTableMethod.newBuilder()
                                .setCls(
                                    ir.Class.newBuilder()
                                        .setName("Owner")
                                        .setModuleName("sample.module")
                                )
                                .setName("dispatch")
                                .setMethod(simpleFunction("dispatch", className = "Owner"))
                                .setShadowMethod(simpleFunction("dispatch_shadow", className = "Owner"))
                        )
                )
                .addTraitVtables(
                    ir.TraitVTableEntry.newBuilder()
                        .setTrait(traitProto)
                        .setVtable(
                            ir.VTableEntries.newBuilder()
                                .addEntries(
                                    ir.VTableMethod.newBuilder()
                                        .setCls(traitProto)
                                        .setName("trait_dispatch")
                                        .setMethod(simpleFunction("trait_dispatch_impl", className = "Owner"))
                                )
                        )
                )
                .setEnvUserFunction(simpleFunction("env_capture", className = "Owner"))
                .build()
        )

        val property = mapped.properties.getValue("value")
        assertTrue(property.first.decl.isPropGetter)
        assertNotNull(property.second)
        assertTrue(property.second!!.decl.isPropSetter)
        assertEquals("sample.module.Owner", property.first.enclosingClass.fullname)
        assertEquals("builtins.int", mapped.propertyTypes.getValue("value").typeName)

        assertNotNull(mapped.envUserFunction)
        assertEquals("env_capture", mapped.envUserFunction!!.decl.name)

        val glueMethod = mapped.glueMethods.entries.single()
        assertEquals("sample.module.Trait", glueMethod.key.first.fullname)
        assertEquals("adapt", glueMethod.key.second)
        assertEquals("adapt_value", glueMethod.value.decl.name)
        assertEquals("sample.module.Owner", glueMethod.value.enclosingClass.fullname)

        val vtableEntry = mapped.vtableEntries.single()
        assertEquals("dispatch", vtableEntry.name)
        assertEquals("dispatch", vtableEntry.method.decl.name)
        assertNotNull(vtableEntry.shadowMethod)
        assertEquals("dispatch_shadow", vtableEntry.shadowMethod!!.decl.name)

        val traitVtable = mapped.traitVtables.entries.single()
        assertEquals("sample.module.Trait", traitVtable.key.fullname)
        assertEquals("trait_dispatch", traitVtable.value.single().name)
        assertEquals("trait_dispatch_impl", traitVtable.value.single().method.decl.name)
    }

    @Test
    fun `mapValue clamps oversized integers to kotlin int bounds`() {
        val mapper = ProtoToPirMapper()

        val positive = mapper.mapValue(
            ir.Value.newBuilder()
                .setInteger(
                    ir.Integer.newBuilder()
                        .setValue(Long.MAX_VALUE)
                        .setType(intType)
                        .setLine(1)
                )
                .build()
        ) as PIRInteger

        val negative = mapper.mapValue(
            ir.Value.newBuilder()
                .setInteger(
                    ir.Integer.newBuilder()
                        .setValue(Long.MIN_VALUE)
                        .setType(intType)
                        .setLine(2)
                )
                .build()
        ) as PIRInteger

        assertEquals(Int.MAX_VALUE, positive.value)
        assertEquals(Int.MIN_VALUE, negative.value)
    }

    private fun simpleFunction(
        name: String,
        moduleName: String = "sample.module",
        className: String? = null,
        isPropGetter: Boolean = false,
        isPropSetter: Boolean = false
    ): Function {
        return Function.newBuilder()
            .setDecl(
                simpleFuncDecl(
                    name = name,
                    moduleName = moduleName,
                    className = className,
                    isPropGetter = isPropGetter,
                    isPropSetter = isPropSetter
                )
            )
            .addBlocks(BasicBlock.newBuilder().setLabel(0))
            .build()
    }

    private fun simpleFuncDecl(
        name: String,
        moduleName: String = "sample.module",
        className: String? = null,
        isPropGetter: Boolean = false,
        isPropSetter: Boolean = false
    ): FuncDecl {
        val builder = FuncDecl.newBuilder()
            .setName(name)
            .setModuleName(moduleName)
            .setSig(
                FuncSignature.newBuilder()
                    .setRetType(objectType)
            )
            .setIsPropGetter(isPropGetter)
            .setIsPropSetter(isPropSetter)

        if (className != null) {
            builder.setClassName(className)
        }

        return builder.build()
    }
}
