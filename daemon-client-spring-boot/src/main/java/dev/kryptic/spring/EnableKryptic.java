package dev.kryptic.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Fetches development secrets via {@link dev.kryptic.Kryptic#fetch()} and adds them
 * to the Spring {@code Environment} as a property source named {@code kryptic}.
 *
 * <p>Existing properties, system properties, and environment variables are never
 * overwritten. Outside development this is a no-op, and it never throws.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(KrypticPropertySourceRegistrar.class)
public @interface EnableKryptic {
}
