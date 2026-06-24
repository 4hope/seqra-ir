package org.seqra.ir.api.py

sealed class PIRDependency

data class PIRCapsule(val name: String) : PIRDependency()

data class PIRSourceDep(val path: String) : PIRDependency()

data class PIRModule(
    val fullname: String,
    val imports: List<String>,
    val functions: List<PIRFunc>,
    val classes: List<PIRClass>,
    val finalNames: List<Pair<String, PIRType>>,
    val typeVarNames: List<String> = emptyList(),
    val dependencies: Set<PIRDependency> = emptySet()
)

typealias PIRModules = Map<String, PIRModule>