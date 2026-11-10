import org.gradle.api.initialization.resolve.RepositoriesMode
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "branch-changes-intellij-plugin"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.20.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
