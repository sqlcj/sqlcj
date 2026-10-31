package org.sqlcj.spring.boot.postgres.jdbc;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.sqlcj.spring.boot.postgres.TransactionalRunner;

/**
 * An application on Spring Boot's JDBC support alone, which the starter
 * configures its executor and registers the generated repositories of the
 * {@code generated} package below this one for.
 *
 * <p>It declares nothing but the transactional component the scenarios call,
 * so that everything else about it is Spring Boot's own default.
 */
@SpringBootApplication
@Import(TransactionalRunner.class)
public class JdbcApplication {
}
