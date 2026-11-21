package org.sqlcj.spring.boot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.spring.boot.autoconfigure.outside.OutsideRepository;
import org.sqlcj.spring.boot.autoconfigure.repositories.NotesRepository;
import org.sqlcj.spring.boot.autoconfigure.repositories.UnmarkedRepository;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that an application failing to start on a missing generated
 * repository is told why the starter did not register it and what to do, for
 * each decision of the {@link GeneratedRepositoryRegistrar}: no
 * {@code QueryExecutor} because there is no {@code DataSource} bean, no
 * executor because several {@code DataSource} beans leave no single candidate,
 * several executors, and a repository package outside the auto-configuration
 * packages.
 *
 * <p>A failure analyzer runs on a real startup failure alone — Spring Boot's
 * {@code ApplicationContextRunner} runs none — so every test starts a
 * {@link SpringApplicationBuilder} and reads the report from the log. Each one
 * also asserts that Spring Boot's generic action is absent, which proves this
 * analyzer ran ahead of Spring Boot's own analyzer of the same exception.
 *
 * <p>A last test proves the other side: a missing type without the marker
 * keeps Spring Boot's report untouched.
 */
@ExtendWith(OutputCaptureExtension.class)
class MissingGeneratedRepositoryFailureAnalyzerTest {

    private static final String NEW_LINE = System.lineSeparator();

    /** The action Spring Boot's own analyzer writes, which must not appear. */
    private static final String GENERIC_ACTION = "Consider defining a bean of type";

    private static final String NO_SINGLE_EXECUTOR = "The sqlcj starter registers generated repositories "
        + "only over a single QueryExecutor bean, ";

    @Test
    void explainsAMissingRepositoryWithoutADataSourceBean(CapturedOutput output) {
        assertThrows(
            UnsatisfiedDependencyException.class,
            () -> new SpringApplicationBuilder(NoDataSourceApplication.class).web(WebApplicationType.NONE).run()
        );

        assertReported(
            output,
            NotesConsumer.class,
            NotesRepository.class,
            NO_SINGLE_EXECUTOR
                + "and there is none: the starter defines its own over the application's DataSource, and "
                + "there is no DataSource bean.",
            "Configure a DataSource, for example with Spring Boot's spring.datasource.* properties, or "
                + "define a QueryExecutor bean. See \"The executor\" in the sqlcj Spring guide."
        );
    }

    @Test
    void explainsAMissingRepositoryWithTwoDataSourceBeansAndNoPrimary(CapturedOutput output) {
        assertThrows(
            UnsatisfiedDependencyException.class,
            () -> new SpringApplicationBuilder(TwoDataSourceApplication.class).web(WebApplicationType.NONE).run()
        );

        assertReported(
            output,
            NotesConsumer.class,
            NotesRepository.class,
            NO_SINGLE_EXECUTOR
                + "and there is none: the starter defines its own only over a single DataSource candidate, and "
                + "none of the DataSource beans 'firstDataSource', 'secondDataSource' is primary.",
            "Mark the DataSource the generated repositories use @Primary, or declare every other DataSource "
                + "with @Bean(defaultCandidate = false). See \"Several Databases\" in the sqlcj Spring guide."
        );
    }

    @Test
    void explainsAMissingRepositoryWithTwoQueryExecutorBeans(CapturedOutput output) {
        assertThrows(
            UnsatisfiedDependencyException.class,
            () -> new SpringApplicationBuilder(TwoExecutorApplication.class).web(WebApplicationType.NONE).run()
        );

        assertReported(
            output,
            NotesConsumer.class,
            NotesRepository.class,
            NO_SINGLE_EXECUTOR + "and there are 2: 'firstQueryExecutor', 'secondQueryExecutor'.",
            "Define a single QueryExecutor bean, or register each generated repository over the QueryExecutor "
                + "it uses, with @Import over the default one or a @Bean method over a qualified one. See "
                + "\"Several Databases\" in the sqlcj Spring guide."
        );
    }

    @Test
    void explainsAMissingRepositoryOutsideTheAutoConfigurationPackages(CapturedOutput output) {
        assertThrows(
            UnsatisfiedDependencyException.class,
            () -> new SpringApplicationBuilder(OutsideRepositoryApplication.class)
                .web(WebApplicationType.NONE)
                .run()
        );

        assertReported(
            output,
            OutsideConsumer.class,
            OutsideRepository.class,
            "The sqlcj starter registers only the generated repositories in the application's "
                + "auto-configuration packages (org.sqlcj.spring.boot.autoconfigure.repositories), and this "
                + "repository's package is org.sqlcj.spring.boot.autoconfigure.outside.",
            "Register the repository with @Import(OutsideRepository.class) on a @Configuration class, or "
                + "generate it under the application's packages by changing java.package in the sqlcj "
                + "configuration. See \"A Generated Package Outside the Application's Packages\" in the sqlcj "
                + "Spring guide."
        );
    }

