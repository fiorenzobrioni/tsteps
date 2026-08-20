plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.callbackdev.tsteps"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.callbackdev.tsteps"
        // 33 (Android 13): same floor as tweather — system per-app language picker
        // for the IT/EN l10n, single runtime path for POST_NOTIFICATIONS, themed
        // icons, full java.time. tsteps adds a reason of its own: modern step
        // sensors and the ACTIVITY_RECOGNITION runtime-permission single path.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Shared debug keystore (committed) so debug builds from any machine
        // or CI can update an existing install without uninstalling first.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "tsteps-debug"
            keyPassword = "android"
        }

        // Real release key. The keystore lives OUTSIDE the repo; the four properties
        // come from ~/.gradle/gradle.properties locally and from ORG_GRADLE_PROJECT_*
        // env vars (GitHub Secrets) in the release workflow. Only created when fully
        // configured, so a clean checkout still builds.
        val releaseStore = findProperty("TSTEPS_KEYSTORE") as String?
        val releaseStorePassword = findProperty("TSTEPS_KEYSTORE_PASSWORD") as String?
        val releaseKeyAlias = findProperty("TSTEPS_KEY_ALIAS") as String?
        val releaseKeyPassword = findProperty("TSTEPS_KEY_PASSWORD") as String?
        if (!releaseStore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Dev builds are a different app id, so they install side-by-side with
            // the release-signed app instead of being uninstallable over it (same
            // id + different signature = no install at all). Series decision
            // (Aug 2026); snake had it from its skeleton. A debug res overlay
            // relabels the launcher icon "tsteps (dev)" to tell the two apart.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The real key wins whenever it is configured. Otherwise the debug-key
            // opt-in stays: with the flag, per-push CI signs the minified build with
            // the shared debug key so it can actually be installed and smoke-tested
            // (R8 breakage only shows up in a release build). Off by default so an
            // unconfigured checkout can never produce an installable release by accident.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
                    .takeIf { project.hasProperty("signReleaseWithDebugKey") }
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

    testOptions {
        unitTests {
            // Compose UI tests run on the JVM via Robolectric (no emulator needed).
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.health.connect)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
