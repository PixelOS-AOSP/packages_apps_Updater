import org.lineageos.generatebp.GenerateBpPluginExtension
import org.lineageos.generatebp.models.Module
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.lineageos.generatebp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            load(keystorePropertiesFile.inputStream())
        }
    }

android {
    compileSdk = 35
    namespace = "net.pixelos.ota"

    defaultConfig {
        applicationId = "net.pixelos.ota"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            // Includes the default ProGuard rules files.
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                ),
            )
        }
        getByName("debug") {
            // Append .dev to package name so we won't conflict with AOSP build.
            applicationIdSuffix = ".dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            (keystoreProperties["keyAlias"] as String?)?.let {
                keyAlias = it
            }
            (keystoreProperties["keyPassword"] as String?)?.let {
                keyPassword = it
            }
            (keystoreProperties["storeFile"] as String?)?.let {
                storeFile = file(it)
            }
            (keystoreProperties["storePassword"] as String?)?.let {
                storePassword = it
            }
        }
    }
}

dependencies {
    compileOnly(fileTree(mapOf("dir" to "../system_libs", "include" to listOf("*.jar"))))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}

configure<GenerateBpPluginExtension> {
    targetSdk.set(android.defaultConfig.targetSdk!!)
    minSdk.set(android.defaultConfig.minSdk!!)
    availableInAOSP.set { module: Module ->
        when {
            module.group.startsWith("androidx") -> true
            module.group.startsWith("org.jetbrains") -> true
            module.group == "com.google.android.material" -> true
            module.group == "com.google.errorprone" -> true
            module.group == "com.google.guava" -> true
            module.group == "junit" -> true
            else -> false
        }
    }
}
