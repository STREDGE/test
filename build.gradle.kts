// Все плагины объявлены здесь (apply false), чтобы Kotlin-плагин и AGP жили в одном classloader'е.
// Версии — в settings.gradle.kts (pluginManagement.plugins).
plugins {
    id("com.android.application") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.jvm") apply false
}
