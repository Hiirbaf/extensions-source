plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "eu.kanade.tachiyomi.extension.all.googledrive"

    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.16.1")
    compileOnly(project(":lib-source"))
}

// Copia el ícono a la carpeta de salida de extensiones
tasks.register<Copy>("copyExtensionIcon") {
    from("src/all/googledrive/icon.png")
    into("$buildDir/outputs/extension/icons/")
}
