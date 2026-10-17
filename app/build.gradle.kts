import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    // Exported Room schemas are checked in so migrations can be tested (docs/SCHEMA.md).
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.jarvis"
    // Room migration tests read the exported schemas as assets.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileSdk = 35
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        val localProperties = Properties()
        val propertiesFile = rootProject.file("local.properties")
        if (propertiesFile.exists()) {
            propertiesFile.inputStream().use { localProperties.load(it) }
        }
        val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY", "").trim()
        val geminiApiKey2 = localProperties.getProperty("GEMINI_API_KEY_2", "").trim()
        val geminiModel = localProperties.getProperty("GEMINI_MODEL", "gemini-flash-latest")
            .trim()
            .ifBlank { "gemini-flash-latest" }
        val groqApiKey = localProperties.getProperty("GROQ_API_KEY", "").trim()
        val groqModel = localProperties.getProperty("GROQ_MODEL", "llama-3.3-70b-versatile")
            .trim()
            .ifBlank { "llama-3.3-70b-versatile" }
        val youtubeApiKey = localProperties.getProperty("YOUTUBE_API_KEY", "").trim()
        val geminiDailyBudget = localProperties.getProperty("GEMINI_DAILY_BUDGET", "18").toIntOrNull() ?: 18
        val geminiMinuteBudget = localProperties.getProperty("GEMINI_MINUTE_BUDGET", "4").toIntOrNull() ?: 4
        fun buildConfigString(value: String) =
            value.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${buildConfigString(geminiApiKey)}\"")
        buildConfigField("String", "GEMINI_API_KEY_2", "\"${buildConfigString(geminiApiKey2)}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${buildConfigString(geminiModel)}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${buildConfigString(groqApiKey)}\"")
        buildConfigField("String", "GROQ_MODEL", "\"${buildConfigString(groqModel)}\"")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${buildConfigString(youtubeApiKey)}\"")
        buildConfigField("int", "GEMINI_DAILY_BUDGET", geminiDailyBudget.coerceAtLeast(0).toString())
        buildConfigField("int", "GEMINI_MINUTE_BUDGET", geminiMinuteBudget.coerceAtLeast(0).toString())
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
