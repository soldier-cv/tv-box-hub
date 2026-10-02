plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the artefact identity, so the APK on disk is
// always self-describing rather than an anonymous "app-release.apk".
val appName = "BoxHub"
val appVersionName = "1.0.0"
val appVersionCode = 1

android {
    namespace = "com.boxhub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.boxhub"
        minSdk = 24          // Android 7.0 — covers 斐讯 N1 Phoenix
        targetSdk = 28       // stay pre-scoped-storage / pre-cleartext-default
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // BoxHub is sideloaded onto a fixed TV box, never published to Google Play.
    // targetSdk is deliberately pinned to 28 so the box's Android 7 keeps
    // legacy storage + permissive cleartext behaviour, which trips Play's
    // ExpiredTargetSdkVersion lint gate. That gate is irrelevant here.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Names the produced APK `BoxHub-<version>-<buildType>.apk`.
 *
 * Why a post-package rename instead of the obvious `outputFileName`:
 *   - AGP 8.13's new `androidComponents` API exposes no output-file-name setter
 *     on VariantOutput (checked against gradle-api-8.13.2 — only versionCode,
 *     versionName and enabled are present).
 *   - The legacy `applicationVariants` route does have
 *     BaseVariantOutputImpl.setOutputFileName(), but AGP overwrites the name
 *     during configuration, so the call has no effect and the artefact stays
 *     "app-release.apk". Verified by instrumenting the callback: it never fires.
 *
 * So the rename happens after packaging, and it *fails the build* rather than
 * silently leaving an unrenamed APK behind. The move is also wired as a real
 * task output, so Gradle does not consider the result missing on the next run.
 */
fun registerRenamedApk(variantName: String, buildType: String): TaskProvider<Task> {
    val label = "$appName-$appVersionName-$buildType"
    val rename = tasks.register("renameApk$variantName") {
        group = "build"
        description = "Renames the $buildType APK to $label.apk"
        val srcDir = layout.buildDirectory.dir("outputs/apk/$buildType")
        val src = srcDir.map { it.file("app-$buildType.apk") }
        val dst = srcDir.map { it.file("$label.apk") }
        val srcFile = src
        val dstFile = dst
        outputs.file(dst)
        doLast {
            val from = srcFile.get().asFile
            val to = dstFile.get().asFile
            check(from.isFile) { "packaged APK missing: $from" }
            if (from != to) {
                // Clear a stale artefact from an earlier version first.
                if (to.exists()) to.delete()
                check(from.renameTo(to)) { "could not rename $from -> $to" }
            }
        }
    }
    rename.configure {
        // Run after the packaging task that produces app-<type>.apk, and before
        // assemble finishes, so a plain `gradlew assembleRelease` always leaves
        // the correctly-named artefact on disk.
        dependsOn("package$variantName")
    }
    return rename
}

androidComponents {
    onVariants { variant ->
        val buildType = if (variant.name == "release") "release" else "debug"
        // Registered in afterEvaluate because onVariants fires before AGP has
        // created the assemble<Variant> lifecycle task.
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        afterEvaluate {
            val rename = registerRenamedApk(variantName, buildType)
            // Wire it here, not inside registerRenamedApk: an earlier attempt
            // attached the dependency before the assemble task existed, which
            // silently produced no dependency at all (verified with --dry-run:
            // assembleRelease did not list renameApkRelease).
            tasks.named("assemble$variantName").configure {
                // dependsOn alone is enough: package<Variant> must finish first,
                // which registerRenamedApk already enforces.
                dependsOn(rename)
            }
        }
    }
}

// No third-party dependencies on purpose: everything (HTTP server, WebSocket,
// FileProvider, JSON) is implemented against the platform framework so the APK
// stays tiny and behaves predictably on API 24-25 TV box firmware.
dependencies {
}