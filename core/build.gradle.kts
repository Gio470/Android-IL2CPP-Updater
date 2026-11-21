plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktfmt)
    `java-library`
}

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    api(libs.capstone)
    // Reads Il2CppDumper metadata, which the modern engine can use when it is offered one.
    implementation(libs.gson)
    implementation(libs.slf4j)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useKotlinTest("2.0.0")
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Android's dexer does not accept Java 21 class files, and this module is shared
// with the Android app, so it targets 17 while still being built by a JDK 21.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

ktfmt {
    kotlinLangStyle()
}
