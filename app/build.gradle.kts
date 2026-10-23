plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.cascade.alarm"
    // compileSdk only controls which APIs are visible at compile time (current AndroidX requires 37).
    // Runtime behavior is governed by targetSdk, which stays at 36 until we deliberately migrate.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cascade.alarm"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Lets engine logic that logs via android.util.Log run in plain JVM tests.
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                // Robolectric's SDK 36 sandbox needs Java 21 and reaches into FileDescriptor internals.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

/**
 * Generates the theme colour table from design/assets/themes.json (DESIGN.md §2.1), so no hex
 * value is ever transcribed by hand. Fails the build if a theme is missing a token.
 */
abstract class GenerateThemesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val themesJson: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(themesJson.get().asFile) as Map<String, Any?>
        val tokens = listOf(
            "ground", "groundDeep", "surface", "surfaceHigh", "surfaceMuted", "line",
            "textPrimary", "textSecondary", "textQuiet", "onRamp5",
        )
        val hex = Regex("^#[0-9A-Fa-f]{6}$")
        fun color(value: Any?, where: String): String {
            val s = value as? String ?: error("themes.json: $where is missing")
            require(hex.matches(s)) { "themes.json: $where is not #RRGGBB: $s" }
            return "Color(0xFF${s.substring(1).uppercase()})"
        }
        fun str(value: Any?) = if (value == null) "null" else
            "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        fun status(map: Any?, where: String): String {
            val m = map as? Map<*, *> ?: error("themes.json: $where is missing")
            return "StatusColors(pass = ${color(m["pass"], "$where.pass")}, warn = ${color(m["warn"], "$where.warn")}, " +
                "fail = ${color(m["fail"], "$where.fail")})"
        }

        val themes = root["themes"] as? Map<*, *> ?: error("themes.json: no themes")
        val free = (root["free"] as List<*>).map { it.toString() }.toSet()
        val out = StringBuilder()
        out.appendLine("// Generated from design/assets/themes.json by :app:generateThemes. Do not edit.")
        out.appendLine("package com.cascade.alarm.ui.theme")
        out.appendLine()
        out.appendLine("import androidx.compose.ui.graphics.Color")
        out.appendLine()
        out.appendLine("internal object GeneratedThemes {")
        out.appendLine("    const val DEFAULT = ${str(root["default"])}")
        out.appendLine("    val status = ${status(root["status"], "status")}")
        out.appendLine("    val statusLight = ${status(root["statusLightOverride"], "statusLightOverride")}")
        out.appendLine("    val families = listOf(")
        (root["families"] as Map<*, *>).forEach { (id, family) ->
            val f = family as Map<*, *>
            val ids = (f["themes"] as List<*>).joinToString { str(it) }
            out.appendLine("        ThemeFamily(${str(id)}, ${str(f["label"])}, listOf($ids)),")
        }
        out.appendLine("    )")
        out.appendLine("    val all = listOf(")
        themes.forEach { (id, value) ->
            val t = value as Map<*, *>
            val light = t["light"] == true
            val ramp = t["ramp"] as? List<*> ?: error("themes.json: $id.ramp is missing")
            val firing = t["firing"] as? List<*> ?: error("themes.json: $id.firing is missing")
            require(ramp.size == 5) { "themes.json: $id.ramp needs 5 stops" }
            require(firing.size == 3) { "themes.json: $id.firing needs 3 stops" }
            out.appendLine("        ThemeSpec(")
            out.appendLine("            id = ${str(id)}, name = ${str(t["name"])}, descriptor = ${str(t["descriptor"])},")
            out.appendLine("            isFree = ${id.toString() in free}, note = ${str(t["note"])},")
            out.appendLine("            colors = CascadeColors(")
            out.appendLine("                isLight = $light,")
            tokens.forEach { out.appendLine("                $it = ${color(t[it], "$id.$it")},") }
            out.appendLine("                ramp = listOf(${ramp.mapIndexed { i, c -> color(c, "$id.ramp[$i]") }.joinToString()}),")
            out.appendLine("                firing = listOf(${firing.mapIndexed { i, c -> color(c, "$id.firing[$i]") }.joinToString()}),")
            out.appendLine("                status = ${if (light) "statusLight" else "status"},")
            out.appendLine("            ),")
            out.appendLine("        ),")
        }
        out.appendLine("    )")
        out.appendLine("}")

        val file = outputDir.get().file("com/cascade/alarm/ui/theme/GeneratedThemes.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(out.toString())
    }
}

val generateThemes = tasks.register<GenerateThemesTask>("generateThemes") {
    themesJson.set(rootProject.layout.projectDirectory.file("design/assets/themes.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateThemes, GenerateThemesTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Challenges (SPEC §6): camera for QR and photo match; ML Kit's bundled model works offline
    // and before first unlock; ZXing only generates the printable QR.
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)

    constraints {
        // AndroidX pulls in kotlinx-serialization 1.7.3, room-testing needs 1.8.x, and test APKs are
        // pinned to the app's versions. Align the app instead of leaving MigrationTest broken.
        implementation(libs.kotlinx.serialization.core)
    }
}
