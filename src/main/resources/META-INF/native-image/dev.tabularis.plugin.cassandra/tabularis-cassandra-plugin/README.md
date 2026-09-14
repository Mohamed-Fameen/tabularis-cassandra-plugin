# Native-image config for this plugin

`reflect-config.json` and `resource-config.json` in this directory are a
**hand-written starting point**, not output from the GraalVM tracing agent -
the sandbox this plugin was originally scaffolded in could not run a live
GraalVM-vs-Cassandra combination (its network policy blocks Maven Central, so
the driver jar itself couldn't even be resolved there). They list the
DataStax Java driver's default pluggable-policy implementations, which
`DefaultDriverContext` resolves by class name from `reference.conf` and
instantiates reflectively even when nothing is overridden.

`build.gradle.kts` also enables the GraalVM Reachability Metadata Repository
(`graalvmNative.metadataRepository.enabled = true`), which supplies
community-maintained config for common libraries on the classpath (Netty in
particular) without us having to hand-roll it.

**Before you rely on a release build**, regenerate this config properly:

```bash
./gradlew nativeCompile   # sanity: does it even compile with today's config?
./scripts/exercise-plugin.sh --agent   # runs the JAR under -agentlib:native-image-agent
                                        # against a real Cassandra, exercising every
                                        # RPC method, and writes fresh config here
git diff src/main/resources/META-INF/native-image/
```

`.github/workflows/refresh-native-image-config.yml` automates this same
flow against a Cassandra service container and opens a PR with whatever
changed, so config drift gets caught as the driver/Netty are upgraded rather
than discovered at release time.
