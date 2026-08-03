# Kryptic Java SDK (dev.kryptic:krypticdev)

Zero-dependency core SDK. The JVM cannot modify its own process environment, so secrets
land in **system properties**; `Kryptic.fetch()` returns them as a map for framework
integrations (the Spring Boot module with `@EnableKryptic` builds on it).

```java
import dev.kryptic.Kryptic;

public static void main(String[] args) {
    Kryptic.inject(); // system properties, development only
}
```

No-op outside development (`SPRING_PROFILES_ACTIVE` etc. = production/staging, or
`KRYPTIC_DISABLED=true`). Never throws; never overwrites existing properties or env vars.
Configuration: `KRYPTIC_PROJECT_ID`, `KRYPTIC_ENV`, `KRYPTIC_SOCKET_PATH`, `KRYPTIC_TIMEOUT_MS`,
`KRYPTIC_SILENT` (env vars or system properties). Requires Java 17+ (unix sockets).

Protocol: [daemon/PROTOCOL.md](https://github.com/dev-kryptic/Kryptic.Daemon/blob/main/PROTOCOL.md). License: Apache-2.0. `mvn test`
