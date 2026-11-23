plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin") version "2.3.12"
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("dev.nafs.ApplicationKt")
}

dependencies {
    implementation(project(":shared"))
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.12")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.18.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.18.0")
    implementation("ch.qos.logback:logback-classic:1.5.8")
}
