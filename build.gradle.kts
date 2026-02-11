plugins {
    kotlin("jvm") version "2.3.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.9.43")
    implementation("org.jetbrains.skiko:skiko-awt:0.9.43")
}

tasks.test {
    useJUnitPlatform()
}