import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":innertube"))
    implementation(compose.desktop.currentOs)
    implementation(libs.material3)
    implementation(libs.material.icons)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    implementation(libs.vlcj)
    implementation(libs.protobuf.kotlin)
    implementation(libs.dbus.core)
    implementation(libs.dbus.transport)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.tinypinyin)
    implementation(libs.jcef)
    testImplementation(libs.junit)
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("kotlin") }
        }
    }
}

// Windows installers ship libVLC inside the app so users never install VLC. Fetched only on Windows hosts.
val vlcVersion = "3.0.21"
val vlcSha256 = "a0b7ec02b50adf6417eed014fb8df50af39690505a4225b85b3dc2ed17d14843"
val vlcBundleRoot = layout.buildDirectory.dir("vlc-bundle")
val fetchVlc = tasks.register("fetchVlc") {
    val zip = layout.buildDirectory.file("vlc-$vlcVersion-win64.zip")
    val out = vlcBundleRoot.map { it.dir("windows") }
    onlyIf { System.getProperty("os.name").lowercase().contains("win") || project.hasProperty("bundleVlc") }
    inputs.property("canvasVideoOutput", true)
    outputs.dir(out)
    doLast {
        val z = zip.get().asFile
        fun sha() = MessageDigest.getInstance("SHA-256").digest(z.readBytes()).joinToString("") { b -> "%02x".format(b) }
        if (!z.exists() || sha() != vlcSha256) {
            URI("https://download.videolan.org/pub/videolan/vlc/$vlcVersion/win64/vlc-$vlcVersion-win64.zip").toURL()
                .openStream().use { s -> z.outputStream().use { o -> s.copyTo(o) } }
            check(sha() == vlcSha256) { "VLC zip checksum mismatch" }
        }
        // Canvas uses the memory video output and color converters, without native video windows.
        val skip = listOf("gui", "lua", "video_filter", "video_splitter", "visualization", "spu", "stream_out", "mux", "access_output", "control", "services_discovery", "text_renderer", "d3d9", "d3d11")
        project.sync {
            from(zipTree(z)) {
                include("*/libvlc.dll", "*/libvlccore.dll", "*/plugins/**")
                skip.forEach { exclude("*/plugins/$it/**") }
                exclude { it.path.contains("/plugins/video_output/") && it.name != "libvmem_plugin.dll" }
                eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                includeEmptyDirs = false
            }
            into(out)
        }
    }
}
// Installers ship Chromium for "Sign in with browser" so the first login never waits on a ~150 MB download.
// Must match the jcefmaven version: its installation check compares the release tag in build_meta.json.
val jcefTag = "jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83"
val jcefSha256 = mapOf(
    "linux-amd64" to "83f427992cd783fb6f3d499fdf275675187818aa7ca9c4a2a4d43af534a8bdfc",
    "windows-amd64" to "0dc2ce28766d267f551398e24af7a9bec244b888e651bd97a14ac2d128550875",
)
val fetchJcef = tasks.register("fetchJcef") {
    val host = System.getProperty("os.name").lowercase().let { if ("win" in it) "windows" else if ("linux" in it) "linux" else null }
    val platform = host?.let { "$it-amd64" }
    val jar = layout.buildDirectory.file("jcef-natives-$platform.jar")
    val out = vlcBundleRoot.map { it.dir("$host/jcef") }
    onlyIf { platform in jcefSha256 && System.getProperty("os.arch") in setOf("amd64", "x86_64") }
    inputs.property("tag", jcefTag)
    outputs.dir(out)
    doLast {
        val j = jar.get().asFile
        fun sha() = MessageDigest.getInstance("SHA-256").digest(j.readBytes()).joinToString("") { b -> "%02x".format(b) }
        if (!j.exists() || sha() != jcefSha256[platform]) {
            URI("https://repo.maven.apache.org/maven2/me/friwi/jcef-natives-$platform/$jcefTag/jcef-natives-$platform-$jcefTag.jar").toURL()
                .openStream().use { s -> j.outputStream().use { o -> s.copyTo(o) } }
            check(sha() == jcefSha256[platform]) { "JCEF natives checksum mismatch" }
        }
        val tarGz = zipTree(j).matching { include("*.tar.gz") }.singleFile
        project.sync {
            from(tarTree(resources.gzip(tarGz)))
            into(out)
        }
        // jcefmaven treats a directory with build_meta.json and install.lock as installed.
        out.get().file("install.lock").asFile.writeText("")
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(fetchVlc, fetchJcef) }

// Release version: appVersion in gradle.properties. MSI needs MAJOR.MINOR.BUILD numbers.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "1.0.0"

compose.desktop {
    application {
        mainClass = "com.utaloom.MainKt"
        jvmArgs += listOf("-Xmx768m", "-Dutaloom.version=$appVersion")
        // packageRelease* tasks run ProGuard to drop unused code (mainly the huge icons pack).
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate.set(false)
            optimize.set(false)
        }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "utaloom"
            packageVersion = appVersion
            description = "Desktop YouTube Music client"
            vendor = "Utaloom"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(vlcBundleRoot)
            modules("java.net.http", "java.sql", "jdk.unsupported", "java.naming", "jdk.security.auth")
            linux {
                iconFile.set(project.file("icon.png"))
                debMaintainer = "utaloom@caliph.dev"
                appCategory = "Audio"
                debPackageVersion = appVersion
            }
            windows {
                iconFile.set(project.file("icon.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Stable id so MSI upgrades replace old installs instead of duplicating them.
                upgradeUuid = "5b8e1f0c-6f3a-4d7e-9c2b-3a4d5e6f7a8b"
            }
        }
    }
}

// deb/rpm pull libVLC from the distro repos so users never install VLC by hand.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
    val deps = when (targetFormat) {
        TargetFormat.Deb -> "libvlc5,vlc-plugin-base"
        // ponytail: Fedora package names; openSUSE/RHEL name VLC differently, add a separate rpm when someone asks.
        TargetFormat.Rpm -> "vlc-libs,vlc-plugins-base,vlc-plugins-extra,vlc-plugin-ffmpeg,vlc-plugin-pulseaudio"
        else -> null
    }
    // jpackage splits args on spaces, so the lists above must stay space-free.
    if (deps != null) freeArgs.addAll("--linux-package-deps", deps)
}

tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.utaloom.ProbeKt")
    args = (project.findProperty("videoId") as String?)?.let { listOf(it) } ?: emptyList()
}

tasks.register<JavaExec>("ltProbe") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.utaloom.LtProbeKt")
}

tasks.register<JavaExec>("lyricsProbe") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.utaloom.LyricsProbeKt")
}

tasks.register<JavaExec>("playerSmoke") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.utaloom.PlayerSmokeKt")
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("smoke-data").get().asFile.absolutePath)
    environment("APPDATA", layout.buildDirectory.dir("smoke-data").get().asFile.absolutePath)
}

tasks.register<JavaExec>("smartShuffleAcceptance") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.utaloom.SmartShuffleAcceptanceKt")
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("smart-shuffle-data").get().asFile.absolutePath)
    environment("APPDATA", layout.buildDirectory.dir("smart-shuffle-data").get().asFile.absolutePath)
}

tasks.register<JavaExec>("networkSmoke") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.utaloom.PlayerSmokeKt")
    args("network")
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("network-smoke-data").get().asFile.absolutePath)
    environment("APPDATA", layout.buildDirectory.dir("network-smoke-data").get().asFile.absolutePath)
}

tasks.register<JavaExec>("togetherAcceptance") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.utaloom.TogetherAcceptanceKt")
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("together-smoke-data").get().asFile.absolutePath)
    environment("APPDATA", layout.buildDirectory.dir("together-smoke-data").get().asFile.absolutePath)
}

// Keep tests away from the user's real library/settings.
tasks.withType<Test>().configureEach {
    environment("XDG_DATA_HOME", layout.buildDirectory.dir("test-data").get().asFile.absolutePath)
    // Show the active test and real errors so native/network hangs are visible in CI.
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
