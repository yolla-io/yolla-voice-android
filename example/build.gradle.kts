import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The demo backend's token is not in the repository: the build takes it from YOLLA_VOICE_DEMO_TOKEN, the environment
// variable (for example a secret variable of a build server) or the same key in the git-ignored local.properties. Without it the
// app is built with a placeholder and says what to set instead of asking the backend.
val demoTokenPlaceholder = "YOUR_DEMO_TOKEN"
val demoToken: String = run {
    val fromEnvironment = providers.environmentVariable("YOLLA_VOICE_DEMO_TOKEN").orNull?.trim().orEmpty()
    val local = rootProject.file("local.properties")
    val fromLocal = if (!local.isFile) {
        ""
    } else {
        Properties().apply { local.inputStream().use { load(it) } }
            .getProperty("YOLLA_VOICE_DEMO_TOKEN")?.trim().orEmpty()
    }
    val token = fromEnvironment.ifEmpty { fromLocal }.ifEmpty { demoTokenPlaceholder }
    require(token.none { it.isWhitespace() || it == '"' || it == '\\' || it == '$' }) {
        "YOLLA_VOICE_DEMO_TOKEN has whitespace, a quote, a backslash or \"$\" in it: it cannot be a BuildConfig string"
    }
    token
}

android {
    namespace = "com.yollacalls.android.voice.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yollacalls.android.voice.example"
        minSdk = 26
        targetSdk = 36
        // A build server may pass `-PversionCode=<build number>` so that every build installs over the previous one.
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "YOLLA_VOICE_DEMO_TOKEN", "\"$demoToken\"")
        // The live test places real calls through the app: it runs only when asked for with `-Pyolla.liveCalls=true`
        // AND `YOLLA_TEST_NUMBER` is set. A plain `connectedDebugAndroidTest` skips it even with the variable set.
        // The test also skips itself when the app was built without YOLLA_VOICE_DEMO_TOKEN.
        val liveCalls = providers.gradleProperty("yolla.liveCalls").orNull == "true"
        val testNumber = System.getenv("YOLLA_TEST_NUMBER")
        if (liveCalls && !testNumber.isNullOrEmpty()) {
            testInstrumentationRunnerArguments["yollaLiveNumber"] = testNumber
        }
    }

    signingConfigs {
        // A debug key kept in the repository (the standard Android debug password), so that builds from any
        // machine carry the same signature and install over each other. It protects nothing:
        // a real app signs with its own key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug signing only: this is a demo, not a store build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Java 11 bytecode, as the SDK modules; the unit tests run on the toolchain's Java (JUnit 6 needs 17+).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Fakes shared by the JVM and the emulator tests.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation("io.yolla:yolla-voice-android:0.1.0")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.core.telecom)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Prints the app's version name, for build scripts.
tasks.register("printVersionName") {
    val versionName = android.defaultConfig.versionName
    doLast { println(versionName) }
}
