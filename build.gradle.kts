import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("org.springframework.boot") version "3.2.5"
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.spring") version "1.9.22"
    id("com.avast.gradle.docker-compose") version "0.17.18"

    kotlin("plugin.jpa") version "2.2.0"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21 // или другой, в зависимости от нужной версии

repositories {
    mavenCentral()
}

dockerCompose {
    useComposeFiles.set(listOf("docker-compose.yml"))
    startedServices.set(listOf("db"))   // если в compose только Postgres — можно не указывать
    // stopContainers.set(false)        // опционально: не останавливать БД после остановки приложения
}

dockerCompose.isRequiredBy(tasks.named("bootRun").get())

dependencies {

    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.9"))
    // Spring Boot Web для REST API
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Jackson Kotlin support
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Telegram Bot API
    implementation("com.github.pengrad:java-telegram-bot-api:9.5.0")

    // Kotlin стандартная библиотека
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // DB
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-core:10.17.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:10.20.1")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.vladmihalcea:hibernate-types-60:2.21.1") // для jsonb

    // Google authentication
    implementation("com.google.api-client:google-api-client:2.6.0")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20250106-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.23.0")

    // Spring Boot Test
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage") // не нужен старый JUnit 4
    }
}


tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<BootRun>("bootRun") {
    jvmArgs = listOf("-Duser.timezone=UTC")
}