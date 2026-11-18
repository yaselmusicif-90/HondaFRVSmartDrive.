
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hondafrv.smartdrive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hondafrv.smartdrive"
        minSdk = 23
        targetSdk = 28
        versionCode = 4
        versionName = "1.4-dashboard-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
