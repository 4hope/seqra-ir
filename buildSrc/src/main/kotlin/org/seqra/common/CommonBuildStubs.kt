package org.seqra.common

import org.gradle.api.Project

fun dep(group: String, name: String, version: String): String =
    "$group:$name:$version"

object KotlinDependency {
    object Versions {
        const val kotlin = "2.1.0"
        const val kotlinxSerialization = "1.7.3"
        const val kotlinxCoroutines = "1.9.0"
        const val kotlinLogging = "5.1.0"
        const val kotlinxCollections = "0.3.8"
    }

    object Plugins {
        const val KotlinSerialization = "org.jetbrains.kotlin.plugin.serialization"
    }

    object Libs {
        const val kotlin_logging = "io.github.microutils:kotlin-logging:3.0.5"
        const val kotlinx_serialization_core =
            "org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3"
        const val kotlinx_coroutines_core =
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0"
        const val kotlinx_collections =
            "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.8"
    }
}

object JunitDependencies {
    object Libs {
        const val junit_bom = "org.junit:junit-bom:5.11.3"
        const val junit_jupiter = "org.junit.jupiter:junit-jupiter"
    }
}

/**
 * В оригинальном seqra-common-build тут, вероятно, есть общая настройка проекта.
 * Для локальной сборки пока достаточно no-op.
 */
fun Any.configureDefault(@Suppress("UNUSED_PARAMETER") name: String) {
    // no-op
}