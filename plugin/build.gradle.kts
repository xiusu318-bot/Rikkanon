import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.plugin"
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    }
}

// JVM 单元测试使用 quickjs-kt 的桌面原生库（同 :app / :search）
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(project(":ai"))
    implementation(project(":common"))
    implementation(libs.quickjs)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
