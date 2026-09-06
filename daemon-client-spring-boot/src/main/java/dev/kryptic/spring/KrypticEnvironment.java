package dev.kryptic.spring;

import dev.kryptic.Kryptic;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

final class KrypticEnvironment {

    static final String PROPERTY_SOURCE_NAME = "kryptic";

    private KrypticEnvironment() {
    }

    static void contribute(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }

        Map<String, Object> source = new LinkedHashMap<>();
        for (Map.Entry<String, String> secret : Kryptic.fetch().entrySet()) {
            if (environment.containsProperty(secret.getKey())) {
                continue;
            }
            source.put(secret.getKey(), secret.getValue());
        }
        if (source.isEmpty()) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, source));
    }
}
