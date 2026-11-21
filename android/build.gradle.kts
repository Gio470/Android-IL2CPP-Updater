plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktfmt)
}

android {
    namespace = "org.updater.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.updater.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = project.version.toString()
        // The tool disassembles AArch64 only, so any other ABI would ship a
        // Capstone build that could never be asked to do anything.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    androidComponents {
        // The screenshot tests launch a ComponentActivity, and only the debug-only
        // ui-test-manifest declares one, so the release copy of them cannot run.
        beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")

    packaging {
        resources {
            // The Capstone jar carries desktop builds for every platform. The one
            // this app uses is the NDK build in jniLibs; the rest are dead weight
            // and one of them is the glibc aarch64 build that cannot load here.
            excludes +=
                setOf(
                    "linux-*/**",
                    "darwin/**",
                    "darwin-*/**",
                    "win32-*/**",
                    "natives/**",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/*.kotlin_module",
                )
        }
    }
}

dependencies {
    implementation(project(":core")) { exclude(group = "net.java.dev.jna", module = "jna") }
    // JNA has to come as an AAR: the jar carries desktop natives, the AAR carries
    // the Android ones per ABI where the loader can find them.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Renders the screen on the JVM, so the layout can be looked at without a device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

ktfmt {
    kotlinLangStyle()
}
