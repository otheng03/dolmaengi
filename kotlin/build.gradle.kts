plugins {
    kotlin("jvm") version "1.9.25"
    id("application")
    jacoco
}

group = "dolmeangi"
version = "0.0.1-SNAPSHOT"
description = "A toy distributed transactional key-value store"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Core
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.7.3")

    // ktor network
    implementation("io.ktor:ktor-network:2.3.7")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // Testing
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    // Default to KV store, but allow running Sequencer via command-line args
    val appArgs = providers.gradleProperty("appArgs").getOrElse("")
    mainClass.set(
        if (appArgs.contains("sequencer")) {
            "dolmeangi.kotlin.sequencer.SequencerMainKt"
        } else {
            "dolmeangi.kotlin.MainKt"
        }
    )
}

// Custom task to run the Sequencer
tasks.register<JavaExec>("runSequencer") {
    group = "application"
    description = "Run the Sequencer server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dolmeangi.kotlin.sequencer.SequencerMainKt")
    standardInput = System.`in`
}

// Custom task to run the KV Store
tasks.register<JavaExec>("runKVStore") {
    group = "application"
    description = "Run the KV Store server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dolmeangi.kotlin.kvstore.KVStoreMainKt")
    standardInput = System.`in`
}

tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport) // Generate coverage report after tests
}

tasks.jacocoTestReport {
    dependsOn(tasks.test) // Ensure tests run before generating report

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    // Print coverage summary to console
    doLast {
        val reportFile = file("${layout.buildDirectory.get()}/reports/jacoco/test/html/index.html")
        if (reportFile.exists()) {
            println("\n" + "=".repeat(80))
            println("Code Coverage Report Generated:")
            println("HTML Report: file://${reportFile.absolutePath}")
            println("=".repeat(80) + "\n")
        }
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.0".toBigDecimal() // Set minimum coverage threshold
            }
        }
    }
}
