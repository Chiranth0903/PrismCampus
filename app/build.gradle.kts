import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets stay in ignored local.properties or the build environment.
val prismLocalProperties = Properties().apply {
    val settings = rootProject.file("local.properties")
    if (settings.exists()) settings.inputStream().use { load(it) }
}
val geminiApiKey = providers.environmentVariable("GEMINI_API_KEY")
    .getOrElse(prismLocalProperties.getProperty("GEMINI_API_KEY", ""))
val escapedGeminiApiKey = geminiApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

// Allows JVM-only checks on machines without an installed Android NDK.
val enableNativeQwen = providers.gradleProperty("skipNativeQwen").orNull != "true"
val bundleQwenModel by tasks.registering(Sync::class) {
    from("../server/models") { include("qwen2.5-0.5b-instruct-q4_k_m.gguf") }
    into(layout.buildDirectory.dir("generated/qwenAssets"))
}

android {
    namespace = "com.prismcampus.app"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.prismcampus.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "MEMORY_PROFILING", (providers.gradleProperty("memoryProfiling").orNull == "true").toString())
        val registryUrl = providers.gradleProperty("campusRegistryUrl").orElse(providers.environmentVariable("PUBLIC_CAMPUS_REGISTRY_URL")).getOrElse("")
        require(registryUrl.isEmpty() || registryUrl.startsWith("https://")) { "The public campus registry must use HTTPS" }
        buildConfigField("String", "CAMPUS_REGISTRY_URL", "\"${registryUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        testInstrumentationRunner = if (providers.gradleProperty("memoryProfiling").orNull == "true") {
            "com.prismcampus.app.ai.MemoryProfileInstrumentation"
        } else if (providers.gradleProperty("offlineSmokeTest").orNull == "true") {
            "com.prismcampus.app.ai.OfflineSmokeInstrumentation"
        } else "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        if (enableNativeQwen) {
            externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        }
        buildConfigField("String", "GEMINI_API_KEY", "\"$escapedGeminiApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (enableNativeQwen) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/qwenAssets"))
    androidResources { noCompress += "gguf" }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.named("preBuild").configure { dependsOn(bundleQwenModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Jetpack Compose BOM & UI
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Google ML Kit Text Recognition (Bundled standalone model - 100% offline, zero network download needed)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Gson JSON Parser
    implementation("com.google.code.gson:gson:2.10.1")

    // OSMDroid for offline/cached interactive maps
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Vosk Offline Speech Recognition
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Debugging UI tools
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
