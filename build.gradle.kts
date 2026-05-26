plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "org.example"
version = "0.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("org.json:json:20240303")

    intellijPlatform {
        create("IC", "2025.1.4.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            <ul>
              <li>0.2.0 — Settings UI (port, model, max turns); extract_method and extract_variable tools;
                  read_file and find_usages tools; Kotlin creation tools; project context injection.</li>
              <li>0.1.0 — AST-safe Rename at Caret action; localhost agent tool surface.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    runIde {
        // Auto-open a benchmark project when the sandbox IDE launches.
        // Override with:
        //   .\gradlew.bat runIde -PbenchmarkProject=spring-petclinic
        // or:
        //   BENCHMARK_PROJECT=spring-petclinic ./gradlew runIde
        val benchmarkProject =
            providers.gradleProperty("benchmarkProject").orNull
                ?: providers.environmentVariable("BENCHMARK_PROJECT").orNull
                ?: "sample-java-project"
        val projectPath = file("benchmarks/projects/$benchmarkProject").absolutePath
        args(projectPath)
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf("-Didea.project.path=$projectPath")
        })
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
