import com.android.build.api.dsl.Packaging
import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.rerere.rikkahub.rikkanon"
        minSdk = 26
        targetSdk = 37
        versionCode = 191
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
    androidResources {
        generateLocaleConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

/**
 * 防御性字节码补丁：修复 Compose Foundation 1.13.0-alpha01 中 LazyList / LazyGrid / LazyStaggeredGrid
 * 在 Lookahead 滚动快路径中重复保留并累加旧 `scrollBackAmount`，导致尾项离屏触发重测量时画面猛烈回跳到底部的缺陷。
 *
 * 三层正交防线（全无分支指令织入，不改变 StackMapTable 帧结构）：
 * 1. 数据源头净化：在 `copyWithScrollDeltaWithoutRemeasure` 创建纯位移副本时，将继承的 `this.scrollBackAmount` 替换为 `0f`；
 * 2. 消费端免测量守卫：在 `applyMeasureResult$foundation` 读取 `result.getScrollBackAmount()` 后，若 `visibleItemsStayedTheSame == true` 则将其乘以 `0f`；
 * 3. 瞬移生命周期清理：在 `snapToItemIndexInternal$foundation` / `snapToItemInternal$foundation` 发生位置改变重置 `itemAnimator` 时，同步调用 `_lazyLayoutScrollDeltaBetweenPasses.stop$foundation()`。
 */
abstract class ComposeLazyScrollBackFixVisitorFactory :
    AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        return classData.className in TARGET_CLASSES
    }

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        val className = classContext.currentClassData.className
        val internalClassName = className.replace('.', '/')
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

                // 防线 A：copyWithScrollDeltaWithoutRemeasure(int, boolean) 不再携带旧 scrollBackAmount
                if (
                    className.endsWith("MeasureResult") &&
                        name == "copyWithScrollDeltaWithoutRemeasure" &&
                        descriptor.startsWith("(IZ)L")
                ) {
                    return object : MethodVisitor(Opcodes.ASM9, mv) {
                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String,
                            fieldName: String,
                            fieldDesc: String,
                        ) {
                            if (
                                opcode == Opcodes.GETFIELD &&
                                    owner == internalClassName &&
                                    fieldName == "scrollBackAmount" &&
                                    fieldDesc == "F"
                            ) {
                                super.visitInsn(Opcodes.POP)
                                super.visitInsn(Opcodes.FCONST_0)
                            } else {
                                super.visitFieldInsn(opcode, owner, fieldName, fieldDesc)
                            }
                        }
                    }
                }

                // 防线 B：applyMeasureResult$foundation(..., isLookingAhead, visibleItemsStayedTheSame)
                // 当 visibleItemsStayedTheSame (slot 3) 为 true 时，无分支将 scrollBackAmount 置 0f
                if (
                    className.endsWith("State") &&
                        name.startsWith("applyMeasureResult") &&
                        descriptor.endsWith(";ZZ)V")
                ) {
                    return object : MethodVisitor(Opcodes.ASM9, mv) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            methodName: String,
                            methodDesc: String,
                            isInterface: Boolean,
                        ) {
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface)
                            if (
                                opcode == Opcodes.INVOKEVIRTUAL &&
                                    methodName == "getScrollBackAmount" &&
                                    methodDesc == "()F"
                            ) {
                                super.visitInsn(Opcodes.ICONST_1)
                                super.visitVarInsn(Opcodes.ILOAD, 3)
                                super.visitInsn(Opcodes.ISUB)
                                super.visitInsn(Opcodes.I2F)
                                super.visitInsn(Opcodes.FMUL)
                            }
                        }

                        override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                            super.visitMaxs(maxStack + 2, maxLocals)
                        }
                    }
                }

                // 防线 C：snapToItemIndexInternal / snapToItemInternal 在 positionChanged 重置 itemAnimator 时
                // 同步停止残留的 _lazyLayoutScrollDeltaBetweenPasses 差值动画
                if (
                    className.endsWith("State") &&
                        (name.startsWith("snapToItemIndexInternal") || name.startsWith("snapToItemInternal")) &&
                        descriptor == "(IIZ)V"
                ) {
                    return object : MethodVisitor(Opcodes.ASM9, mv) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            methodName: String,
                            methodDesc: String,
                            isInterface: Boolean,
                        ) {
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface)
                            if (
                                opcode == Opcodes.INVOKEVIRTUAL &&
                                    owner == "androidx/compose/foundation/lazy/layout/LazyLayoutItemAnimator" &&
                                    methodName == "reset" &&
                                    methodDesc == "()V"
                            ) {
                                super.visitVarInsn(Opcodes.ALOAD, 0)
                                super.visitFieldInsn(
                                    Opcodes.GETFIELD,
                                    internalClassName,
                                    "_lazyLayoutScrollDeltaBetweenPasses",
                                    "Landroidx/compose/foundation/lazy/layout/LazyLayoutScrollDeltaBetweenPasses;",
                                )
                                super.visitMethodInsn(
                                    Opcodes.INVOKEVIRTUAL,
                                    "androidx/compose/foundation/lazy/layout/LazyLayoutScrollDeltaBetweenPasses",
                                    "stop\$foundation",
                                    "()V",
                                    false,
                                )
                            }
                        }

                        override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                            super.visitMaxs(maxStack + 1, maxLocals)
                        }
                    }
                }

                return mv
            }
        }
    }

    companion object {
        private val TARGET_CLASSES = setOf(
            "androidx.compose.foundation.lazy.LazyListMeasureResult",
            "androidx.compose.foundation.lazy.LazyListState",
            "androidx.compose.foundation.lazy.grid.LazyGridMeasureResult",
            "androidx.compose.foundation.lazy.grid.LazyGridState",
            "androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridMeasureResult",
            "androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState",
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(
            ComposeLazyScrollBackFixVisitorFactory::class.java,
            InstrumentationScope.ALL,
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Local JVM tests need the desktop native library instead of the Android AAR.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.quickjs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur and glass)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)
    implementation(libs.haze.glass)
    implementation(libs.haze.glass.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ucrop
    implementation(libs.ucrop)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing
    implementation(libs.zxing.core)

    // quickie (qrcode scanner)
    implementation(libs.quickie.bundled)
    implementation(libs.barcode.scanning)
    implementation(libs.androidx.camera.core)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // media3 (video playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // icons
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":mediagen"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":ui"))
    implementation(project(":workspace"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(kotlin("reflect"))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
