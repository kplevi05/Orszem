import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "hu.orszem"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Argon2id password hashing (Spring Security Crypto pulls this transitively,
    // but the BouncyCastle provider is needed explicitly).
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // Simple in-memory rate limiting for the public report endpoint.
    implementation("com.bucket4j:bucket4j_jdk17-core:8.14.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // Newer docker-java: the 3.4.x bundled with Spring Boot 3.4 cannot talk to
    // recent Docker Desktop socket proxies (unversioned /info -> HTTP 400).
    testImplementation("com.github.docker-java:docker-java-core:3.5.3")
    testImplementation("com.github.docker-java:docker-java-transport-httpclient5:3.5.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Forward Docker / Testcontainers configuration from the environment so the
    // build works both on a developer machine and in CI without code changes.
    listOf(
        "DOCKER_HOST",
        "DOCKER_API_VERSION",
        "TESTCONTAINERS_HOST_OVERRIDE",
        "TESTCONTAINERS_RYUK_DISABLED",
        "TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
    ).forEach { key -> System.getenv(key)?.let { systemProperty(key, it) } }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("orszem-api.jar")
}
