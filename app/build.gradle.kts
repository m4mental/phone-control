plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.phonecontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.phonecontrol"
        minSdk = 32
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore/phonecontrol-release.jks")
            val storePass = System.getenv("KEYSTORE_PASSWORD") ?: "PhoneControl2026Key"
            val keyPass = System.getenv("KEY_PASSWORD") ?: "PhoneControl2026Key"
            val alias = System.getenv("KEY_ALIAS") ?: "phonecontrol"

            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                initWith(signingConfigs.getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            val releaseKeystore = rootProject.file("keystore/phonecontrol-release.jks")
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.biometric)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
