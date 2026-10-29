package org.sqlcj.spring.boot.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.QueryExecutor;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the starter's auto-configuration contributes exactly one
 * {@link QueryExecutor} over the application's single {@link DataSource}
 * candidate, that the executor takes part in a transaction Spring manages,
 * that the auto-configuration backs off from an application's own executor,
 * and that an application reaches it through the imports file and turns it off
 * with {@code spring.autoconfigure.exclude}.
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
    void anApplicationReachesTheAutoConfigurationThroughTheImportsFile() {
        new ApplicationContextRunner()
            .withUserConfiguration(AutoConfiguredApplication.class)
            .run(context -> assertEquals(1, context.getBeansOfType(QueryExecutor.class).size()));
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
}
