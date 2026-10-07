// Imported rather than fully qualified: AGP 9 registers a `java` extension that shadows the `java.*` packages.
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin)
    id("maven-publish")
}

android {
    namespace = "io.github.nova.ffmpegkit"
    compileSdk = rootProject.extra["compileSdk"].toString().toInt()
    // Keep in sync with jitpack.yml. NDK r28+ links 16 KB-aligned .so by default; r27 produced 4 KB alignment.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = rootProject.extra["minSdk"].toString().toInt()

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.github.zqcformix"
                artifactId = "ffmpegkit"
                version = rootProject.extra["versionName"].toString()

                pom {
                    name.set("FFmpegKit")
                    description.set("Android FFmpeg wrapper library with libass subtitle support")
                    url.set("https://github.com/zqcformix/FFmpegKitx")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}

// Google Play requires 16 KB page size support for apps targeting Android 15+.
// Checks every 64-bit .so shipped in the release AAR; 32-bit ABIs never run on 16 KB page devices.
val verifyNativeAlignment = tasks.register("verifyNativeAlignment") {
    group = "verification"
    description = "Fails if a 64-bit .so in the release AAR has a LOAD segment aligned below 16 KB."
    val aars = files(tasks.named("bundleReleaseAar"))
    inputs.files(aars)
    doLast {
        fun minLoadAlignment(elf: ByteArray): Long? {
            val buf = ByteBuffer.wrap(elf)
            if (elf.size < 0x40 || buf.getInt(0) != 0x7F454C46 || elf[4].toInt() != 2) return null
            buf.order(if (elf[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            val phOff = buf.getLong(0x20).toInt()
            val phEntSize = buf.getShort(0x36).toInt() and 0xFFFF
            val phNum = buf.getShort(0x38).toInt() and 0xFFFF
            return (0 until phNum).map { phOff + it * phEntSize }
                .filter { buf.getInt(it) == 1 } // PT_LOAD
                .minOfOrNull { buf.getLong(it + 0x30) }
        }

        val checked = mutableListOf<String>()
        val failures = mutableListOf<String>()
        aars.filter { it.name.endsWith(".aar") }.forEach { aar ->
            ZipFile(aar).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".so") }.forEach { entry ->
                    val elf = zip.getInputStream(entry).use { it.readBytes() }
                    if (elf.size > 4 && elf[4].toInt() != 2) return@forEach
                    val align = minLoadAlignment(elf)
                    checked += entry.name
                    if (align == null || align < 16384) {
                        failures += "${entry.name}: ${align?.let { "0x" + it.toString(16) } ?: "unreadable ELF"}"
                    }
                }
            }
        }
        if (checked.isEmpty()) throw GradleException("No 64-bit .so found in $aars")
        if (failures.isNotEmpty()) {
            throw GradleException("LOAD segments below 16 KB alignment:\n" + failures.joinToString("\n"))
        }
        logger.lifecycle("16 KB alignment OK for ${checked.size} libraries")
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(verifyNativeAlignment)
}
