import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "nl.stokpop"
version = "1.0.0"

application {
    mainClass.set("nl.stokpop.NetstatInfoKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    named<ShadowJar>("shadowJar") {
        archiveBaseName.set("netstat-info")
        mergeServiceFiles()
    }
}

