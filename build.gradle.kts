// AGP 9 provides built-in Kotlin. Pin its Kotlin Gradle Plugin runtime explicitly.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    }
}

plugins {
    id("com.android.application") version "9.1.1" apply false
}
