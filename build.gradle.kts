plugins {
    id("org.jetbrains.kotlin.jvm") version "1.4.31"
    id("com.github.johnrengelman.shadow") version "7.0.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.opendc:opendc-compute-simulator:2.0")
    implementation("org.opendc:opendc-simulator-core:2.0")
    implementation("org.opendc:opendc-workflow-service:2.0")
    implementation("org.opendc:opendc-format:2.0")
    implementation("org.opendc:opendc-telemetry-sdk:2.0")
    implementation("org.opendc:opendc-harness-api:2.0")

    runtimeOnly("org.opendc:opendc-harness-junit5:2.0")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:2.14.1")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.shadowJar {
    dependencies {
        // Do not include the JUnit 5 runner in the final shadow jar, since it is only necessary for development
        // inside IDE
        exclude("org.opendc:opendc-harness-junit5")
    }
}

tasks.register<Test>("experiment") {
    // Ensure JUnit Platform is used for resolving tests
    useJUnitPlatform()

    description = "Runs OpenDC experiments"
    group = "application"

    testClassesDirs = sourceSets["main"].output.classesDirs
    classpath = sourceSets["main"].runtimeClasspath
}