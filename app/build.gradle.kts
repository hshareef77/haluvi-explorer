plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hussainshareef.haluviexplorer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hussainshareef.haluviexplorer"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("HALUVI_RELEASE_VERSION_CODE")?.toIntOrNull() ?: 29).also { require(it >= 29) { "Version code below distributed APK floor" } }
        versionName = (System.getenv("HALUVI_RELEASE_VERSION_NAME") ?: "0.1.1")
    }
    val keyStorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    if (!keyStorePath.isNullOrBlank()) {
        signingConfigs {
            create("permanentRelease") {
                storeFile = file(keyStorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "release"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") { signingConfig = signingConfigs.getByName("permanentRelease") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))
    implementation("com.google.android.libraries.places:places:3.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
