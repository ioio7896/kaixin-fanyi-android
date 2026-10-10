import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

val workspaceRoot = rootProject.projectDir.parentFile
val modelDir = workspaceRoot.resolve("models/Hy-MT2-1.8B-GGUF")
val modelFile = modelDir.resolve("Hy-MT2-1.8B-Q4_K_M.gguf")
val modelLicense = modelDir.resolve("LICENSE.txt")
val bundledModelAssets = layout.buildDirectory.dir("generated/model-assets")

val packageBundledModel by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the local Hy-MT2 GGUF and its license into the APK assets."
    from(modelFile) {
        into("models")
    }
    from(modelLicense) {
        into("models")
    }
    archiveFileName.set("hy-mt2-model.zip")
    destinationDirectory.set(bundledModelAssets)
    entryCompression = ZipEntryCompression.STORED
    doFirst {
        check(modelFile.isFile) {
            "Missing bundled model: ${modelFile.absolutePath}. Place the official Q4_K_M GGUF there before building."
        }
        check(modelLicense.isFile) {
            "Missing model license: ${modelLicense.absolutePath}. Keep the upstream license beside the model."
        }
    }
}

android {
    namespace = "com.localtranslate.hymt2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.localtranslate.hymt2"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndkVersion = "27.2.12479018"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                )
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Keep the inner model bundle as-is in the APK. It is already a STORED zip;
    // APK compression would waste build time and slow the first-run extraction.
    androidResources {
        noCompress += listOf("zip", "bz2")
    }

    sourceSets.getByName("main").assets.srcDir(bundledModelAssets)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild").configure {
    dependsOn(packageBundledModel)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation(
        files(
            "libs/commons-compress-1.27.1.jar",
            "libs/commons-io-2.16.1.jar",
            "libs/commons-lang3-3.16.0.jar",
            "libs/commons-codec-1.17.1.jar",
            "libs/sherpa-onnx-static-link-onnxruntime-1.13.4.aar",
        ),
    )
    kapt("androidx.room:room-compiler:2.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Align the test/lint classpath with the app's cached AndroidX Core version.
    androidTestImplementation("androidx.core:core-ktx:1.13.1")
}

kapt {
    correctErrorTypes = true
}
