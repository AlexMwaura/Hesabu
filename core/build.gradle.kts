// Pure Kotlin rules shared by the hub, the server and (later) the Android till. No Spring here.
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.6"))
    api("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.6"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
