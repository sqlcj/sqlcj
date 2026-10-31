package org.sqlcj.spring.boot.postgres;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;

/**
 * An application that registers a generated repository itself, under a bean
 * name of its own, which the starter then leaves alone.
 *
 * <p>The repository is registered as an initializer rather than as a
 * {@code @Bean} method, because the generated class is loaded at run time from
 * the directory the tests compiled it into; the container autowires its one
 * constructor argument, the executor, as it does for any class registered this
 * way.
 *
 * <p>It declares its context type, so that Spring Boot can resolve the type
 * argument a lambda would not carry, and it lives outside the application's
 * package, so that component scanning cannot apply it to another scenario.
 */
public final class ApplicationRepositoryInitializer
    implements ApplicationContextInitializer<GenericApplicationContext> {

    private final String beanName;

    private final Class<?> repositoryClass;

    public ApplicationRepositoryInitializer(String beanName, Class<?> repositoryClass) {
        this.beanName = beanName;
        this.repositoryClass = repositoryClass;
    }

    @Override
    public void initialize(GenericApplicationContext context) {
        context.registerBean(beanName, repositoryClass);
    }
}
