plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dogukan.muzik"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dogukan.muzik"
        minSdk = 24
        targetSdk = 34
        val yapi = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = 100 + yapi
        versionName = "1.0.$yapi"
    }
    signingConfigs {
        create("sabit") {
            val yol = System.getenv("KS_PATH")
            if (yol != null) {
                storeFile = file(yol)
                storeType = "pkcs12"
                storePassword = System.getenv("KS_PASS")
                keyAlias = "cizgi"
                keyPassword = System.getenv("KS_PASS")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (System.getenv("KS_PATH") != null) signingConfig = signingConfigs.getByName("sabit")
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.guava:guava:33.3.1-android")
}
