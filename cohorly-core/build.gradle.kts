plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

dependencies {
    // Provided by the Android platform at runtime; the JVM test classpath
    // supplies the standalone jar.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "cohorly-android-core"
        }
    }
}
