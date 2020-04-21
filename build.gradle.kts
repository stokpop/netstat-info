import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.50"
    application
    id("com.github.johnrengelman.shadow") version "5.0.0"
}

group = "nl.stokpop"
version = "1.0-SNAPSHOT"

application {
    mainClassName = "nl.stokpop.NetstatInfoKt"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks {
    named<ShadowJar>("shadowJar") {
        archiveBaseName.set("netstat-info")
        mergeServiceFiles()
    }
}

