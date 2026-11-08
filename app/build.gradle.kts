plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.thyagoohiggins.listeningtime"
    compileSdk = 35

    defaultConfig {
        // NUNCA altere o applicationId: é ele (junto com a assinatura) que permite
        // instalar uma versão nova por cima da antiga.
        applicationId = "io.github.thyagoohiggins.listeningtime"
        minSdk = 26
        targetSdk = 35
        // O GitHub Actions passa um versionCode sempre crescente (minutos desde 2025).
        versionCode = env("VERSION_CODE")?.toInt() ?: 1
        versionName = env("VERSION_NAME") ?: "1.0-dev"
    }

    // Mesma chave em TODAS as builds => atualização sem "conflito de pacote".
    // A keystore vem dos Secrets do GitHub (nunca é commitada).
    val keystorePath = env("KEYSTORE_FILE") ?: "${rootDir}/keystore/release.jks"
    val hasKeystore = file(keystorePath).exists()

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = env("KEYSTORE_PASSWORD")
                keyAlias = env("KEY_ALIAS") ?: "listeningtime"
                keyPassword = env("KEY_PASSWORD") ?: env("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
