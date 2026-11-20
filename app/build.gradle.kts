plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.voiceclock"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.example.voiceclock"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.0.0"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json 在单元测试（JVM）中无实现，测试环境需要真实实现
    testImplementation("org.json:json:20240303")
}
