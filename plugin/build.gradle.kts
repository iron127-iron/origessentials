plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.origessentials"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.slf4j:slf4j-api:2.0.13")
    // H2 embedded database for local player data
    implementation("com.h2database:h2:2.2.224")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.shadowJar {
    archiveBaseName.set("OrigEssentials")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    minimize()
    exclude("org/slf4j/**")
    manifest {
        attributes(
            "Main-Class" to "com.origessentials.plugin.OrigEssentialsPlugin",
            "Paperweight-Mixin" to "true"
        )
    }
}

tasks.jar {
    archiveBaseName.set("OrigEssentials-dev")
    manifest {
        attributes(
            "Main-Class" to "com.origessentials.plugin.OrigEssentialsPlugin"
        )
    }
}