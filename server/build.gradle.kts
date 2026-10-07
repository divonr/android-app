plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.example.ApI.server.ServerMainKt")
}

dependencies {
    implementation(project(":shared"))

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.auth)
    // ktor-server-sse requires Ktor 3.x; add in P3 when streaming endpoint is built

    // Serialization & coroutines (shared also brings these, but explicit is cleaner)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.logback.classic)

    // Test
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    // Tests must never reach the live sync server or the live data dir, even through a
    // module() default: point both at harmless places (tests inject their own anyway)
    environment("SYNC_SERVER_URL", "http://127.0.0.1:9")
    environment("LLM_WEB_DATA_DIR", layout.buildDirectory.dir("test-data-dir").get().asFile.absolutePath)
}
