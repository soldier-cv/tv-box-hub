plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// The HTTP/WebSocket and filesystem layers are deliberately written against
// the plain JDK, so they can be exercised on the desktop JVM instead of only
// on the TV box.
sourceSets["main"].kotlin.srcDir("../app/src/main/java/com/boxhub/http")
sourceSets["main"].kotlin.srcDir("../app/src/main/java/com/boxhub/fs")

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("boxhubtest.ServerTestKt")
}

/**
 * Headless dashboard behaviour test. Loads the real assets/index.html into
 * jsdom and drives it the way a finger would. Skips cleanly when jsdom has not
 * been installed (`npm install` in this directory) so a plain JVM-only build
 * never breaks on a machine without node.
 */
val uitest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the jsdom dashboard behaviour test (requires npm install in servertest/)"
    workingDir = projectDir
    isIgnoreExitValue = false

    val jsdomPresent = file("node_modules/jsdom").exists()
    doFirst {
        if (!jsdomPresent) {
            logger.lifecycle("uitest skipped: run 'npm install' in servertest/ first")
        }
    }
    onlyIf { jsdomPresent }
    commandLine("node", "uitest.js")
}