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
    // rikka.hook：方法 Hook 引擎（仅在插件调用 rikka.hook 时才加载原生库）
    implementation(libs.pine.core)
    // rikka.java：放开隐藏 API 反射限制（仅在插件首次调用 rikka.java 时生效）
    implementation(libs.hiddenapibypass)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
