plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Правка DEX (работает и на Android).
    api("org.smali:dexlib2:2.5.2")
    // Чтение/правка бинарного AndroidManifest.xml.
    api("io.github.reandroid:ARSCLib:1.4.0")
    // Копирование zip-записей без перепаковки.
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.smali:smali:2.5.2")
}

tasks.test {
    useJUnitPlatform()
}
