plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// CI supplies an increasing build number and the existing debug signing key. Ordinary local builds keep their defaults.
val distributedVersionCode = providers.gradleProperty("autoGymVersionCode").orNull?.let {
    it.toIntOrNull()?.takeIf { code -> code in 1..2_100_000_000 }
        ?: error("autoGymVersionCode must be an integer between 1 and 2100000000")
}
android {
    namespace = "com.example.workoutpoc"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.workoutpoc"
        minSdk = 26
        targetSdk = 36
        versionCode = distributedVersionCode ?: 2
        versionName = providers.gradleProperty("autoGymVersionName").orElse("1.0.1").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    providers.environmentVariable("AUTO_GYM_DEBUG_KEYSTORE").orNull?.let { path ->
        val existingKey = file(path)
        require(existingKey.isFile) { "AUTO_GYM_DEBUG_KEYSTORE must point to the existing signing key" }
        signingConfigs.getByName("debug") {
            storeFile = existingKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.datastore:datastore:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Official Claude SDK; only the agent package uses the network (decision 0009).
    implementation("com.anthropic:anthropic-java:3.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.datastore:datastore-core-okio:1.1.7")
    // Local stand-in for the Claude API; same OkHttp version the SDK brings.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}
