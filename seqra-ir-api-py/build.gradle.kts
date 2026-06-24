import org.seqra.common.JunitDependencies
import org.seqra.common.KotlinDependency

plugins {
    id("kotlin-conventions")
    id("com.google.protobuf") version Versions.protobuf_gradle_plugin
}

dependencies {
    api(project(":seqra-ir-api-common"))

    api(Libs.asm)
    api(Libs.asm_tree)
    api(Libs.asm_commons)
    api(Libs.asm_util)

    api(KotlinDependency.Libs.kotlinx_coroutines_core)
}

dependencies {
    api(Libs.grpc_stub)
    api(Libs.grpc_protobuf)
    api(Libs.protobuf_java_util)
    api(Libs.protobuf_kotlin)
    api(Libs.grpc_kotlin_stub)

    implementation("io.grpc:grpc-netty-shaded:${Versions.grpc}")

    testImplementation(platform(JunitDependencies.Libs.junit_bom))
    testImplementation(JunitDependencies.Libs.junit_jupiter)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn") }
}

tasks.register<JavaExec>("runPyGrpcClient") {
    group = "application"
    description = "Runs the Kotlin gRPC client for Python IR conversion"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.seqra.ir.api.py.grpc.MainKt")
}

protobuf {
    protoc {
        artifact = Libs.protoc.toString()
    }
    plugins {
        create("grpc") { artifact = Libs.protoc_gen_grpc_java.toString() }
        create("grpckt") { artifact = Libs.protoc_gen_grpc_kotlin.toString() + ":jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                create("kotlin")
            }
        }
    }
}