    @Test
    void leavesSpringBootsReportForAMissingTypeWithoutTheMarker(CapturedOutput output) {
        assertThrows(
            UnsatisfiedDependencyException.class,
            () -> new SpringApplicationBuilder(UnmarkedApplication.class).web(WebApplicationType.NONE).run()
        );

        assertTrue(
            output.getAll()
                .contains(
                    "Consider defining a bean of type "
                        + "'org.sqlcj.spring.boot.autoconfigure.repositories.UnmarkedRepository' "
                        + "in your configuration."
                ),
            () -> "Spring Boot's own action should stand: " + output.getAll()
        );
        assertFalse(
            output.getAll().contains("The sqlcj starter"),
            () -> "the starter should explain nothing of an unmarked type: " + output.getAll()
        );
    }

    /**
     * Asserts that the logged report is the analyzer's description and action
     * for the given injection point and missing repository, and that Spring
     * Boot's generic action is absent.
     */
    private static void assertReported(
        CapturedOutput output,
        Class<?> consumer,
        Class<?> repository,
        String reason,
        String action
    ) {
        String report = "Description:" + NEW_LINE + NEW_LINE
            + "Parameter 0 of constructor in " + consumer.getName() + " required a bean of type '"
            + repository.getName() + "' that could not be found." + NEW_LINE + NEW_LINE
            + reason + NEW_LINE + NEW_LINE
            + "Action:" + NEW_LINE + NEW_LINE
            + action + NEW_LINE;

        assertTrue(output.getAll().contains(report), () -> "expected report:" + NEW_LINE + report);
        assertFalse(
            output.getAll().contains(GENERIC_ACTION),
            () -> "the starter's report should replace Spring Boot's: " + output.getAll()
        );
    }

    private static DataSource embeddedDataSource() {
        return new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    }

    /** An application with the starter alone, so it has no {@code DataSource}. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = NotesRepository.class)
    @ImportAutoConfiguration(SqlcjAutoConfiguration.class)
    @Import(NotesConsumer.class)
    static class NoDataSourceApplication {
    }

    /** An application with two data sources of which none is primary. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = NotesRepository.class)
    @ImportAutoConfiguration({ DataSourceAutoConfiguration.class, SqlcjAutoConfiguration.class })
    @Import(NotesConsumer.class)
    static class TwoDataSourceApplication {

        @Bean
        DataSource firstDataSource() {
            return embeddedDataSource();
        }

        @Bean
        DataSource secondDataSource() {
            return embeddedDataSource();
        }
    }

    /** An application with two executors of its own. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = NotesRepository.class)
    @ImportAutoConfiguration({ DataSourceAutoConfiguration.class, SqlcjAutoConfiguration.class })
    @Import(NotesConsumer.class)
    static class TwoExecutorApplication {

        @Bean
        QueryExecutor firstQueryExecutor(DataSource dataSource) {
            return new JdbcQueryExecutor(dataSource);
        }

        @Bean
        QueryExecutor secondQueryExecutor(DataSource dataSource) {
            return new JdbcQueryExecutor(dataSource);
        }
    }

    /**
     * An application that asks for a marked repository of a package outside its
     * auto-configuration packages, which the starter leaves to {@code @Import}.
     */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = NotesRepository.class)
    @ImportAutoConfiguration({ DataSourceAutoConfiguration.class, SqlcjAutoConfiguration.class })
    @Import(OutsideConsumer.class)
    static class OutsideRepositoryApplication {
    }

    /** An application that asks for a hand-written class without the marker. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = NotesRepository.class)
    @ImportAutoConfiguration({ DataSourceAutoConfiguration.class, SqlcjAutoConfiguration.class })
    @Import(UnmarkedConsumer.class)
    static class UnmarkedApplication {
    }

    /** The component whose constructor argument makes the startup fail. */
    static class NotesConsumer {

        NotesConsumer(NotesRepository notes) {
        }
    }

    static class OutsideConsumer {

        OutsideConsumer(OutsideRepository outside) {
        }
    }

    static class UnmarkedConsumer {

        UnmarkedConsumer(UnmarkedRepository unmarked) {
        }
    }
}
