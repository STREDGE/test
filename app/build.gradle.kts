plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "samm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "samm.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Постоянный ключ из репозитория: обновления менеджера ставятся поверх старой версии.
        // Это отдельный ключ только для менеджера, игра подписывается ключом, созданным на телефоне.
        create("samm") {
            storeFile = rootProject.file("keystore/samm-manager.jks")
            storePassword = "samm-manager"
            keyAlias = "samm"
            keyPassword = "samm-manager"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("samm")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("samm")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/versions/**", "META-INF/INDEX.LIST", "**/module-info.class",
            )
        }
    }

    // libAML.so и другие .so для игры лежат в assets/payload/<abi>/ — их кладёт CI.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("payload-assets"))
}

dependencies {
    implementation(project(":modcore"))
    implementation(project(":patcher"))
    implementation("com.android.tools.build:apksig:8.10.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
