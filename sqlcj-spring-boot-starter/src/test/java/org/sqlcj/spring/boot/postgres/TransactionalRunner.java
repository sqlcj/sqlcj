package org.sqlcj.spring.boot.postgres;

import org.springframework.transaction.annotation.Transactional;

/**
 * Runs work inside the transaction Spring Boot's transaction manager owns, the
 * way an application's own {@code @Transactional} service method does.
 *
 * <p>Both applications import it, so the writes a test makes through the
 * registered repositories take part in one Spring-managed transaction that
 * commits when the work returns and rolls back when it throws.
 *
 * <p>The class and its method are public and non-final so that the container
 * can proxy them.
 */
public class TransactionalRunner {

    @Transactional
    public void run(Runnable work) {
        work.run();
    }
}
