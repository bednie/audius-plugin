// build.gradle.kts

plugins {
    java
    alias(libs.plugins.lavalink)
}

group = "com.audius"
version = "0.1.0"

lavalinkPlugin {
    name = "audius-plugin"
    apiVersion = libs.versions.lavalink.api
    serverVersion = libs.versions.lavalink.server
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}

dependencies {
    // add your dependencies here
}