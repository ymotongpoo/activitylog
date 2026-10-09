import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The release version comes from -PactivitylogVersion=x.y.z (see
// distribute.sh). versionCode must grow with every release so that an
// update installs over the previous one: x.y.z becomes x*10000 + y*100 + z.
val appVersion = (findProperty("activitylogVersion") as String?) ?: "0.0.0"
val appVersionCode = appVersion.split(".", "-").take(3)
    .map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> maxOf(1, major * 10000 + minor * 100 + patch) }

// Release signing is configured through the environment so that the key
// never lives in the repository. Without it the release build is unsigned.
val releaseKeystore: String? = System.getenv("ACTIVITYLOG_KEYSTORE")

android {
    namespace = "net.ymotongpoo.activitylog"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.ymotongpoo.activitylog"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ACTIVITYLOG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ACTIVITYLOG_KEY_ALIAS") ?: "activitylog"
                keyPassword = System.getenv("ACTIVITYLOG_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    // android.jar only ships stubs of org.json; use the real implementation in JVM tests.
    testImplementation(libs.org.json)
}
