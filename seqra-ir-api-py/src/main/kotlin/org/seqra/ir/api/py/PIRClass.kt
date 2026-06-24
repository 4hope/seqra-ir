package org.seqra.ir.api.py

data class PIRVTableMethod(
    val cls: PIRClass,
    val name: String,
    val method: PIRFunc,
    val shadowMethod: PIRFunc? = null
)

data class PIRClass(
    val name: String,
    val moduleName: String,
    val isTrait: Boolean = false,
    val isGenerated: Boolean = false,
    val isAbstract: Boolean = false,
    val isExtClass: Boolean = true,
    val isFinalClass: Boolean = false,
    val isAugmented: Boolean = false,
    val inheritsPython: Boolean = false,
    val hasDict: Boolean = false,
    val allowInterpretedSubclasses: Boolean = false,
    val needsGetters: Boolean = false,
    val serializable: Boolean = false,
    val builtinBase: String? = null,
    val ctor: PIRFuncDecl,
    val setup: PIRFuncDecl,
    val attributes: Map<String, PIRType> = emptyMap(),
    val deletable: List<String> = emptyList(),
    val methodDecls: Map<String, PIRFuncDecl> = emptyMap(),
    val methods: Map<String, PIRFunc> = emptyMap(),
    val glueMethods: Map<Pair<PIRClass, String>, PIRFunc> = emptyMap(),
    val properties: Map<String, Pair<PIRFunc, PIRFunc?>> = emptyMap(),
    val propertyTypes: Map<String, PIRType> = emptyMap(),
    val vtable: Map<String, Int>? = null,
    val vtableEntries: List<PIRVTableMethod> = emptyList(),
    val traitVtables: Map<PIRClass, List<PIRVTableMethod>> = emptyMap(),
    val base: PIRClass? = null,
    val traits: List<PIRClass> = emptyList(),
    val mro: List<PIRClass> = emptyList(),
    val baseMro: List<PIRClass> = emptyList(),
    val children: List<PIRClass> = emptyList(),
    val childrenKnown: Boolean = true,
    val attrsWithDefaults: Set<String> = emptySet(),
    val alwaysInitializedAttrs: Set<String> = emptySet(),
    val sometimesInitializedAttrs: Set<String> = emptySet(),
    val initSelfLeak: Boolean = false,
    val bitmapAttrs: List<String> = emptyList(),
    val envUserFunction: PIRFunc? = null,
    val reuseFreedInstance: Boolean = false,
    val isAcyclic: Boolean = false,
    val isEnum: Boolean = false,
    val coroutineName: String? = null
) {
    val fullname: String get() = "$moduleName.$name"
}
