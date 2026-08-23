plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Firma release: legge keystore/release.properties (fuori dal git, vedi .gitignore).
// Se il file non c'è, assembleRelease produce un APK unsigned.
// NB: niente java.util.Properties qui — nel DSL "java" è un accessor e va in conflitto.
val releaseProps: Map<String, String> = runCatching {
    val f = rootProject.file("keystore/release.properties")
    if (!f.exists()) emptyMap()
    else f.readLines()
        .filter { it.contains('=') && !it.trimStart().startsWith('#') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
}.getOrDefault(emptyMap())

android {
    namespace = "com.adgent.trader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adgent.trader"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.5"
    }

    signingConfigs {
        create("release") {
            if (releaseProps.isNotEmpty()) {
                storeFile = file(releaseProps.getValue("storeFile"))
                storePassword = releaseProps.getValue("storePassword")
                keyAlias = releaseProps.getValue("keyAlias")
                keyPassword = releaseProps.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    // Dati: REST + WS Binance (endpoint pubblici, nessuna chiave), cache locale
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Widget home screen
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Blocco app con biometria/PIN del dispositivo
    implementation(libs.androidx.biometric)
}
