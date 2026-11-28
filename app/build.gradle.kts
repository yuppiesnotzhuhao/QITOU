import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val qitouSigningProperties = Properties().apply {
    val localFile = file("${System.getProperty("user.home")}/.android/qitou-release.properties")
    if (localFile.isFile) {
        localFile.inputStream().use(::load)
    }
}

fun signingValue(property: String, environment: String): String? =
    System.getenv(environment)?.takeIf(String::isNotBlank)
        ?: qitouSigningProperties.getProperty(property)?.takeIf(String::isNotBlank)

val releaseStoreFile = signingValue("storeFile", "QITOU_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "QITOU_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "QITOU_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "QITOU_RELEASE_KEY_PASSWORD")
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "app.qitou.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.qitou.bridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 65
        versionName = "0.18.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
