pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // freeRASP (Talsec) Gradle plugin
        maven { url = uri("https://europe-west3-maven.pkg.dev/talsec-artifact-repository/plugin") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // freeRASP (Talsec): must stay the last repositories
        maven { url = uri("https://europe-west3-maven.pkg.dev/talsec-artifact-repository/common") }
        maven { url = uri("https://europe-west3-maven.pkg.dev/talsec-artifact-repository/freerasp") }
    }
}
rootProject.name = "AvidViewer"
include(":app")
