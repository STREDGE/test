pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.10.1"
        id("org.jetbrains.kotlin.android") version "2.1.21"
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "sa-mod-manager"

// Чистая JVM-логика (IMG, архивы модов) — собирается и тестируется где угодно.
include(":modcore")
// Патчер APK — нужны библиотеки из Google Maven (apksig, smali).
include(":patcher")

// Android-приложение подключаем только там, где есть Android SDK (CI или ПК разработчика).
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) {
    include(":app")
}
