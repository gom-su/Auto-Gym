plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val distributedVersionCode = providers.gradleProperty("autoGymVersionCode").orNull?.let {
    it.toIntOrNull()?.takeIf { code -> code in 1..2_100_000_000 }
        ?: error("autoGymVersionCode must be an integer between 1 and 2100000000")
}

android {
    namespace = "com.example.workoutpoc.wearapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.workoutpoc"
        minSdk = 33
        targetSdk = 36
        versionCode = distributedVersionCode ?: 2
        versionName = providers.gradleProperty("autoGymVersionName").orElse("1.0.1").get()
        testInstrumentationRunner = "com.example.workoutpoc.wearapp.WearTestRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
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
    implementation(project(":wear-protocol"))
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.wear.compose:compose-material3:1.5.0")
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("The Fragment version pulled by Play services predates the Activity Result API")
        }
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
