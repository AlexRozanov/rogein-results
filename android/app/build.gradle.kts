plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ru.rogein.chip"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.rogein.chip"
        minSdk = 26
        targetSdk = 35
        // Бамп при каждом релизе для команды: versionCode +1, versionName по semver.
        // CI может переопределить: -PversionCode=3 -PversionName=0.2.1
        // Синхронизируйте site/android-app/version.json (см. android/README.md).
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 2
        versionName = (findProperty("versionName") as String?) ?: "0.2.0"
    }

    signingConfigs {
        // Общий ключ команды (CI + локально). Не путать с личным ~/.android/debug.keystore —
        // иначе APK с GitLab не встанет поверх сборки из Studio.
        create("team") {
            storeFile = rootProject.file("keystore/rogein-team.jks")
            storePassword = "rogein-team"
            keyAlias = "rogein"
            keyPassword = "rogein-team"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("team")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("team")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":chip-parse"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
