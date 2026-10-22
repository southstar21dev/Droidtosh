import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// docs/RELEASING.md: scripts/version.sh turns the semantic version (the VERSION file, or the release
// tag that CI passes in DROIDTOSH_VERSION) into the versionName and the versionCode.
val versionInfo = providers.exec {
    commandLine(rootProject.file("../scripts/version.sh").path, System.getenv("DROIDTOSH_VERSION") ?: "")
}.standardOutput.asText.get().trim().split(" ")
val appVersion = versionInfo[0]
val buildNumber = versionInfo[1].toInt()
val roborazziVersion = "1.46.1"

/** The GitHub repo whose releases the in-app updater reads; it must be public. */
val updateRepo = "southstar21dev/Droidtosh"

// The release key, shared by release builds and (when present) local debug builds so either can
// replace the other: android/keystore.properties locally, ANDROID_KEYSTORE_* env vars in CI.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_FILE") ?: keystoreProperties.getProperty("storeFile")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
val hasReleaseKey = releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
    id("com.google.gms.google-services") apply false
    id("io.github.takahirom.roborazzi")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "dev.southstar21.droidtosh"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.southstar21.droidtosh"
        minSdk = 33
        targetSdk = 35
        versionCode = buildNumber
        versionName = appVersion
        buildConfigField("boolean", "FCM_ENABLED", file("google-services.json").exists().toString())
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            // Not shrunk: the APK is sideloaded, and R8 rules for protobuf-lite, ZXing and Firebase
            // aren't worth the risk for the size saved.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // The Roborazzi screenshot tests render whole screens with native graphics.
                test.maxHeapSize = "3g"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material 3 Expressive (MaterialExpressiveTheme, MaterialShapes, LoadingIndicator, the
    // expressive motion scheme) only ships in the 1.5 alphas. 1.5.0-alpha15 is the newest that still
    // builds on Compose 1.9, compileSdk 35 and Kotlin 2.1.
    implementation("androidx.compose.material3:material3:1.5.0-alpha15")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:$roborazziVersion")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:$roborazziVersion")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:$roborazziVersion")
}

// The end-to-end harness (e2e/) runs dev.southstar21.droidtosh.e2e.PhonePeer on a plain JVM with the unit-test
// classpath (compiled app + test classes, protobuf, coroutines, the mockable android.jar). This
// writes that classpath to a file so the harness can launch the peer with `java -cp` directly.
tasks.register("e2ePhoneClasspath") {
    val testTask = tasks.named<Test>("testDebugUnitTest")
    val output = layout.buildDirectory.file("e2e/phone-classpath.txt")
    dependsOn(testTask.map { it.classpath })
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        output.get().asFile.writeText(testTask.get().classpath.asPath)
    }
}
