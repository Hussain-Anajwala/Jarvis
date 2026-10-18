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
        // ONNX Runtime ships native code per ABI; keep 64-bit phones and x86_64 emulators only (~halves the APK).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
        // Optional services. local.properties (git-ignored) wins; an environment variable of the same name also works.
        // A key compiled into an app is extractable from the APK, so use keys you can restrict and revoke.
        fun optionalSecret(name: String) = localProperties.getProperty(name, "").trim().ifBlank { System.getenv(name)?.trim().orEmpty() }
        val elevenLabsApiKey = optionalSecret("ELEVENLABS_API_KEY")
        val googleRoutesApiKey = optionalSecret("GOOGLE_ROUTES_API_KEY")
        val mapboxAccessToken = optionalSecret("MAPBOX_ACCESS_TOKEN")
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
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${buildConfigString(elevenLabsApiKey)}\"")
        buildConfigField("String", "GOOGLE_ROUTES_API_KEY", "\"${buildConfigString(googleRoutesApiKey)}\"")
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"${buildConfigString(mapboxAccessToken)}\"")
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
    // SplashScreen API (system splash on Android 12+, the same look on older versions).
    implementation("androidx.core:core-splashscreen:1.0.1")
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
    // Google Identity Services AuthorizationClient for the gmail.readonly scope (M4); no GoogleSignIn.
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    // On-device "Hey JARVIS" wake word: ONNX Runtime (MIT) runs the openWakeWord models in assets/wakeword.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Compose pulls graphics-path transitively; 1.0.1 ships a 16 KB page-aligned native library.
    implementation("androidx.graphics:graphics-path:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
