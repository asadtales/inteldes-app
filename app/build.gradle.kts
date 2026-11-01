plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/** Output of a git command run in the repo, or "" when git is not there (a source archive, for instance). */
fun git(vararg args: String): String = try {
    ProcessBuilder("git", *args).directory(rootDir).redirectErrorStream(true).start().let { p ->
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
    }
} catch (e: Exception) { "" }

// What the "Tentang" screen tells the user about THIS build, so a build can always be traced to its source.
val buildCommit: String = git("rev-parse", "--short=8", "HEAD").ifBlank { "tidak diketahui" }
// True when tracked files differ from that commit: then the build is NOT exactly what the commit contains.
val buildDirty: Boolean = try { ProcessBuilder("git", "diff", "--quiet", "HEAD").directory(rootDir).start().waitFor() != 0 } catch (e: Exception) { false }

android {
    namespace = "com.inteldes.app"
    compileSdk = 34

    // The exported Room schemas (app/schemas) double as test assets for the migration test.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    defaultConfig {
        applicationId = "com.inteldes.app"
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 4
        versionName = "2.5.0"

        // The IntelDes web/server this app can sign in to (login + QR pairing). HTTPS only.
        buildConfigField("String", "SERVER_URL", "\"https://inteldes.at-in.site\"")
        buildConfigField("String", "BUILD_COMMIT", "\"$buildCommit\"")
        buildConfigField("boolean", "BUILD_DIRTY", buildDirty.toString())
        buildConfigField("long", "BUILD_TIME_MS", "${System.currentTimeMillis()}L")

        // arm64-v8a covers virtually every real device sold since ~2017 (incl. the
        // Galaxy M51 this app is tested on); x86_64 is only here so the on-device
        // Whisper path is testable on the Android Studio emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Image loading for photo thumbnails
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Networking — plain OkHttp + Gson (the AI/transcription APIs' JSON shapes are
    // simple enough that hand-built Gson request/response models are clearer than
    // a Retrofit service interface layer here).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Security (encrypted prefs for API keys)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google sign-in / authorisation for Drive sync (each user connects their own account)
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // QR scanning (runs inside Google Play services: no CAMERA permission needed) and biometric/PIN confirmation
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // DataStore (lightweight prefs for non-secret settings)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
