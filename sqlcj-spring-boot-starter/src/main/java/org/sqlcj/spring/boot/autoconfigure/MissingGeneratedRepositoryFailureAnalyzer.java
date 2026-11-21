package org.sqlcj.spring.boot.autoconfigure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.analyzer.AbstractInjectionFailureAnalyzer;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;
import org.sqlcj.runtime.GeneratedRepository;
import org.sqlcj.runtime.QueryExecutor;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Explains a startup failure caused by a missing bean of a type sqlcj
 * generated, by naming the decision of the {@link GeneratedRepositoryRegistrar}
 * that left it unregistered and the fix the Spring guide documents.
 *
 * <p>Spring Boot builds it from the starter's {@code META-INF/spring.factories}
 * once a refresh has failed, passing the failed context's bean factory, whose
 * bean definitions outlive that failure. The registrar's decisions are
 * therefore read from the same definitions the registrar itself read.
 *
 * <p>It reports only a {@link NoSuchBeanDefinitionException} that found no bean
 * of a type carrying {@link GeneratedRepository}, and only while the registrar
 * is defined, so an application that excluded the starter's auto-configuration
 * and registers its repositories itself keeps Spring Boot's own report. For
 * everything else it returns nothing, which leaves that report in place as
 * well.
 *
 * <p>Its order is the one Spring Boot gives its own analyzer of the same
 * exception, which runs this one ahead of the unordered
 * {@code NoSuchBeanDefinitionFailureAnalyzer} that writes the generic
 * {@code Consider defining a bean of type ...}.
 */
@NullMarked
final class MissingGeneratedRepositoryFailureAnalyzer
    extends AbstractInjectionFailureAnalyzer<NoSuchBeanDefinitionException>
    implements Ordered {

    /** The opening both executor reasons share. */
    private static final String NO_SINGLE_EXECUTOR = "The sqlcj starter registers generated repositories "
        + "only over a single QueryExecutor bean, ";

    private final ListableBeanFactory beanFactory;

    MissingGeneratedRepositoryFailureAnalyzer(BeanFactory beanFactory) {
        Assert.isTrue(beanFactory instanceof ListableBeanFactory, "'beanFactory' must be a ListableBeanFactory");
        this.beanFactory = (ListableBeanFactory) beanFactory;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    protected @Nullable FailureAnalysis analyze(
        Throwable rootFailure,
        NoSuchBeanDefinitionException cause,
        @Nullable String description
    ) {
        Class<?> repository = cause.getBeanType();
        if (
            cause.getNumberOfBeansFound() != 0
                || repository == null
                || !repository.isAnnotationPresent(GeneratedRepository.class)
                || definitionNames(GeneratedRepositoryRegistrar.class).isEmpty()
        ) {
            return null;
        }
        Reason reason = reason(repository);
        if (reason == null) {
            return null;
        }
        return new FailureAnalysis(
            String.format(
                "%s required a bean of type '%s' that could not be found.%n%n%s",
                (description != null) ? description : "A component",
                repository.getName(),
                reason.description()
            ),
            reason.action(),
            cause
        );
    }

    /**
     * Returns the registrar's reason for leaving the repository unregistered,
     * or {@code null} when the definitions show none: the executor count
     * decides first, because without a single executor the registrar registers
     * nothing wherever the package lies.
     */
    private @Nullable Reason reason(Class<?> repository) {
        List<String> executors = definitionNames(QueryExecutor.class);
        if (executors.isEmpty()) {
            return noExecutor();
        }
        if (executors.size() > 1) {
            return severalExecutors(executors);
        }
        return outsideTheAutoConfigurationPackages(repository);
    }

    /**
     * Returns why no executor is defined. While the starter's
     * auto-configuration applies, its executor's only other condition is that
     * the application defines none of its own, so no executor always means no
     * single {@code DataSource} candidate.
     */
    private Reason noExecutor() {
        List<String> dataSources = definitionNames(DataSource.class);
        if (dataSources.isEmpty()) {
            return new Reason(
                NO_SINGLE_EXECUTOR
                    + "and there is none: the starter defines its own over the application's DataSource, and "
                    + "there is no DataSource bean.",
                "Configure a DataSource, for example with Spring Boot's spring.datasource.* properties, or "
                    + "define a QueryExecutor bean. See \"The executor\" in the sqlcj Spring guide."
            );
        }
        return new Reason(
            NO_SINGLE_EXECUTOR
                + "and there is none: the starter defines its own only over a single DataSource candidate, and "
                + "none of the DataSource beans " + quoted(dataSources) + " is primary.",
            "Mark the DataSource the generated repositories use @Primary, or declare every other DataSource "
                + "with @Bean(defaultCandidate = false). See \"Several Databases\" in the sqlcj Spring guide."
        );
    }

    private Reason severalExecutors(List<String> executors) {
        return new Reason(
            NO_SINGLE_EXECUTOR + "and there are " + executors.size() + ": " + quoted(executors) + ".",
            "Define a single QueryExecutor bean, or register each generated repository over the QueryExecutor "
                + "it uses, with @Import over the default one or a @Bean method over a qualified one. See "
                + "\"Several Databases\" in the sqlcj Spring guide."
        );
    }

    /**
     * Returns the package reason, or {@code null} when the repository's package
     * is an auto-configuration package or below one, and when no
     * auto-configuration package is registered at all.
     */
    private @Nullable Reason outsideTheAutoConfigurationPackages(Class<?> repository) {
        if (!AutoConfigurationPackages.has(beanFactory)) {
            return null;
        }
        List<String> packages = AutoConfigurationPackages.get(beanFactory);
        String repositoryPackage = repository.getPackageName();
        for (String basePackage : packages) {
            if (repositoryPackage.equals(basePackage) || repositoryPackage.startsWith(basePackage + ".")) {
                return null;
            }
        }
        return new Reason(
            "The sqlcj starter registers only the generated repositories in the application's "
                + "auto-configuration packages (" + String.join(", ", packages) + "), and this repository's "
                + "package is " + repositoryPackage + ".",
            "Register the repository with @Import(" + repository.getSimpleName() + ".class) on a @Configuration "
                + "class, or generate it under the application's packages by changing java.package in the sqlcj "
                + "configuration. See \"A Generated Package Outside the Application's Packages\" in the sqlcj "
                + "Spring guide."
        );
    }

    /**
     * Names every bean definition of the type exactly as the registrar counts
     * them: without instantiating a factory bean, so the count is the one the
     * registrar acted on, and in definition order.
     */
    private List<String> definitionNames(Class<?> type) {
        return List.of(beanFactory.getBeanNamesForType(type, true, false));
    }

    private static String quoted(List<String> names) {
        return names.stream().map(name -> "'" + name + "'").collect(Collectors.joining(", "));
    }

    /** One reason the registrar left the repository unregistered, with its fix. */
    private record Reason(String description, String action) {
    }
}
