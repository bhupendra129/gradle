// tag::android[]
plugins {
    id("com.android.application") version "7.3.1" apply false
// end::android[]
    kotlin("android") version "2.1.0" apply false
// tag::android[]
}
// end::android[]

// tag::android-buildscript[]
buildscript {
    repositories {
        google()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.3.1")
    }
}
// end::android-buildscript[]
