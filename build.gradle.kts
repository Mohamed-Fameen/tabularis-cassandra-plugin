
plugins {
    java
    application
    id("org.graalvm.buildtools.native") version "0.10.3"
}

group = "dev.tabularis.plugin"
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val jacksonVersion = "2.17.2"
val driverVersion = "4.19.3"

dependencies {
    // DataStax OSS Java driver - CQL native protocol v4, works against both
    // Apache Cassandra and ScyllaDB (ScyllaDB is wire-compatible with Cassandra's
    // CQL protocol). See README "Cassandra vs ScyllaDB" for shard-awareness notes.
    implementation("org.apache.cassandra:java-driver-core:${driverVersion}")

    // JSON-RPC message (de)serialization
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.tabularis.plugin.cassandra.Main")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "dev.tabularis.plugin.cassandra.Main"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("tabularis-cassandra-plugin")
            mainClass.set("dev.tabularis.plugin.cassandra.Main")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            // Hand-written reflect/resource config for the DataStax driver's
            // default pluggable policies lives in
            // src/main/resources/META-INF/native-image/ and is picked up
            // automatically from the classpath. It is a starting point, not
            // tracing-agent output - see the README.md next to those files
            // and README.md's "Building a native image" section for how to
            // regenerate it properly against a live cluster.
            resources.autodetect()
        }
    }
    // Pulls community-maintained GraalVM reachability metadata (notably for
    // Netty, which the DataStax driver depends on) so we don't have to
    // hand-roll every low-level reflection/resource entry ourselves.
    metadataRepository {
        enabled.set(true)
    }
}
