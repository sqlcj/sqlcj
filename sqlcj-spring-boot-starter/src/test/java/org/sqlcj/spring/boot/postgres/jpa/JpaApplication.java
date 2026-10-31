package org.sqlcj.spring.boot.postgres.jpa;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.sqlcj.spring.boot.postgres.TransactionalRunner;

/**
 * An application on Spring Boot's JPA support, which maps the same table one
 * of the generated repositories writes.
 *
 * <p>It declares nothing but the transactional component the scenarios call,
 * so that everything else about it, the entity manager factory and the
 * transaction manager included, is Spring Boot's own default.
 */
@SpringBootApplication
@Import(TransactionalRunner.class)
public class JpaApplication {
}
