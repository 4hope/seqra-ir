package org.seqra.ir.api.py.grpc

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonIrFuzzCasesTest {

    private val repoRoot: Path = findRepoRoot()
    private val subprojectDir: Path = repoRoot.resolve("seqra-ir-api-py")
    private val fuzzCasesDir: Path = subprojectDir.resolve("fuzz_cases")
    private val originalsDir: Path = fuzzCasesDir.resolve("originals")
    private val fixtureSourcesDir: Path = subprojectDir.resolve("src").resolve("test").resolve("resources").resolve("python")
    private val generatedRootDir: Path = subprojectDir.resolve("build").resolve("tmp").resolve("python-ir-fuzz-generated")
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
    private val fuzzScript: Path = fuzzCasesDir.resolve("fuzz_compare.py")
    private val serverLog: Path = subprojectDir.resolve("build").resolve("tmp").resolve("python-ir-server.log")
    private val serverHost: String = resolveServerHost()

    private var serverProcess: Process? = null

    @BeforeAll
    fun startGrpcServer() {
        generatedRootDir.createDirectories()
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

    @TestFactory
    fun fuzzCases(): List<DynamicTest> {
        val originals = originalsDir.listDirectoryEntries("*.py").sortedBy { it.fileName.toString() }
        assertTrue(originals.isNotEmpty(), "No fuzz cases found in ${originalsDir.absolutePathString()}")

        return originals.map { originalPath ->
            DynamicTest.dynamicTest(originalPath.fileName.toString()) {
                val caseOutputDir = generatedRootDir.resolve(originalPath.nameWithoutExtension)
                caseOutputDir.toFile().deleteRecursively()
                caseOutputDir.createDirectories()
                val generatedPath = caseOutputDir.resolve("${originalPath.nameWithoutExtension}_generated.py")

                val result = runBlocking {
                    runPythonIrPipeline(
                        sourceFiles = listOf(toWslPath(originalPath)),
                        outputDir = caseOutputDir.toFile(),
                        host = serverHost
                    )
                }

                assertTrue(result.success, buildFailureMessage("Pipeline failed", result.errors))
                assertTrue(result.outputJson.exists(), "Missing output.json in ${caseOutputDir.absolutePathString()}")
                assertTrue(generatedPath.exists(), "Missing generated file ${generatedPath.absolutePathString()}")
                assertTrue(
                    result.generatedFiles.any { it.toPath().normalize() == generatedPath.normalize() },
                    "Pipeline returned unexpected generated files: ${result.generatedFiles}"
                )

                runCommand(
                    listOf("py", "-3.12", "-m", "py_compile", generatedPath.pathString),
                    "Python syntax check failed for ${generatedPath.fileName}"
                )

                runCommand(
                    listOf(
                        "py", "-3.12", fuzzScript.pathString,
                        "--original-file", originalPath.pathString,
                        "--generated-file", generatedPath.pathString,
                        "--iterations", "300",
                        "--seed", "20260514"
                    ),
                    "Fuzz comparison failed for ${originalPath.fileName}"
                )
            }
        }
    }

    @Test
    fun `pipeline emits python for multiple source files`() {
        val caseRoot = generatedRootDir.resolve("multi_file_case")
        caseRoot.toFile().deleteRecursively()
        caseRoot.createDirectories()

        val fixtureRoot = fixtureSourcesDir.resolve("multi_file_case")
        val initFile = stageFixture(fixtureRoot.resolve("pkg").resolve("__init__.py"), caseRoot.resolve("pkg").resolve("__init__.py"))
        val helpersFile = stageFixture(fixtureRoot.resolve("pkg").resolve("helpers.py"), caseRoot.resolve("pkg").resolve("helpers.py"))
        val mainFile = stageFixture(fixtureRoot.resolve("main.py"), caseRoot.resolve("main.py"))

        val outputDir = caseRoot.resolve("generated")
        outputDir.toFile().deleteRecursively()
        outputDir.createDirectories()

        val result = runBlocking {
            runPythonIrPipeline(
                sourceFiles = listOf(
                    toWslPath(mainFile),
                    toWslPath(helpersFile),
                    toWslPath(initFile)
                ),
                outputDir = outputDir.toFile(),
                host = serverHost
            )
        }

        assertTrue(result.success, buildFailureMessage("Pipeline failed", result.errors))
        assertEquals(3, result.moduleCount, "Expected package, helper, and entry modules")

        val generatedMain = outputDir.resolve("main_generated.py")
        val generatedPkg = outputDir.resolve("pkg_generated.py")
        val generatedHelpers = outputDir.resolve("pkg").resolve("helpers_generated.py")

        assertTrue(generatedMain.exists(), "Missing generated entry module")
        assertTrue(generatedPkg.exists(), "Missing generated package module")
        assertTrue(generatedHelpers.exists(), "Missing generated helper module")

        assertTrue(
            result.generatedFiles.map { it.toPath().normalize() }.containsAll(
                listOf(generatedMain, generatedPkg, generatedHelpers).map { it.normalize() }
            ),
            "Pipeline returned unexpected generated files: ${result.generatedFiles}"
        )

        assertTrue(
            generatedMain.readText().contains("__pir_import_module(\"pkg.helpers_generated\", \"pkg.helpers\")"),
            "Expected generated main module to import generated helper module"
        )

        runCommand(
            listOf("py", "-3.12", "-m", "py_compile", generatedPkg.pathString, generatedHelpers.pathString, generatedMain.pathString),
            "Python syntax check failed for multi-file generated output"
        )
    }

    private fun runCommand(command: List<String>, errorMessage: String) {
        val process = ProcessBuilder(command)
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "$errorMessage\n$output")
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

    private fun stageFixture(source: Path, target: Path): Path {
        target.parent?.createDirectories()
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target
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
