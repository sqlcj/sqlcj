package org.sqlcj.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one repository class sqlcj generated.
 *
 * <p>The annotation has no effect in plain Java: a generated repository is
 * constructed with its {@link QueryExecutor} and used exactly as it is without
 * it.
 *
 * <p>The sqlcj Spring Boot starter registers the classes that carry it as
 * beans, so a hand-written class must not carry it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GeneratedRepository {
}
