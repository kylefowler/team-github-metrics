plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    application
}

dependencies {
}

group = "org.allkapps.metrics"
version = "1.0.0"
application {
    mainClass.set("org.allkapps.metrics.ApplicationKt")
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.serialization)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.clikt)
    implementation(libs.picnic)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.multiplatform.settings)
    implementation(libs.multiplatform.settings.noarg)
    implementation(libs.openai.client)
}