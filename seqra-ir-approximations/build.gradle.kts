import org.seqra.common.JunitDependencies
import org.seqra.common.KotlinDependency

plugins {
    id("kotlin-conventions")
}

dependencies {
    implementation(project(":seqra-ir-api-jvm"))
    implementation(project(":seqra-ir-core"))
    testImplementation(platform(JunitDependencies.Libs.junit_bom))
    testImplementation(JunitDependencies.Libs.junit_jupiter)
//    implementation(Libs.jooq)

    testImplementation(testFixtures(project(":seqra-ir-core")))
    testImplementation(testFixtures(project(":seqra-ir-storage")))
    testImplementation(KotlinDependency.Libs.kotlin_logging)
//    testRuntimeOnly(Libs.guava)
}
