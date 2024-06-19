plugins {
    java
}

group = "com.hivemq.helmcharts"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

sourceSets.create("integrationTest")

dependencies {
    // JUnit
    "integrationTestImplementation"(libs.junit.jupiter)

    // Custom Extension
    "integrationTestImplementation"(libs.hivemq.extensionSdk)
    "integrationTestImplementation"(libs.javassist)
    "integrationTestImplementation"(libs.shrinkwrap.api)
    "integrationTestImplementation"(libs.shrinkwrap.impl)

    // Testcontainers
    "integrationTestImplementation"(libs.testcontainers)
    "integrationTestImplementation"(libs.testcontainers.k3s)
    "integrationTestImplementation"(libs.testcontainers.hivemq)
    "integrationTestImplementation"(libs.testcontainers.junitJupiter)
    "integrationTestImplementation"(libs.testcontainers.selenium)

    // Certificates
    "integrationTestImplementation"(libs.bouncycastle.pkix)
    "integrationTestImplementation"(libs.bouncycastle.prov)

    // Testing
    "integrationTestImplementation"(libs.assertj)
    "integrationTestImplementation"(libs.awaitility)
    "integrationTestImplementation"(libs.selenium.remote.driver)
    "integrationTestImplementation"(libs.selenium.java)

    // Misc
    "integrationTestImplementation"(libs.fabric8.kubernetes.client)
    "integrationTestImplementation"(libs.slf4j.api)
    "integrationTestRuntimeOnly"(libs.logback.classic)
    "integrationTestImplementation"(libs.rest.assured)
    "integrationTestImplementation"(libs.hivemq.mqttClient)
    "integrationTestImplementation"(libs.netty.codec.http)
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs integration tests"
    testClassesDirs = sourceSets[name].output.classesDirs
    classpath = sourceSets[name].runtimeClasspath

    if (environment["TEST_PLAN"] != null) {
        val testPlan = environment["TEST_PLAN"].toString()
        if (testPlan == "Other") {
            systemProperty(
                "excludeTags", "Upgrade,Extensions,Services1,Services2,CustomConfig,Services,Platform,PodSecurityContext"
            )
        } else {
            systemProperty(
                "includeTags", testPlan
            )
        }
    }
    useJUnitPlatform {
        if (systemProperties["includeTags"] != null) {
            val includeTags = systemProperties["includeTags"].toString().split(",")
            println("JUnit includeTags: $includeTags")
            includeTags(*includeTags.toTypedArray())
        }
        if (systemProperties["excludeTags"] != null) {
            val excludeTags = systemProperties["excludeTags"].toString().split(",")
            println("JUnit excludeTags: $excludeTags")
            excludeTags(*excludeTags.toTypedArray())
        }
    }
    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
    }
    reports {
        junitXml.isOutputPerTestCase = true
    }
    maxHeapSize = "3g"

    // sets docker images versions for the tests
    systemProperties(
        "hivemq.version" to libs.versions.hivemq.platform.get(),
        "selenium.version" to libs.versions.selenium.container.get(),
        "nginx.version" to libs.versions.nginx.container.get()
    )

    dependsOn(saveDockerImages)  // Platform Operator images

    inputs.files(
        layout.buildDirectory.file("hivemq-dns-init-wait.tar"),
        layout.buildDirectory.file("hivemq-operator.tar"),
        layout.buildDirectory.file("hivemq-k8s.tar"),
        layout.buildDirectory.file("hivemq-platform-operator-init.tar"),
        layout.buildDirectory.file("hivemq-platform-operator.tar"),
        layout.buildDirectory.file("hivemq-platform.tar"),
    )
}

/* ******************** Docker Platform Operator Images ******************** */

val savePlatformOperatorDockerImage by tasks.registering(Exec::class) {
    group = "container"
    description = "Save HiveMQ Platform Operator Docker image"
    dependsOn(gradle.includedBuild("hivemq-platform-operator").task(":quarkusBuild"))
    workingDir(layout.buildDirectory)
    commandLine("docker", "save", "-o", "hivemq-platform-operator.tar", "hivemq/hivemq-platform-operator-test:snapshot")
}

val savePlatformOperatorInitDockerImage by tasks.registering(Exec::class) {
    group = "container"
    description = "Save HiveMQ Platform Operator Init Docker image"
    dependsOn(gradle.includedBuild("hivemq-platform-operator-init").task(":docker"))
    workingDir(layout.buildDirectory)
    commandLine(
        "docker",
        "save",
        "-o",
        "hivemq-platform-operator-init.tar",
        "hivemq/hivemq-platform-operator-init-test:snapshot"
    )
}

val hivemqVersion = libs.versions.hivemq.platform.get()

val savePlatformDockerImage by tasks.registering(Exec::class) {
    group = "container"
    description = "Save HiveMQ Platform Docker image"
    dependsOn(pullPlatformDockerImage)
    workingDir(layout.buildDirectory)
    commandLine("docker", "save", "-o", "hivemq-platform.tar", "docker.io/hivemq/hivemq4:$hivemqVersion")
}

val pullPlatformDockerImage by tasks.registering(Exec::class) {
    commandLine("docker", "pull", "docker.io/hivemq/hivemq4:$hivemqVersion")
}

val saveDockerImages by tasks.registering {
    group = "container"
    description = "Save all Platform Docker images"
    dependsOn(savePlatformOperatorInitDockerImage)
    dependsOn(savePlatformOperatorDockerImage)
    dependsOn(savePlatformDockerImage)
}

/* ******************** update versions ******************** */

val updatePlatformVersion by tasks.registering {
    group = "version"
    val appVersion = project.properties["appVersion"]
    if (appVersion != null) {
        doLast {
            val filesToUpdate = fileTree(projectDir).matching {
                include("**/*.yml")
                include("**/*.yaml")
                include("**/*.json")
                include("**/*.sh")
                include("**/*.toml")
                include("**/*.java")
                // include test hivemq/mqtt-cli image to update, which is part of the hivemq-platform chart
            }.plus(file("../charts/hivemq-platform/templates/tests/test-mqtt-cli.yml"))
            filesToUpdate.forEach { file ->
                val text = file.readText()
                file.writeText(text.replace("""^hivemq-platform = \"(.*)\"$""".toRegex(RegexOption.MULTILINE)) {
                    "hivemq-platform = \"${appVersion}\""
                }.replace("""(?i)(hivemq/hivemq4:)(\d+\.\d+\.\d+(-snapshot)?)$""".toRegex(RegexOption.MULTILINE)) {
                    "${it.groupValues[1]}${appVersion}${it.groupValues[3]}"
                }.replace("""(?i)(hivemq/mqtt-cli:)(\d+\.\d+\.\d+(-snapshot)?)$""".toRegex(RegexOption.MULTILINE)) {
                    "${it.groupValues[1]}${appVersion}${it.groupValues[3]}"
                })
            }
        }
    }
}
