import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support; the standalone
    // org.jetbrains.kotlin.android plugin must not be applied alongside it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ventouxlabs.bascule"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ventouxlabs.bascule"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing is configured from a properties file that lives OUTSIDE
    // the repository — `~/.config/bascule/keystore.properties` on a developer
    // machine, or the path in $KEYSTORE_PROPERTIES (CI materialises one from
    // secrets). Absent, the release build is left unsigned rather than failing,
    // so a fork or a CI run without secrets still proves the build compiles and
    // R8 succeeds. The keystore itself is never committed: lose it and the app
    // can never be updated in place again, so back it up somewhere durable.
    val keystoreProperties = releaseKeystoreProperties()
    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProperties != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += "GradleDependency"
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room schema export from the first commit so migrations are diffable
// (00-design.md §3.1, §8.12).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    // Robolectric-based JVM tests for Android-framework-coupled classes
    // (BroadcastReceiver/Service/CoroutineWorker) — see
    // .claude/PRPs/plans/scale-admin-testing-completeness.plan.md Task 2.
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.work.testing)
}

/**
 * The first readable keystore.properties among: `$KEYSTORE_PROPERTIES`,
 * `~/.config/bascule/keystore.properties`, and `keystore.properties` at the
 * repository root (gitignored). Null when none exists.
 */
fun releaseKeystoreProperties(): Properties? {
    val candidates = listOfNotNull(
        // An env var that is *set* is a statement of intent: a dangling path
        // must fail loudly rather than quietly produce an unsigned APK that
        // uploads under a green check.
        System.getenv("KEYSTORE_PROPERTIES")?.let { path ->
            File(path).also { require(it.isFile) { "KEYSTORE_PROPERTIES is set but $path is not a file" } }
        },
        File(System.getProperty("user.home"), ".config/bascule/keystore.properties"),
        rootProject.file("keystore.properties"),
    )
    val found = candidates.firstOrNull { it.isFile } ?: return null
    return Properties().apply { found.inputStream().use(::load) }
}
