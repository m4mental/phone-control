import java.util.Properties

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

            val localProperties = Properties().apply {
                val localPropertiesFile = rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    load(localPropertiesFile.inputStream())
                }
            }

            fun getProp(name: String, envName: String): String? {
                return System.getenv(envName)
                    ?: (project.findProperty(name) as? String)
                    ?: localProperties.getProperty(name)
            }

            val storePass = getProp("RELEASE_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD") ?: "PhoneControlSecuredRotated2026Key!"
            val keyPass = getProp("RELEASE_KEY_PASSWORD", "KEY_PASSWORD") ?: "PhoneControlSecuredRotated2026Key!"
            val alias = getProp("RELEASE_KEY_ALIAS", "KEY_ALIAS") ?: "phonecontrol"

            if (keystoreFile.exists() && keystoreFile.length() > 0L) {
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
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            val releaseKeystore = rootProject.file("keystore/phonecontrol-release.jks")
            if (releaseKeystore.exists() && releaseKeystore.length() > 0L) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}


dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
