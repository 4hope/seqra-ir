package org.seqra.ir.api.py.grpc

import io.grpc.ManagedChannelBuilder
import ir.FileList
import ir.IRServiceGrpcKt
import ir.SourceRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.seqra.ir.api.py.PIRModule
import org.seqra.ir.api.py.mapper.ProtoToPirMapper
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.writeText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonIrClassMetadataTest {

    private val repoRoot: Path = findRepoRoot()
    private val subprojectDir: Path = repoRoot.resolve("seqra-ir-api-py")
    private val fixtureSourcesDir: Path = subprojectDir.resolve("src").resolve("test").resolve("resources").resolve("python")
    private val fuzzCasesDir: Path = subprojectDir.resolve("fuzz_cases")
    private val grpcDirWsl: String = toWslPath(
        repoRoot.resolve("seqra-ir-api-py")
            .resolve("src")
            .resolve("main")
            .resolve("kotlin")
            .resolve("org")
            .resolve("seqra")
            .resolve("ir")
            .resolve("api")
            .resolve("py")
            .resolve("grpc")
    )
    private val serverLog: Path = subprojectDir.resolve("build").resolve("tmp").resolve("python-ir-class-metadata-server.log")
    private val testSourcesDir: Path = subprojectDir.resolve("build").resolve("tmp").resolve("python-ir-class-metadata")
    private val serverHost: String = resolveServerHost()

    private var serverProcess: Process? = null

    @BeforeAll
    fun startGrpcServer() {
        testSourcesDir.createDirectories()
        serverLog.parent.createDirectories()
        Files.deleteIfExists(serverLog)

        serverProcess = ProcessBuilder(
            "wsl.exe",
            "bash",
            "-lc",
            "cd $grpcDirWsl && ./venv/bin/python ./python_server.py --host 0.0.0.0 --port 50051"
        )
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .redirectOutput(serverLog.toFile())
            .start()

        waitForPort(serverHost, 50051, Duration.ofSeconds(20))
    }

    @AfterAll
    fun stopGrpcServer() {
        serverProcess?.destroy()
        serverProcess?.waitFor()

        ProcessBuilder("wsl.exe", "bash", "-lc", "pkill -f python_server.py >/dev/null 2>&1 || true")
            .directory(repoRoot.toFile())
            .start()
            .waitFor()
    }

    @Test
    fun `class metadata survives grpc mapping`() {
        val source = stageFixture("class_metadata_sample.py")

        val modules = runBlocking { fetchModules(listOf(toWslPath(source))) }
        val module = modules.single { it.fullname.endsWith("class_metadata_sample") }
        val base = module.classes.single { it.name == "Base" }
        val child = module.classes.single { it.name == "Child" }

        assertTrue(child.ctor.name.isNotBlank(), "Expected ctor metadata to be populated")
        assertTrue(child.setup.name.isNotBlank(), "Expected setup metadata to be populated")
        assertEquals("Base", child.base?.name)
        assertTrue(child.mro.any { it.name == "Base" }, "Expected Base in Child.mro")
        assertTrue(base.children.any { it.name == "Child" }, "Expected Child in Base.children")
        assertTrue(child.methodDecls.containsKey("__init__"), "Expected __init__ in methodDecls")
        assertTrue(child.properties.containsKey("data"), "Expected property metadata for data")
        assertTrue(child.propertyTypes.containsKey("data"), "Expected property type metadata for data")
        assertEquals("int", child.propertyTypes.getValue("data").typeName)
        assertTrue(!child.vtable.isNullOrEmpty(), "Expected vtable metadata")
        assertTrue(child.vtableEntries.isNotEmpty(), "Expected vtable entries metadata")
        assertNotNull(child.methods["data"], "Expected getter function body to be preserved")
    }

    @Test
    fun `imports and local callable metadata survive grpc mapping`() {
        val source = stageFixture("callable_metadata_sample.py")

        val modules = runBlocking { fetchModules(listOf(toWslPath(source))) }
        val module = modules.single { it.fullname.endsWith("callable_metadata_sample") }
        val callableMethods = module.classes.flatMap { it.methods.values }.filter { it.decl.name == "__call__" }

        assertTrue(module.imports.contains("math"), "Expected module imports to include math")
        assertTrue(
            callableMethods.any { it.isLambda && it.enclosingFunction == "outer" },
            "Expected nested lambda metadata"
        )
        assertTrue(
            callableMethods.any { it.isLocal && !it.isLambda && it.enclosingFunction == "outer" },
            "Expected nested local function metadata"
        )
    }

    private suspend fun fetchModules(sourceFiles: List<String>): List<PIRModule> {
        val channel = ManagedChannelBuilder
            .forAddress(serverHost, 50051)
            .usePlaintext()
            .build()

        try {
            val stub = IRServiceGrpcKt.IRServiceCoroutineStub(channel)
            val response = stub.getAll(
                SourceRequest.newBuilder()
                    .setFiles(
                        FileList.newBuilder()
                            .addAllFiles(sourceFiles)
                            .build()
                    )
                    .setIncludeMetadata(true)
                    .build()
            )

            val errors = buildList {
                addAll(response.errorsList)
                addAll(response.modules.errorsList)
                addAll(response.classes.errorsList)
                addAll(response.cfgs.errorsList)
            }
            assertTrue(response.success, buildFailureMessage("Pipeline failed", errors))

            return ProtoToPirMapper().mapComplete(response)
        } finally {
            channel.shutdown()
        }
    }

    private fun stageFixture(name: String): Path {
        val source = fixtureSourcesDir.resolve(name)
        val target = testSourcesDir.resolve(name)
        target.writeText(Files.readString(source))
        return target
    }

    private fun waitForPort(host: String, port: Int, timeout: Duration) {
        val deadline = Instant.now().plus(timeout)
        var lastError: Exception? = null

        while (Instant.now().isBefore(deadline)) {
            try {
                Socket(host, port).use { return }
            } catch (error: Exception) {
                lastError = error
                Thread.sleep(250)
            }
        }

        val logTail = if (serverLog.exists()) serverLog.toFile().readText() else ""
        throw AssertionError("Timed out waiting for gRPC server on $host:$port\n$logTail", lastError)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (current.resolve("settings.gradle.kts").exists()) {
                return current
            }
            current = current.parent ?: error("Could not locate repo root from ${Path.of("").toAbsolutePath()}")
        }
    }

    private fun toWslPath(path: Path): String {
        val normalized = path.toAbsolutePath().normalize().pathString.replace('\\', '/')
        return if (normalized.length >= 3 && normalized[1] == ':') {
            "/mnt/${normalized[0].lowercaseChar()}${normalized.substring(2)}"
        } else {
            normalized
        }
    }

    private fun resolveServerHost(): String {
        val process = ProcessBuilder("wsl.exe", "bash", "-lc", "hostname -I")
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        return output.split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { "127.0.0.1" }
    }

    private fun buildFailureMessage(prefix: String, errors: List<String>): String {
        val suffix = if (errors.isEmpty()) "" else errors.joinToString(separator = "\n")
        return listOf(prefix, suffix).filter { it.isNotBlank() }.joinToString(separator = "\n")
    }
}
