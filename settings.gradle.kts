rootProject.name = "hesabu"

// Planned next: "hub", "server", "fake-vscu", "fake-daraja", "e2e" (see TODO.md)
include("core", "fake-vscu", "hub", "fake-daraja", "server")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
