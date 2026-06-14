package org.seqra.ir.api.py.grpc

import com.google.protobuf.util.JsonFormat
import io.grpc.ManagedChannelBuilder
import ir.FileList
import ir.IRServiceGrpcKt
import ir.SourceRequest
import kotlinx.coroutines.runBlocking
import org.seqra.ir.api.py.PIRModule
import org.seqra.ir.api.py.emit.PIRToPythonEmitter
import org.seqra.ir.api.py.mapper.ProtoToPirMapper
import java.io.File

private const val DEFAULT_SOURCE_PATH = "/mnt/c/MKN/project2/test.py"

data class PythonIrPipelineResult(
    val success: Boolean,
    val outputJson: File,
    val generatedFiles: List<File>,
    val errors: List<String>,
    val moduleCount: Int,
    val classCount: Int,
    val cfgCount: Int
)

fun main(args: Array<String>): Unit = runBlocking {
    val sourceFiles = if (args.isNotEmpty()) args.toList() else listOf(DEFAULT_SOURCE_PATH)

    val result = runPythonIrPipeline(sourceFiles)

    println("Success: ${result.success}")
    println("Module count: ${result.moduleCount}")
    println("Class count: ${result.classCount}")
    println("CFG count: ${result.cfgCount}")

    if (!result.success) {
        result.errors.forEach { println("Error: $it") }
        return@runBlocking
    }

    result.generatedFiles.forEach { outputFile ->
        println("Generated Python file: ${outputFile.path}")
    }
}

suspend fun runPythonIrPipeline(
    sourceFiles: List<String>,
    outputDir: File = File("."),
    host: String = "localhost",
    port: Int = 50051
): PythonIrPipelineResult {
    outputDir.mkdirs()

    val channel = ManagedChannelBuilder
        .forAddress(host, port)
        .usePlaintext()
        .build()
    try {
        val stub = IRServiceGrpcKt.IRServiceCoroutineStub(channel)

        val request = SourceRequest.newBuilder()
            .setFiles(
                FileList.newBuilder()
                    .addAllFiles(sourceFiles)
                    .build()
            )
            .setIncludeMetadata(true)
            .build()

        val response = try {
            stub.getAll(request)
        } catch (error: Exception) {
            return PythonIrPipelineResult(
                success = false,
                outputJson = outputDir.resolve("output.json").absoluteFile,
                generatedFiles = emptyList(),
                errors = listOf(error.message ?: error::class.qualifiedName ?: "Unknown gRPC error"),
                moduleCount = 0,
                classCount = 0,
                cfgCount = 0
            )
        }

        val jsonString = JsonFormat.printer()
            .includingDefaultValueFields()
            .preservingProtoFieldNames()
            .print(response)

        val outputJson = outputDir.resolve("output.json").absoluteFile
        outputJson.writeText(jsonString)

        val errors = buildList {
            addAll(response.errorsList)
            addAll(response.modules.errorsList)
            addAll(response.classes.errorsList)
            addAll(response.cfgs.errorsList)
        }

        if (!response.success) {
            return PythonIrPipelineResult(
                success = false,
                outputJson = outputJson,
                generatedFiles = emptyList(),
                errors = errors,
                moduleCount = response.modules.modulesCount,
                classCount = response.classes.classesCount,
                cfgCount = response.cfgs.functionCfgsCount
            )
        }

        val mapper = ProtoToPirMapper()
        val pirModules = mapper.mapComplete(response)
        val emitter = PIRToPythonEmitter()
        val generatedFiles = pirModules.map { module ->
            val code = emitter.emitModule(module)
            val outputFile = generatedFileFor(module, outputDir).absoluteFile
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(code)
            outputFile
        }

        return PythonIrPipelineResult(
            success = true,
            outputJson = outputJson,
            generatedFiles = generatedFiles,
            errors = errors,
            moduleCount = response.modules.modulesCount,
            classCount = response.classes.classesCount,
            cfgCount = response.cfgs.functionCfgsCount
        )
    } finally {
        channel.shutdown()
    }
}

private fun generatedFileFor(module: PIRModule, outputDir: File): File {
    val parts = module.fullname.split('.').filter { it.isNotBlank() }
    val fileName = (parts.lastOrNull() ?: "module") + "_generated.py"
    val parent = parts.dropLast(1).joinToString(File.separator)
    return if (parent.isBlank()) outputDir.resolve(fileName) else outputDir.resolve(File(parent, fileName).path)
}
