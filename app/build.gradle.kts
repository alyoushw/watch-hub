plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.alyoushw.watchhub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alyoushw.watchhub"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The CI run number is the build number ("build-84"): the app compares it with the newest
        // release to offer updates. Local builds count as 0 (always older).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + build
        versionName = "0.23.$build"
        buildConfigField("int", "BUILD_NUMBER", "$build")
        val animeifyToken = System.getenv("WATCHHUB_ANIMEIFY_TOKEN").orEmpty()
        buildConfigField("String", "ANIMEIFY_TOKEN", "\"${animeifyToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    // A fixed release key (from CI secrets) lets new APKs install over old ones and keep the data.
    // Without the secrets, builds fall back to the debug key.
    val releaseKeystore = System.getenv("WATCHHUB_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        // Shared debug key (standard public "android" passwords) checked into the repo, so every
        // CI build is signed the same way and installs over the previous one without uninstalling.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("WATCHHUB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WATCHHUB_KEY_ALIAS")
                keyPassword = System.getenv("WATCHHUB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        resources {
            // Duplicate metadata files shipped by the Anthropic SDK's dependencies (Jackson, OpenTelemetry).
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.anthropic.java)
    implementation(libs.mlkit.translate)
    implementation(libs.androidx.work)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.okhttp)
    // FFmpeg audio decoders (Dolby AC3 / E-AC3, DTS, MP2…) for devices without them, like car screens.
    implementation(libs.media3.ffmpeg)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    // Watch Hub account: Google sign-in and sync (Firebase, configured at runtime from the web app).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
