package org.seqra.ir.api.py

import java.util.Collections.*
import org.seqra.ir.api.common.CommonProject
import java.io.Closeable
import java.nio.file.Path

enum class PIRLocationType {
    RUNTIME,
    APP,
    LIB,
    SITE_PACKAGES
}

interface PIRSourceLocation {
    val path: String
    val locationType: PIRLocationType
    val pythonVersion: PythonVersion
}

data class PythonVersion(
    val major: Int,
    val minor: Int
) {
    override fun toString(): String = "$major.$minor"
}

interface PIRSourceFile {
    val moduleName: String
    val filePath: Path
    val sourceCode: String
    val location: PIRRegisteredLocation
    val ast: Any?
}

data class PIRRegisteredLocation(
    override val path: String,
    override val locationType: PIRLocationType,
    override val pythonVersion: PythonVersion,
    val isStdlib: Boolean = false,
    val id: Long = System.currentTimeMillis()
) : PIRSourceLocation {
    val pIRLocation: PIRSourceLocation? = null
    val isRuntime: Boolean = locationType == PIRLocationType.RUNTIME
}

interface PIRDatabase : Closeable {
    val id: String
    val locations: List<PIRRegisteredLocation>
    val persistence: PIRDatabasePersistence

    val pythonVersion: PythonVersion

    suspend fun project(sourceDirs: List<Path>, features: List<PIRProjectFeature>?): PIRProject
    suspend fun project(sourceDirs: List<Path>): PIRProject = project(sourceDirs, null)

    suspend fun load(sourceDir: Path): PIRDatabase

    suspend fun load(sourceDirs: List<Path>): PIRDatabase

    suspend fun loadLocations(locations: List<PIRSourceLocation>): PIRDatabase

    suspend fun refresh()

    suspend fun rebuildFeatures()

    fun watchFileSystemChanges(): PIRDatabase

    suspend fun awaitBackgroundJobs()
    suspend fun cancelBackgroundJobs()

    suspend fun setImmutable()

    fun isInstalled(feature: PIRFeature): Boolean = features.contains(feature)

    val features: List<PIRFeature>
}

interface PIRDatabasePersistence : Closeable {

    val locations: List<PIRSourceLocation>

    val storage: Any

    fun setup()

    fun tryLoad(databaseId: String): Boolean = false

    fun <T> write(action: () -> T): T
    fun <T> read(action: () -> T): T

    fun persist(location: PIRRegisteredLocation, files: List<PIRSourceFile>)

    fun findSymbolId(symbol: String): Long
    fun findSymbolName(symbolId: Long): String
    fun findLocation(locationId: Long): PIRRegisteredLocation

    val symbolInterner: Any
    fun findSourceCode(moduleId: Long): String

    fun findSourceFileByName(project: PIRProject, fullName: String): PIRSourceFile?
    fun findSourceFiles(db: PIRDatabase, location: PIRRegisteredLocation): List<PIRSourceFile>
    fun findSourceFiles(project: PIRProject, fullName: String): List<PIRSourceFile>

    fun createIndexes() {}

    fun setImmutable(databaseId: String) {}
}

interface PIRProject : Closeable, CommonProject {

    val locations: List<PIRRegisteredLocation>
    val features: List<PIRProjectFeature>?

    fun allModules(): Collection<PIRModule> = emptyList()

    fun findModuleOrNull(name: String): PIRModule?

    fun findModules(name: String): Set<PIRModule>

    fun findTypeOrNull(name: String): PIRType?

    fun findFunctionOrNull(name: String): PIRFunc?

    fun findClassOrNull(name: String): PIRClass?

    suspend fun refreshed(closeOld: Boolean): PIRProject

    suspend fun <T : PIRProjectTask> execute(task: T): T

    fun isInstalled(feature: PIRProjectFeature): Boolean
}

interface PIRProjectTask

interface PIRFeature

interface PIRProjectFeature : PIRFeature {
    fun analyzeModule(module: PIRModule): Any
    fun analyzeFunction(func: PIRFunc): Any
    fun analyzeClass(clazz: PIRClass): Any
}

class PIRTypeInferenceFeature : PIRProjectFeature {
    override fun analyzeModule(module: PIRModule): Any {
        return TypeInferenceResult()
    }

    override fun analyzeFunction(func: PIRFunc): Any {
        return FunctionTypeInfo()
    }

    override fun analyzeClass(clazz: PIRClass): Any {
        return ClassTypeInfo()
    }
}

class PIRCFGFeature : PIRProjectFeature {
    override fun analyzeModule(module: PIRModule): Any {
        return module.functions.associate { it.name to it.flowGraph() }
    }

    override fun analyzeFunction(func: PIRFunc): Any {
        return func.flowGraph()
    }

    override fun analyzeClass(clazz: PIRClass): Any {
        return clazz.methods.mapValues { (_, func) -> func.flowGraph() }
    }
}

class PIRDependencyAnalysisFeature : PIRProjectFeature {
    override fun analyzeModule(module: PIRModule): Any {
        val imports = module.imports
        val dependencies = mutableSetOf<String>()

        return DependencyGraph(imports, dependencies.toList())
    }

    override fun analyzeFunction(func: PIRFunc): Any {
        return FunctionDependencies()
    }

    override fun analyzeClass(clazz: PIRClass): Any {
        return ClassDependencies()
    }
}

data class TypeInferenceResult(val inferredTypes: Map<String, PIRType> = emptyMap())
data class FunctionTypeInfo(val returnType: PIRType? = null, val paramTypes: Map<String, PIRType> = emptyMap())
data class ClassTypeInfo(val attributeTypes: Map<String, PIRType> = emptyMap(), val methodSignatures: Map<String, FunctionTypeInfo> = emptyMap())
data class DependencyGraph(val imports: List<String>, val dependencies: List<String>)
data class FunctionDependencies(val usedModules: Set<String> = emptySet(), val calledFunctions: Set<String> = emptySet())
data class ClassDependencies(val baseClasses: Set<String> = emptySet(), val usedTypes: Set<String> = emptySet())
