@file:Suppress("ConstPropertyName")

import org.seqra.common.dep

object Versions {
    const val asm = "9.7.1"
    const val guava = "31.1-jre"

    // hikaricp version compatible with Java 8
    const val hikaricp = "4.0.3"
    const val jooq = "3.14.16"
    const val sqlite = "3.41.2.2"

    const val xodus = "2.0.1"
    const val rocks_db = "9.1.1"
    const val lmdb_java = "0.9.0"

    const val jdot = "1.0"

    const val protobuf = "3.25.1"
    const val grpc = "1.62.2"
    const val grpc_kotlin = "1.4.1"
    const val protobuf_gradle_plugin = "0.9.4"
}

object Libs {
    val asm = dep(
        group = "org.ow2.asm",
        name = "asm",
        version = Versions.asm
    )
    val asm_tree = dep(
        group = "org.ow2.asm",
        name = "asm-tree",
        version = Versions.asm
    )
    val asm_commons = dep(
        group = "org.ow2.asm",
        name = "asm-commons",
        version = Versions.asm
    )
    val asm_util = dep(
        group = "org.ow2.asm",
        name = "asm-util",
        version = Versions.asm
    )

    val guava = dep(
        group = "com.google.guava",
        name = "guava",
        version = Versions.guava
    )

    val xodusUtils = dep(
        group = "org.jetbrains.xodus",
        name = "xodus-utils",
        version = Versions.xodus
    )

    val xodusApi = dep(
        group = "org.jetbrains.xodus",
        name = "xodus-openAPI",
        version = Versions.xodus
    )

    val xodusEnvironment = dep(
        group = "org.jetbrains.xodus",
        name = "xodus-environment",
        version = Versions.xodus
    )

    val lmdb_java = dep(
        group = "org.lmdbjava",
        name = "lmdbjava",
        version = Versions.lmdb_java
    )

    val rocks_db = dep(
        group = "org.rocksdb",
        name = "rocksdbjni",
        version = Versions.rocks_db
    )

    val hikaricp = dep(
        group = "com.zaxxer",
        name = "HikariCP",
        version = Versions.hikaricp
    )

    val sqlite = dep(
        group = "org.xerial",
        name = "sqlite-jdbc",
        version = Versions.sqlite
    )

    val jooq = dep(
        group = "org.jooq",
        name = "jooq",
        version = Versions.jooq
    )
    val jooq_meta = dep(
        group = "org.jooq",
        name = "jooq-meta",
        version = Versions.jooq
    )
    val jooq_meta_extensions = dep(
        group = "org.jooq",
        name = "jooq-meta-extensions",
        version = Versions.jooq
    )
    val jooq_codegen = dep(
        group = "org.jooq",
        name = "jooq-codegen",
        version = Versions.jooq
    )
    val jooq_kotlin = dep(
        group = "org.jooq",
        name = "jooq-kotlin",
        version = Versions.jooq
    )

    val jdot = dep(
        group = "info.leadinglight",
        name = "jdot",
        version = Versions.jdot
    )

    val protobuf_kotlin = dep(
        group = "com.google.protobuf",
        name = "protobuf-kotlin",
        version = Versions.protobuf
    )

    val protobuf_java_util = dep(
        group = "com.google.protobuf",
        name = "protobuf-java-util",
        version = Versions.protobuf
    )

    val protoc = dep(
        group = "com.google.protobuf",
        name = "protoc",
        version = Versions.protobuf
    )

    val grpc_kotlin_stub = dep(
        group = "io.grpc",
        name = "grpc-kotlin-stub",
        version = Versions.grpc_kotlin
    )

    val grpc_protobuf = dep(
        group = "io.grpc",
        name = "grpc-protobuf",
        version = Versions.grpc
    )

    val grpc_stub = dep(
        group = "io.grpc",
        name = "grpc-stub",
        version = Versions.grpc
    )

    val grpc_okhttp = dep(
        group = "io.grpc",
        name = "grpc-okhttp",
        version = Versions.grpc
    )

    val protoc_gen_grpc_java = dep(
        group = "io.grpc",
        name = "protoc-gen-grpc-java",
        version = Versions.grpc
    )

    val protoc_gen_grpc_kotlin = dep(
        group = "io.grpc",
        name = "protoc-gen-grpc-kotlin",
        version = Versions.grpc_kotlin
    )

}
