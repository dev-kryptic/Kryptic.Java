# Kryptic Daemon Client (dev.kryptic:daemon-client)

Zero-dependency core client. The JVM cannot modify its own process environment, so secrets
land in **system properties**; `Kryptic.fetch()` returns them as a map for framework
integrations.

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

## Spring Boot

`dev.kryptic:daemon-client-spring-boot` adds `@EnableKryptic`, which calls `Kryptic.fetch()`
and contributes a `kryptic` property source so `@Value`, `Environment`, and
`application.yml` placeholders see the secrets. Same no-op / never-overwrite rules as the
core. Spring Framework 6.1+ is `provided` (Boot 3 already has it).

```xml
<dependency>
  <groupId>dev.kryptic</groupId>
  <artifactId>daemon-client-spring-boot</artifactId>
  <version>1.0.1</version>
</dependency>
```

```java
import dev.kryptic.spring.EnableKryptic;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;

@SpringBootApplication
@EnableKryptic
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

Protocol: [daemon/PROTOCOL.md](https://github.com/dev-kryptic/Kryptic.Daemon/blob/main/PROTOCOL.md). License: Apache-2.0. `mvn test`
