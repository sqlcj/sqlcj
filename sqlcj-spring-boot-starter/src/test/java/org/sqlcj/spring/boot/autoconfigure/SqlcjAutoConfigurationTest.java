package org.sqlcj.spring.boot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;
import org.sqlcj.spring.boot.autoconfigure.outside.OutsideRepository;
import org.sqlcj.spring.boot.autoconfigure.repositories.NotesRepository;
import org.sqlcj.spring.boot.autoconfigure.repositories.UnmarkedRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the starter's auto-configuration contributes exactly one
 * {@link QueryExecutor} over the application's single {@link DataSource}
 * candidate, that the executor takes part in a transaction Spring manages,
 * that the auto-configuration backs off from an application's own executor,
 * that it registers the generated repositories of the auto-configuration
 * packages over the one executor and nothing else, and that an application
 * reaches it through the imports file and turns it off with
 * {@code spring.autoconfigure.exclude}.
 *
 * <p>Two tests cover the configurations the Spring guide documents beside the
 * auto-configured one: a repository of a package outside the
 * auto-configuration packages registered with {@code @Import}, and an
 * application with a second, non-default-candidate database whose repository,
 * executor, and transaction manager are its own.
 *
 * <p>Every context runs on its own in-memory H2 database, so no test leaves
 * state behind.
 */
class SqlcjAutoConfigurationTest {

    /** The one table the transaction tests write to and read back. */
    private static final String CREATE_NOTES = "CREATE TABLE notes (id INT PRIMARY KEY)";

    private static final String INSERT_NOTE = "INSERT INTO notes (id) VALUES (?)";

    private static final String SELECT_NOTE_IDS = "SELECT id FROM notes ORDER BY id";

    /** The repository and query names a generated repository would pass. */
    private static final String REPOSITORY = "NotesRepository";

