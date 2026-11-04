plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "app.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vault.public"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        buildConfigField("String", "SERVER_URL", "\"\"")
        buildConfigField("boolean", "SERVER_CONFIGURABLE", "true")
        buildConfigField("String", "DEFAULT_FOLDERS", "\"Photos,Videos,Documents\"")
    }

    signingConfigs {
        create("release") {
            val passwordFile = File(System.getProperty("user.home"), ".vault-release.pass")
            val keystoreFile = File(System.getProperty("user.home"), ".vault-release.jks")
            if (passwordFile.exists() && keystoreFile.exists()) {
                val password = passwordFile.readText().trim()
                storeFile = keystoreFile
                storePassword = password
                keyAlias = "vault"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "SERVER_URL", "\"http://127.0.0.1:18080\"")
        }
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            buildConfigField("String", "SERVER_URL", "\"http://127.0.0.1:18080\"")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
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
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}
