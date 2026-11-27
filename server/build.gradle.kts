plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":fake-daraja"))
    testImplementation(project(":hub"))
    testImplementation(project(":fake-vscu"))
    // Postgres for tests without Docker.
    testImplementation("io.zonky.test:embedded-postgres:2.2.3")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.4.0"))
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8")
    testImplementation("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64")
}
