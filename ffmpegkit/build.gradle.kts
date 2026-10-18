// Imported rather than fully qualified: AGP 9 registers a `java` extension that shadows the `java.*` packages.
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin)
    id("maven-publish")
}

// Test-only: `-PcapiTestAsan` (or `=true`) builds an AddressSanitizer core for connectedCapiTest. It applies to
// the debug build type only, so release and published AARs never get it.
val capiTestAsan: Provider<Boolean> =
    providers.gradleProperty("capiTestAsan").map { it.isEmpty() || it.toBoolean() }.orElse(false)

android {
    namespace = "io.github.nova.ffmpegkit"
    compileSdk = rootProject.extra["compileSdk"].toString().toInt()
    // AGP installs this NDK on demand, including on JitPack. NDK r28+ links 16 KB-aligned .so by default; r27 produced 4 KB alignment.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = rootProject.extra["minSdk"].toString().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        consumerProguardFiles("consumer-rules.pro")

        aarMetadata {
            // The public API only uses classes that exist since API 26. By default AGP 9 writes compileSdk (37)
            // here, which forces every app that depends on this library to compile against 37.
            minCompileSdk = rootProject.extra["minSdk"].toString().toInt()
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    // A separate CMake configuration, so it builds next to the normal one.
                    if (capiTestAsan.get()) arguments += "-DFFKX_ASAN=ON"
                }
            }
        }
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
            // Consumers' compilers read this metadata: Kotlin 2.2 metadata is readable from Kotlin 2.1 on,
            // including AGP 9's built-in Kotlin (2.2.10). With the default 2.4 a new AGP 9 project failed to compile.
            languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
            apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        }
        // The kotlin-stdlib the POM asks for; kotlinx-coroutines 1.11 already needs 2.2.20.
        coreLibrariesVersion = "2.2.20"
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
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Playback checks for TrimMode.EDIT_LIST; test APK only, not a library dependency.
    androidTestImplementation(libs.androidx.media3.exoplayer)
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

/**
 * Runs the C ABI test program (src/main/cpp/test/capi_test.c) on the device adb picks (set ANDROID_SERIAL
 * to choose), next to the libraries it was linked with, against the androidTest media samples. Uses the
 * device's primary ABI, or `-PcapiTestAbi=armeabi-v7a` for a 32-bit run on a 64-bit device.
 * `-PcapiTestAsan` runs an AddressSanitizer build of the core and the test program with the NDK's runtime.
 */
abstract class ConnectedCapiTest @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val adb: RegularFileProperty

    /** AGP's native build output (intermediates/cxx/Debug), one directory per build configuration and ABI. */
    @get:Internal
    abstract val nativeOutputs: DirectoryProperty

    @get:Internal
    abstract val media: DirectoryProperty

    @get:Internal
    abstract val abi: Property<String>

    @get:Internal
    abstract val asan: Property<Boolean>

    @get:Internal
    abstract val ndk: DirectoryProperty

    @TaskAction
    fun run() {
        val abi = abi.orNull ?: adb("shell", "getprop", "ro.product.cpu.abi").output.trim()
        val name = if (asan.get()) "ffkx_capi_test-asan" else "ffkx_capi_test"
        val program = nativeOutputs.get().asFile.walk()
            .filter { it.name == name && it.parentFile.name == abi }
            .maxByOrNull { it.lastModified() }
            ?: throw GradleException("No $name built for $abi")
        val files = program.parentFile.listFiles { file -> file.name.endsWith(".so") }!!.map { it.path } +
            program.path + listOfNotNull(if (asan.get()) asanRuntime(abi).path else null)
        val dir = "/data/local/tmp/ffkx-capi-test"
        adb("shell", "rm -rf $dir && mkdir -p $dir/media $dir/scratch").check()
        adb("push", *files.toTypedArray(), "$dir/").check()
        adb("push", *media.get().asFile.listFiles()!!.map { it.path }.toTypedArray(), "$dir/media/").check()
        val run = adb("shell", "cd $dir && chmod 755 $name && LD_LIBRARY_PATH=. ./$name media scratch")
        logger.lifecycle("${program.path} on $abi:\n${run.output}")
        if (run.exitValue != 0) throw GradleException("$name failed on $abi (exit ${run.exitValue}); files kept in $dir")
        adb("shell", "rm -rf $dir").check()
    }

    /** The NDK's AddressSanitizer runtime for [abi], e.g. libclang_rt.asan-x86_64-android.so. */
    private fun asanRuntime(abi: String): File {
        val arch = mapOf("arm64-v8a" to "aarch64", "armeabi-v7a" to "arm", "x86_64" to "x86_64").getValue(abi)
        return ndk.get().asFile.resolve("toolchains/llvm/prebuilt").walk()
            .firstOrNull { it.name == "libclang_rt.asan-$arch-android.so" }
            ?: throw GradleException("No ASan runtime for $abi in ${ndk.get()}")
    }

    private class Result(val command: String, val exitValue: Int, val output: String) {
        fun check() {
            if (exitValue != 0) throw GradleException("$command failed (exit $exitValue):\n$output")
        }
    }

    private fun adb(vararg args: String): Result {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(adb.get().asFile.path, *args)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        return Result("adb ${args.first()}", result.exitValue, output.toString(Charsets.UTF_8))
    }
}

tasks.register<ConnectedCapiTest>("connectedCapiTest") {
    group = "verification"
    description = "Runs the C ABI test program on the connected device or emulator."
    dependsOn("externalNativeBuildDebug")
    adb.set(androidComponents.sdkComponents.adb)
    nativeOutputs.set(layout.buildDirectory.dir("intermediates/cxx/Debug"))
    media.set(layout.projectDirectory.dir("src/androidTest/assets/media"))
    abi.set(providers.gradleProperty("capiTestAbi"))
    asan.set(capiTestAsan)
    ndk.set(androidComponents.sdkComponents.ndkDirectory)
}
