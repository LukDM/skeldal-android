import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: create android/keystore.properties (NOT in git) with
//   storeFile=/path/to/my.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
// Without it, release builds are produced unsigned.
fun engineVersion(): String = try {
    val p = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
        .directory(rootProject.projectDir.parentFile).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0 && out.isNotEmpty() && !out.contains(' ')) out else ""
} catch (e: Exception) {
    ""   // e.g. built from a ZIP without .git
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "cz.gates.skeldal"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "cz.gates.skeldal"
        // Android 8.0+: covers practically all phones and tablets in use, and keeps
        // std::filesystem and modern NDK APIs available without workarounds.
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"

        // engine version = git revision of the repository (as the desktop build shows it)
        buildConfigField("String", "ENGINE_VERSION", "\"${engineVersion()}\"")
        buildConfigField("String", "SDL_VERSION", "\"2.30.9\"")

        // arm64-v8a first (primary), x86_64 for emulators, armeabi-v7a for old devices
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            // The engine renders the 3D view in software (C code), so it must be optimized
            // even in debug builds; -O0 made movement visibly choppy on slower devices.
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // fixes of the game data from the engine repository (data/maps/BILA_VEZ.MAP), see install/GameFixes.kt
    sourceSets {
        getByName("main") {
            assets.srcDir("../../data")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
}
