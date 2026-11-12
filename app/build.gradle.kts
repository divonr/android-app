import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The code sandbox's key and URL are not in the sources: they come from local.properties
// (gitignored: sandbox.apiKey, sandbox.executorUrl) or from the build's environment. A build
// without the key succeeds; the tool then reports that it is not configured.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun sandboxSetting(property: String, env: String, default: String = ""): String =
    (localProperties.getProperty(property) ?: System.getenv(env) ?: default).trim()
fun String.asJavaLiteral(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.ApI"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ApI"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SANDBOX_API_KEY", sandboxSetting("sandbox.apiKey", "SANDBOX_API_KEY").asJavaLiteral())
        buildConfigField(
            "String", "SANDBOX_EXECUTOR_URL",
            sandboxSetting("sandbox.executorUrl", "SANDBOX_EXECUTOR_URL", "https://api-divonr.xyz/v2/execute").asJavaLiteral()
        )
    }

    signingConfigs {
        getByName("debug") {
            // Use custom keystore from environment variable if available (for CI)
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
            }
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // Shared logic module
    implementation(project(":shared"))

    // Core library desugaring for Java 8+ APIs
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
    
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose BOM and UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Material Icons Extended (needed for Icons.Default.Key, Icons.Default.Forum)
    implementation("androidx.compose.material:material-icons-extended")
    
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    
    // JSON & Serialization
    implementation(libs.kotlinx.serialization)
    
    // Image Loading
    implementation(libs.coil)
    
    // Permissions
    implementation(libs.accompanist.permissions)
    
    // Markdown support - CommonMark
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")
    
    // Camera
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Security Crypto
    implementation(libs.androidx.security.crypto)

    // Chrome Custom Tabs (for WebView fallback)
    implementation("androidx.browser:browser:1.7.0")

    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    
    // Coroutines for Play Services (await() extension)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Google APIs Client (for Gmail, Calendar, Drive)
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20231218-2.0.0")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20231123-2.0.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.1")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
