plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.workspace"

    val hasPrebuiltJni = file("src/main/jniLibs/arm64-v8a/libworkspace.so").exists() &&
        file("src/main/jniLibs/arm64-v8a/libtermux.so").exists()
    if (!hasPrebuiltJni) {
        defaultConfig {
            externalNativeBuild {
                cmake {
                    cppFlags += ""
                }
            }
        }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
