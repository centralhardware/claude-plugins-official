plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories { mavenCentral() }

dependencies {
    implementation("dev.inmo:tgbotapi:37.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation("io.ktor:ktor-server-websockets:3.6.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation(kotlin("test"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
java { targetCompatibility = JavaVersion.VERSION_21 }

tasks.test { useJUnitPlatform() }

// One self-contained jar at a fixed path — .mcp.json launches it via start.sh.
val fatJar by tasks.registering(Jar::class) {
    archiveFileName.set("telegram-channel.jar")
    destinationDirectory.set(layout.projectDirectory.dir("build"))
    manifest { attributes["Main-Class"] = "telegram.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}