    private static final String QUERY = "InsertNote";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(DataSourceAutoConfiguration.class, SqlcjAutoConfiguration.class)
        );

    private final ApplicationContextRunner transactionalRunner = new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                SqlcjAutoConfiguration.class
            )
        );

    /** The transactional runner with {@code @Transactional} enabled on top. */
    private final ApplicationContextRunner annotatedTransactionRunner = new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                TransactionAutoConfiguration.class,
                SqlcjAutoConfiguration.class
            )
        );

    @Test
    void configuresOneJdbcExecutorOverTheSingleDataSource() {
        runner.run(context -> {
            assertEquals(1, context.getBeansOfType(QueryExecutor.class).size());
            assertInstanceOf(JdbcQueryExecutor.class, context.getBean(QueryExecutor.class));
            assertEquals(1, context.getBeansOfType(DataSource.class).size());
        });
    }

    @Test
    void configuresOneExecutorWhenOneOfTwoDataSourcesIsPrimary() {
        runner.withUserConfiguration(PrimaryDataSourceConfiguration.class).run(context -> {
            assertEquals(2, context.getBeansOfType(DataSource.class).size());
            assertEquals(1, context.getBeansOfType(QueryExecutor.class).size());
        });
    }

    @Test
    void configuresNoExecutorWhenTwoDataSourcesHaveNoPrimary() {
        runner.withUserConfiguration(TwoDataSourceConfiguration.class).run(context -> {
            assertEquals(2, context.getBeansOfType(DataSource.class).size());
            assertTrue(context.getBeansOfType(QueryExecutor.class).isEmpty());
        });
    }

    @Test
    void startsWithoutADataSourceAndConfiguresNoExecutor() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SqlcjAutoConfiguration.class))
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
                assertTrue(context.getBeansOfType(QueryExecutor.class).isEmpty());
            });
    }

    @Test
    void backsOffFromAnApplicationExecutor() {
        runner.withUserConfiguration(ApplicationExecutorConfiguration.class)
            .run(
                context -> assertArrayEquals(
                    new String[] { "applicationQueryExecutor" },
                    context.getBeanNamesForType(QueryExecutor.class)
                )
            );
    }

    @Test
    void registersAMarkedRepositoryAsOneSingletonOverTheConfiguredExecutor() {
        runner.withUserConfiguration(RepositoryPackageConfiguration.class).run(context -> {
            String name = NotesRepository.class.getName();
            assertArrayEquals(new String[] { name }, context.getBeanNamesForType(NotesRepository.class));
            assertSame(
                context.getBean(QueryExecutor.class),
                context.getBean(NotesRepository.class).executor()
            );
            assertTrue(context.isSingleton(name));
        });
    }

    @Test
    void registersNoUnmarkedRepository() {
        runner.withUserConfiguration(RepositoryPackageConfiguration.class)
            .run(context -> assertTrue(context.getBeansOfType(UnmarkedRepository.class).isEmpty()));
    }

    @Test
    void registersNoMarkedRepositoryOutsideTheAutoConfigurationPackages() {
        runner.withUserConfiguration(RepositoryPackageConfiguration.class)
            .run(context -> assertTrue(context.getBeansOfType(OutsideRepository.class).isEmpty()));
    }

    @Test
    void importsOneRepositoryOutsideTheAutoConfigurationPackagesOverTheConfiguredExecutor() {
        runner.withUserConfiguration(
            RepositoryPackageConfiguration.class,
            ImportedOutsideRepositoryConfiguration.class
        )
            .run(context -> {
                assertArrayEquals(
                    new String[] { OutsideRepository.class.getName() },
                    context.getBeanNamesForType(OutsideRepository.class)
                );
                assertSame(
                    context.getBean(QueryExecutor.class),
                    context.getBean(OutsideRepository.class).executor()
                );
            });
    }

    @Test
    void registersARepositoryOverAnApplicationExecutorWithoutADataSourceAutoConfiguration() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SqlcjAutoConfiguration.class))
            .withUserConfiguration(
                RepositoryPackageConfiguration.class,
                ApplicationExecutorConfiguration.class
            )
            .run(context -> {
                assertArrayEquals(
                    new String[] { "applicationQueryExecutor" },
                    context.getBeanNamesForType(QueryExecutor.class)
                );
                assertSame(
                    context.getBean(QueryExecutor.class),
                    context.getBean(NotesRepository.class).executor()
                );
            });
    }

    @Test
    void registersNoRepositoryTheApplicationAlreadyDefines() {
        runner.withUserConfiguration(
            RepositoryPackageConfiguration.class,
            ApplicationRepositoryConfiguration.class
        )
            .run(
                context -> assertArrayEquals(
                    new String[] { "applicationNotesRepository" },
                    context.getBeanNamesForType(NotesRepository.class)
                )
            );
    }

    @Test
    void registersNoRepositoryWithoutAnExecutor() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SqlcjAutoConfiguration.class))
            .withUserConfiguration(RepositoryPackageConfiguration.class)
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(QueryExecutor.class).isEmpty());
                assertTrue(context.getBeansOfType(NotesRepository.class).isEmpty());
            });
    }

    @Test
    void registersNoRepositoryWithTwoExecutorsOfWhichOneIsPrimary() {
        runner.withUserConfiguration(
            RepositoryPackageConfiguration.class,
            PrimaryExecutorConfiguration.class
        )
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(2, context.getBeansOfType(QueryExecutor.class).size());
                assertTrue(context.getBeansOfType(NotesRepository.class).isEmpty());
            });
    }

    @Test
    void registersNoRepositoryWithoutAnAutoConfigurationPackage() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(QueryExecutor.class).size());
            assertTrue(context.getBeansOfType(NotesRepository.class).isEmpty());
        });
    }

    @Test
    void executorWriteIsRolledBackWithTheSpringTransaction() {
        transactionalRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            createNotes(dataSource);
            QueryExecutor executor = context.getBean(QueryExecutor.class);
            TransactionTemplate transaction = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class)
            );
            transaction.executeWithoutResult(status -> {
                executor.execute(REPOSITORY, QUERY, INSERT_NOTE, List.of(1));
                status.setRollbackOnly();
            });
            assertEquals(List.of(), noteIds(dataSource));
        });
    }

    @Test
    void executorWriteOutsideATransactionIsCommitted() {
        transactionalRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            createNotes(dataSource);
            context.getBean(QueryExecutor.class).execute(REPOSITORY, QUERY, INSERT_NOTE, List.of(2));
            assertEquals(List.of(2), noteIds(dataSource));
        });
    }

    @Test
    void keepsEachDatabaseOfTheSeveralDatabasesConfigurationToItsOwnTransaction() {
        annotatedTransactionRunner.withUserConfiguration(SeveralDatabasesConfiguration.class).run(context -> {
            DataSource first = context.getBean(DataSource.class);
            DataSource second = context.getBean("secondDataSource", DataSource.class);
            createNotes(first);
            createNotes(second);

            QueryExecutor starterExecutor = context.getBean("sqlcjQueryExecutor", QueryExecutor.class);
            QueryExecutor secondExecutor = context.getBean("secondQueryExecutor", QueryExecutor.class);

            assertArrayEquals(
                new String[] { NotesRepository.class.getName() },
                context.getBeanNamesForType(NotesRepository.class)
            );
            assertArrayEquals(
                new String[] { "secondOutsideRepository" },
                context.getBeanNamesForType(OutsideRepository.class)
            );
            assertSame(starterExecutor, context.getBean(NotesRepository.class).executor());
            assertSame(secondExecutor, context.getBean(OutsideRepository.class).executor());

            SeveralDatabasesService service = context.getBean(SeveralDatabasesService.class);

            service.writeToFirst(1);

            assertEquals(List.of(1), noteIds(first));
            assertEquals(List.of(), noteIds(second));

            service.writeToSecond(2);

            assertEquals(List.of(1), noteIds(first));
            assertEquals(List.of(2), noteIds(second));

            assertThrows(IllegalStateException.class, () -> service.writeToFirstAndFail(3));

            assertEquals(List.of(1), noteIds(first));
            assertEquals(List.of(2), noteIds(second));

            assertThrows(IllegalStateException.class, () -> service.writeToSecondAndFail(4));

            assertEquals(List.of(1), noteIds(first));
            assertEquals(List.of(2), noteIds(second));
        });
    }

    @Test
    void anApplicationReachesTheAutoConfigurationThroughTheImportsFile() {
        new ApplicationContextRunner().withUserConfiguration(AutoConfiguredApplication.class).run(context -> {
            assertEquals(1, context.getBeansOfType(QueryExecutor.class).size());
            assertEquals(1, context.getBeansOfType(NotesRepository.class).size());
        });
    }

    @Test
    void theExclusionPropertyTurnsTheAutoConfigurationOff() {
        new ApplicationContextRunner()
            .withUserConfiguration(AutoConfiguredApplication.class)
            .withPropertyValues(
                "spring.autoconfigure.exclude=org.sqlcj.spring.boot.autoconfigure.SqlcjAutoConfiguration"
            )
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(QueryExecutor.class).isEmpty());
                assertTrue(context.getBeansOfType(NotesRepository.class).isEmpty());
            });
    }

    private static void createNotes(DataSource dataSource) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement()
        ) {
            statement.execute(CREATE_NOTES);
        }
    }

    /** Reads the table through the plain data source, outside any transaction. */
    private static List<Integer> noteIds(DataSource dataSource) throws SQLException {
        try (
            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(SELECT_NOTE_IDS)
        ) {
            List<Integer> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
            return ids;
        }
    }

    private static DataSource embeddedDataSource() {
        return new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build();
    }

    /** An application that only enables auto-configuration. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfiguredApplication {
    }

    /** Two application data sources, of which one is primary. */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryDataSourceConfiguration {

        @Bean
        @Primary
        DataSource firstDataSource() {
            return embeddedDataSource();
        }

        @Bean
        DataSource secondDataSource() {
            return embeddedDataSource();
        }
    }

    /** Two application data sources, neither of them primary. */
    @Configuration(proxyBeanMethods = false)
    static class TwoDataSourceConfiguration {

        @Bean
        DataSource firstDataSource() {
            return embeddedDataSource();
        }

        @Bean
        DataSource secondDataSource() {
            return embeddedDataSource();
        }
    }

    /** An application that defines both a data source and its own executor. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationExecutorConfiguration {

        @Bean
        DataSource dataSource() {
            return embeddedDataSource();
        }

        @Bean
        QueryExecutor applicationQueryExecutor(DataSource dataSource) {
            return new JdbcQueryExecutor(dataSource);
        }
    }

    /**
     * An application whose auto-configuration package is the one holding the
     * fixture repositories.
     */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = NotesRepository.class)
    static class RepositoryPackageConfiguration {
    }

    /** An application that declares a generated repository itself. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationRepositoryConfiguration {

        @Bean
        NotesRepository applicationNotesRepository(QueryExecutor executor) {
            return new NotesRepository(executor);
        }
    }

    /** Two application executors, of which one is primary. */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryExecutorConfiguration {

        @Bean
        @Primary
        QueryExecutor firstQueryExecutor(DataSource dataSource) {
            return new JdbcQueryExecutor(dataSource);
        }

        @Bean
        QueryExecutor secondQueryExecutor(DataSource dataSource) {
            return new JdbcQueryExecutor(dataSource);
        }
    }

    /**
     * An application that imports a generated repository of a package outside
     * its auto-configuration packages, as an application whose generated
     * package sits beside its own packages has to.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(OutsideRepository.class)
    static class ImportedOutsideRepositoryConfiguration {
    }

    /**
     * The several-databases configuration of the Spring guide: the
     * auto-configured first database keeps the starter's executor and Spring
     * Boot's transaction manager, while the second database's data source,
     * executor, and transaction manager are non-default candidates qualified
     * {@code second}. Two executor definitions leave the starter registering
     * no repository, so the repository of the auto-configuration package is
     * imported and the second database's is declared over its own executor.
     */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage(basePackageClasses = NotesRepository.class)
    @Import(NotesRepository.class)
    static class SeveralDatabasesConfiguration {

        @Bean(defaultCandidate = false)
        @Qualifier("second")
        DataSource secondDataSource() {
            return embeddedDataSource();
        }

        @Bean(defaultCandidate = false)
        @Qualifier("second")
        QueryExecutor secondQueryExecutor(
            @Qualifier("second") DataSource dataSource
        ) {
            return new JdbcQueryExecutor(new TransactionAwareDataSourceProxy(dataSource));
        }

        @Bean(defaultCandidate = false)
        @Qualifier("second")
        JdbcTransactionManager secondTransactionManager(
            @Qualifier("second") DataSource dataSource
        ) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        OutsideRepository secondOutsideRepository(
            @Qualifier("second") QueryExecutor executor
        ) {
            return new OutsideRepository(executor);
        }

        @Bean
        SeveralDatabasesService severalDatabasesService(NotesRepository first, OutsideRepository second) {
            return new SeveralDatabasesService(first, second);
        }
    }

    /**
     * An application service that writes through the repository of one
     * database per method, inside the transaction of that database.
     *
     * <p>The class and its methods are public and non-final so that the
     * container can proxy them.
     */
    public static class SeveralDatabasesService {

        private final NotesRepository first;

        private final OutsideRepository second;

        SeveralDatabasesService(NotesRepository first, OutsideRepository second) {
            this.first = first;
            this.second = second;
        }

        @Transactional
        public void writeToFirst(int id) {
            first.executor().execute(REPOSITORY, QUERY, INSERT_NOTE, List.of(id));
        }

        @Transactional("second")
        public void writeToSecond(int id) {
            second.executor().execute(REPOSITORY, QUERY, INSERT_NOTE, List.of(id));
        }

        @Transactional
        public void writeToFirstAndFail(int id) {
            first.executor().execute(REPOSITORY, QUERY, INSERT_NOTE, List.of(id));

            throw new IllegalStateException("the first database's transaction is rolled back");
        }

        @Transactional("second")
        public void writeToSecondAndFail(int id) {
            second.executor().execute(REPOSITORY, QUERY, INSERT_NOTE, List.of(id));

            throw new IllegalStateException("the second database's transaction is rolled back");
        }
    }
}
