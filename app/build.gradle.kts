plugins {
    id("com.android.application")
}

android {
    namespace = "com.talkreel.ar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.talkreel.ar"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.11.0"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../manual-apk/AndroidManifest.xml")
            java.setSrcDirs(listOf("../manual-apk/src"))
            res.setSrcDirs(listOf("../manual-apk/res"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.google.ar:core:1.57.0")
    implementation("androidx.core:core:1.17.0")
}
