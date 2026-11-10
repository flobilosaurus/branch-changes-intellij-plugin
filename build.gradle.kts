import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        languageVersion = KotlinVersion.KOTLIN_2_0
        apiVersion = KotlinVersion.KOTLIN_2_0
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier(providers.gradleProperty("pluginVerifierVersion"))
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Disable the Gradle plugin default upper bound as well as the descriptor attribute.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            val verificationIdePath = providers.gradleProperty("verificationIdePath")
            if (verificationIdePath.isPresent) {
                local(file(verificationIdePath.get()))
            } else {
                create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3")
                create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.3")
                create(IntelliJPlatformType.IntellijIdea, "2026.2.2")
                create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("verificationIdeVersion"))
            }
        }
    }
}

tasks {
    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    test {
        useJUnit()
    }
}
