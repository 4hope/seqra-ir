import org.gradle.plugin.use.PluginDependenciesSpec
import org.seqra.common.KotlinDependency
import org.seqra.common.dep

object KotlinDependencyExt {
    object Libs {
        val kotlin_metadata_jvm = dep(
            group = "org.jetbrains.kotlin",
            name = "kotlin-metadata-jvm",
            version = KotlinDependency.Versions.kotlin
        )
    }
}

fun PluginDependenciesSpec.kotlinSerialization() =
    id(KotlinDependency.Plugins.KotlinSerialization)
        .version(KotlinDependency.Versions.kotlin)