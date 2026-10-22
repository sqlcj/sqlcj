package org.sqlcj.compiler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;
import org.sqlcj.runtime.JdbcQueryExecutor;
import org.sqlcj.runtime.SqlInterval;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the milestone acceptance on the reference service: its own Flyway
 * migration history is applied to a real PostgreSQL 16 server, that server's
 * {@code pg_dump --schema-only} snapshot is taken, and the history, the
 * hand-written snapshot, and the dump generate one byte-identical repository
 * tree that compiles and runs against the migrated database.
 *
 * <p>The whole class is skipped when Docker is unavailable, so that
 * {@code mvn test} stays runnable without Docker. Setting
 * {@code -Dsqlcj.test.requireDocker=true}, as continuous integration does,
 * turns an unavailable Docker into a failure instead of a skip.
 */
@EnabledIf("dockerAvailable")
class ReferenceServiceIntegrationTest {

    /**
     * System property that forbids skipping these tests. Continuous
     * integration sets it so that a broken Docker environment fails the build
     * instead of silently reducing coverage.
     */
    private static final String REQUIRE_DOCKER_PROPERTY = "sqlcj.test.requireDocker";

    /** The configured query-group name, which generates {@code TrackerRepository}. */
    private static final String GROUP = "Tracker";

    /** The migration directory inside the database container. */
    private static final String CONTAINER_MIGRATIONS = "/tmp/reference-service-migrations";

    /** The reference service's migrations, in Flyway version order. */
    private static final List<String> MIGRATIONS = List.of(
        "V1__organizations_and_users.sql",
        "V2__projects_and_memberships.sql",
        "V3__tasks.sql",
        "V4__task_constraints.sql",
        "V5__comments_time_entries_and_audit_log.sql",
        "V6__views_and_reporting.sql",
        "V7__roles_and_row_level_security.sql",
        "V8__enum_and_column_changes.sql",
        "V9__status_index.sql",
        "V10__seed_and_view_refresh.sql",
        "V11__assignees_and_soft_deleted_users.sql"
    );

    /**
     * The unsupported query catalogue: one query file per shape the supported
     * subset excludes, with the query name and the reason sqlcj reports for it.
     */
    private static final List<UnsupportedQuery> UNSUPPORTED_QUERIES = List.of(
        new UnsupportedQuery(
            "data_modifying_cte.sql",
            "DeleteDoneTasks",
            "A data-modifying statement in WITH is not supported; "
                + "run it as a query of its own."
        ),
        new UnsupportedQuery(
            "uncast_lower.sql",
            "FindUserByEmail",
            "Cannot infer the type of :email in lower(:email); "
                + "state it with a cast, such as :email::text"
        ),
        new UnsupportedQuery(
            "subscripted_column.sql",
            "ListTasksByFirstLabel",
            "Cannot infer the type of :label in labels[1] = :label; "
                + "state it with a cast, such as :label::text"
        )
    );

    /** The organizations the join kind test owns inside the shared database. */
    private static final List<String> JOIN_ORGANIZATIONS = List.of(
        "Join Test One",
        "Join Test Two"
    );

    /**
     * The projects the join kind test owns, the first of which has one task and
     * the second none.
     */
    private static final List<String> JOIN_PROJECTS = List.of(
        "Join Test Tasked",
        "Join Test Empty"
    );

    /** The files the three schema inputs generate, relative to {@code java.out}. */
    private static final Set<String> GENERATED_FILES = Set.of(
        "sqlcj-manifest.txt",
        "generated/TrackerRepository.java",
        "generated/TasksRow.java",
        "generated/ProjectsRow.java",
        "generated/CommentsRow.java",
        "generated/AuditLog2026Row.java",
        "generated/AuditLogArchiveRow.java",
        "generated/TaskStatus.java",
        "generated/MembershipRole.java"
    );

    private static PostgreSQLContainer<?> postgres;

    private static DataSource dataSource;

    /** The directory that receives the {@code pg_dump --schema-only} snapshot. */
    @TempDir
    static Path dumpDirectory;

    /** The {@code pg_dump --schema-only} snapshot of the migrated database. */
    private static Path dumpFile;

    @TempDir
    Path tempDir;

    static boolean dockerAvailable() {
        boolean available = DockerClientFactory.instance().isDockerAvailable();

        if (!available && Boolean.getBoolean(REQUIRE_DOCKER_PROPERTY)) {
            throw new IllegalStateException(
                "Docker is unavailable, but " + REQUIRE_DOCKER_PROPERTY + " requires these tests to run."
            );
        }

        return available;
    }

    /**
     * Applies the reference service's migrations in Flyway version order with
     * {@code psql}, then dumps the schema of the migrated database.
     */
    @BeforeAll
    static void migrateAndDump() throws Exception {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine")
        );

        postgres.start();

        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();

        postgresDataSource.setUrl(postgres.getJdbcUrl());
        postgresDataSource.setUser(postgres.getUsername());
        postgresDataSource.setPassword(postgres.getPassword());

        dataSource = postgresDataSource;

        assertEquals(
            MIGRATIONS,
            migrationFileNames(),
            "the migration directory holds exactly the applied migrations"
        );

        run("mkdir", "-p", CONTAINER_MIGRATIONS);

        for (String migration : MIGRATIONS) {
            postgres.copyFileToContainer(
                MountableFile.forHostPath(migrations().resolve(migration)),
                CONTAINER_MIGRATIONS + "/" + migration
            );

            run(
                "psql",
                "-X",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-f",
                CONTAINER_MIGRATIONS + "/" + migration
            );
        }

        Container.ExecResult dump = postgres.execInContainer(
            "pg_dump",
            "--schema-only",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName()
        );

        assertEquals(0, dump.getExitCode(), dump.getStderr());

        dumpFile = dumpDirectory.resolve("dump.sql");

        Files.writeString(dumpFile, dump.getStdout());
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * The migration history, the hand-written snapshot, and the dump of the
     * migrated database are three spellings of one schema, so each generates
     * the same files with the same bytes, and that output compiles.
     */
    @Test
    void shouldGenerateIdenticalRepositoriesFromHistorySnapshotAndDump() throws Exception {
        Path fromHistory = tempDir.resolve("from-history");
        Path fromSnapshot = tempDir.resolve("from-snapshot");
        Path fromDump = tempDir.resolve("from-dump");

        generate(List.of(migrations().toString()), fromHistory);
        generate(List.of(snapshot().toString()), fromSnapshot);
        generate(List.of(dumpFile.toString()), fromDump);

        Map<String, byte[]> historyFiles = generatedFiles(fromHistory);

        assertEquals(GENERATED_FILES, historyFiles.keySet());

        assertIdentical(historyFiles, generatedFiles(fromSnapshot));
        assertIdentical(historyFiles, generatedFiles(fromDump));

        compile(fromHistory, tempDir.resolve("classes-history"));
    }

    /**
     * The generated repository executes the reference service's queries
     * against the migrated database, including the replaced membership role,
     * the renamed task status, the optimistic update, and the audit partition
     * the {@code LIKE} copy does not follow.
     */
    @Test
    void shouldExecuteTheGeneratedReferenceServiceQueries() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-runtime");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-runtime"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Object organization = invoke(
                repository,
                "createOrganization",
                "Tracker Test",
                "{\"theme\": \"dark\"}"
            );

            Long organizationId = (Long) component(organization, "id");

            Object user = invoke(
                repository,
                "createUser",
                organizationId,
                "grace@example.com",
                "Grace Hopper"
            );

            Long userId = (Long) component(user, "id");

            Object maintainer = enumConstant(classLoader, "MembershipRole", "MAINTAINER");

            assertEquals(1, invoke(repository, "addMember", organizationId, userId, maintainer));

            List<?> members = (List<?>) invoke(repository, "listMembers", organizationId);

            assertEquals(1, members.size());

            assertEquals("Grace Hopper", component(members.get(0), "displayName"));

            assertEquals(maintainer, component(members.get(0), "role"));

            Object project = invoke(
                repository,
                "createProject",
                organizationId,
                "TRACK",
                "Tracker"
            );

            Long projectId = (Long) component(project, "id");

            assertEquals(
                "ProjectsRow",
                project.getClass().getSimpleName(),
                "a RETURNING * write returns the shared row record"
            );

            assertEquals(
                Optional.of(project),
                invoke(repository, "getProject", projectId)
            );

            assertEquals(
                Optional.empty(),
                invoke(repository, "getProject", projectId + 1_000L),
                "an optional read of a missing project is empty"
            );

            Object open = enumConstant(classLoader, "TaskStatus", "OPEN");
            Object doing = enumConstant(classLoader, "TaskStatus", "DOING");
            Object blocked = enumConstant(classLoader, "TaskStatus", "BLOCKED");

            Object task = invoke(
                repository,
                "createTask",
                projectId,
                "Write the acceptance test",
                open,
                (short) 1,
                List.of("test", "sqlcj"),
                "{\"source\": \"fixture\"}"
            );

            Long taskId = (Long) component(task, "id");

            assertEquals(open, component(task, "status"));

            assertEquals(1, invoke(repository, "moveTask", doing, taskId, 1));

            List<?> doingTasks = (List<?>) invoke(
                repository,
                "listProjectTasks",
                projectId,
                List.of(doing),
                10
            );

            assertEquals(1, doingTasks.size());

            assertEquals(taskId, component(doingTasks.get(0), "id"));

            assertEquals(doing, component(doingTasks.get(0), "status"));

            assertEquals(
                List.of(),
                invoke(repository, "listProjectTasks", projectId, List.of(blocked), 10),
                "the renamed status filters the list"
            );

            List<?> counts = (List<?>) invoke(repository, "countTasksByStatus", projectId);

            assertEquals(1, counts.size());

            assertEquals(doing, component(counts.get(0), "status"));

            assertEquals(1L, component(counts.get(0), "total"));

            Object comment = invoke(repository, "addComment", taskId, userId, "Looks right.");

            assertEquals("Looks right.", component(comment, "body"));

            UUID requestId = UUID.randomUUID();

            OffsetDateTime occurredAt = OffsetDateTime.of(
                2026,
                6,
                1,
                9,
                30,
                0,
                0,
                ZoneOffset.UTC
            );

            assertEquals(
                1,
                invoke(
                    repository,
                    "recordAudit",
                    occurredAt,
                    userId,
                    "task.moved",
                    "{\"to\": \"doing\"}",
                    requestId
                )
            );

            List<?> audit = (List<?>) invoke(repository, "listAudit2026");

            assertEquals(1, audit.size());

            assertEquals("task.moved", component(audit.get(0), "action"));

            assertEquals(
                requestId,
                component(audit.get(0), "requestId"),
                "the partition follows the column its partitioned table gained"
            );

            assertEquals(
                List.of("id", "occurredAt", "actorId", "action", "detail"),
                recordComponentNames(
                    Class.forName("generated.AuditLogArchiveRow", true, classLoader)
                ),
                "the LIKE copy does not follow the column the audit log gained"
            );

            assertEquals(List.of(), invoke(repository, "listArchivedAudit"));
        }
    }

    /**
     * The generated repository executes the query shapes this milestone adds
     * against the migrated database: the dynamic sort and the cast page, the
     * {@code EXISTS}, {@code IN (SELECT ...)}, and scalar subqueries, the
     * composite-key join, the filtered left join, the conflict clauses that
     * bind nothing, and the aliased {@code RETURNING} of an optimistic update.
     *
     * <p>The database is shared with the other tests of this class, so this one
     * works inside its own organization, project, and users, and writes no
     * audit entry.
     */
    @Test
    void shouldExecuteTheGeneratedMilestoneQueryShapes() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-shapes");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-shapes"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Long organizationId = (Long) component(
                invoke(repository, "createOrganization", "Shape Test", "{}"),
                "id"
            );

            Long assigneeId = (Long) component(
                invoke(repository, "createUser", organizationId, "lin@example.com", "Lin Shu"),
                "id"
            );

            Long retiredId = (Long) component(
                invoke(repository, "createUser", organizationId, "kurt@example.com", "Kurt Godel"),
                "id"
            );

            Object project = invoke(repository, "createProject", organizationId, "SHAPE", "Shapes");

            Long projectId = (Long) component(project, "id");

            Object open = enumConstant(classLoader, "TaskStatus", "OPEN");
            Object maintainer = enumConstant(classLoader, "MembershipRole", "MAINTAINER");

            Long betaId = (Long) component(
                createShapeTask(repository, projectId, "Beta", (short) 3, open),
                "id"
            );

            Long alphaId = (Long) component(
                createShapeTask(repository, projectId, "Alpha", (short) 1, open),
                "id"
            );

            Long gammaId = (Long) component(
                createShapeTask(repository, projectId, "Gamma", (short) 2, open),
                "id"
            );

            assertEquals(
                List.of("Alpha", "Beta", "Gamma"),
                componentsOf(
                    (List<?>) invoke(repository, "listTasksPage", projectId, "title", 3, 0),
                    "title"
                ),
                "the dynamic sort orders by title"
            );

            assertEquals(
                List.of("Alpha", "Gamma", "Beta"),
                componentsOf(
                    (List<?>) invoke(repository, "listTasksPage", projectId, "priority", 3, 0),
                    "title"
                ),
                "the same query orders by priority"
            );

            assertEquals(
                List.of("Beta", "Gamma"),
                componentsOf(
                    (List<?>) invoke(repository, "listTasksPage", projectId, "title", 2, 1),
                    "title"
                ),
                "the cast page skips its offset"
            );

            assertEquals(
                1,
                invoke(repository, "joinOrganization", organizationId, assigneeId),
                "the untargeted ON CONFLICT DO NOTHING inserts the first membership"
            );

            assertEquals(
                0,
                invoke(repository, "joinOrganization", organizationId, assigneeId),
                "the untargeted ON CONFLICT DO NOTHING keeps the existing membership"
            );

            assertEquals(
                1,
                invoke(repository, "changeMemberRole", organizationId, assigneeId, maintainer),
                "ON CONFLICT ON CONSTRAINT updates the changed role"
            );

            assertEquals(
                0,
                invoke(repository, "changeMemberRole", organizationId, assigneeId, maintainer),
                "DO UPDATE ... WHERE skips the unchanged role"
            );

            List<?> visibleProjects = (List<?>) invoke(
                repository,
                "listVisibleProjects",
                assigneeId
            );

            assertEquals(1, visibleProjects.size(), "EXISTS keeps only the member's projects");

            assertEquals(projectId, component(visibleProjects.get(0), "id"));

            assertEquals("SHAPE", component(visibleProjects.get(0), "code"));

            assertEquals(
                List.of("Beta", "Alpha", "Gamma"),
                componentsOf(
                    (List<?>) invoke(repository, "listOrganizationTasks", organizationId),
                    "title"
                ),
                "IN (SELECT ...) keeps the tasks of the organization's projects"
            );

            assertNotNull(invoke(repository, "addComment", alphaId, assigneeId, "First."));

            assertNotNull(invoke(repository, "addComment", alphaId, assigneeId, "Second."));

            OffsetDateTime beforeTheComments = OffsetDateTime.of(
                2026,
                1,
                1,
                0,
                0,
                0,
                0,
                ZoneOffset.UTC
            );

            List<?> recentComments = (List<?>) invoke(
                repository,
                "listTasksWithRecentComments",
                beforeTheComments,
                projectId
            );

            assertEquals(
                List.of(betaId, alphaId, gammaId),
                componentsOf(recentComments, "id")
            );

            assertEquals(
                List.of(0L, 2L, 0L),
                componentsOf(recentComments, "recentComments"),
                "the scalar subquery counts each task's recent comments"
            );

            assertEquals(
                List.of(0L, 0L, 0L),
                componentsOf(
                    (List<?>) invoke(
                        repository,
                        "listTasksWithRecentComments",
                        beforeTheComments.plusYears(100),
                        projectId
                    ),
                    "recentComments"
                ),
                "the scalar subquery binds its own cast placeholder"
            );

            Object assignment = ((Optional<?>) invoke(
                repository,
                "assignTask",
                assigneeId,
                alphaId,
                1
            )).orElseThrow();

            assertEquals(
                alphaId,
                component(assignment, "taskId"),
                "the aliased RETURNING names its own components"
            );

            assertEquals(2, component(assignment, "newVersion"));

            assertEquals(
                Optional.empty(),
                invoke(repository, "assignTask", assigneeId, alphaId, 1),
                "an optimistic update of a stale version returns no row"
            );

            assertEquals(
                2,
                component(
                    ((Optional<?>) invoke(repository, "assignTask", retiredId, betaId, 1))
                        .orElseThrow(),
                    "newVersion"
                )
            );

            assertEquals(
                1,
                invoke(repository, "softDeleteUser", retiredId),
                "the soft delete marks the assignee deleted"
            );

            List<?> assignedTasks = (List<?>) invoke(repository, "listAssignedTasks", projectId);

            assertEquals(
                1,
                assignedTasks.size(),
                "the composite-key join keeps only tasks assigned to a member"
            );

            assertEquals(alphaId, component(assignedTasks.get(0), "id"));

            assertEquals(
                maintainer,
                component(assignedTasks.get(0), "role"),
                "the composite-key join reads the membership role"
            );

            List<?> withAssignee = (List<?>) invoke(repository, "listTasksWithAssignee", projectId);

            assertEquals(
                List.of(betaId, alphaId, gammaId),
                componentsOf(withAssignee, "id")
            );

            assertEquals(
                Arrays.asList(null, "Lin Shu", null),
                componentsOf(withAssignee, "assigneeName"),
                "the filtered left join names the live assignee and no other"
            );

            Object claimed = ((Optional<?>) invoke(
                repository,
                "claimHandle",
                organizationId,
                "ida@example.com",
                "ida",
                "Ida Rhodes"
            )).orElseThrow();

            assertNotNull(component(claimed, "id"));

            assertEquals(
                "Ida Rhodes",
                component(claimed, "displayName"),
                "the conflict-target predicate claims a free handle"
            );

            assertEquals(
                Optional.empty(),
                invoke(
                    repository,
                    "claimHandle",
                    organizationId,
                    "ida.rhodes@example.com",
                    "ida",
                    "Ida Rhodes"
                ),
                "the conflict-target predicate leaves a live handle to its owner"
            );
        }
    }

    /**
     * The generated repository executes the nested-query and multi-row write
     * shapes this milestone adds against the migrated database: the
     * {@code NOT EXISTS} check, the CTE report, the aliased subquery source
     * with a {@code LIMIT} placeholder, the multi-row insert, the
     * {@code unnest} bulk insert, the {@code INSERT ... SELECT} from a table,
     * the joined update returning the shared task row, and the {@code USING}
     * delete.
     *
     * <p>The database is shared with the other tests of this class, so this one
     * works inside its own organization, projects, and users, and writes no
     * audit entry.
     */
    @Test
    void shouldExecuteTheGeneratedNestedQueriesAndMultiRowWrites() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-nested");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-nested"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Long organizationId = (Long) component(
                invoke(repository, "createOrganization", "Nest Test", "{}"),
                "id"
            );

            Long authorId = (Long) component(
                invoke(repository, "createUser", organizationId, "edsger@example.com", "Edsger Dijkstra"),
                "id"
            );

            Long otherAuthorId = (Long) component(
                invoke(repository, "createUser", organizationId, "barbara@example.com", "Barbara Liskov"),
                "id"
            );

            Long nestedProjectId = (Long) component(
                invoke(repository, "createProject", organizationId, "NEST", "Nested"),
                "id"
            );

            Long copyProjectId = (Long) component(
                invoke(repository, "createProject", organizationId, "COPY", "Copies"),
                "id"
            );

            assertEquals(
                2,
                invoke(repository, "createTwoTasks", nestedProjectId, "Alpha", "Beta"),
                "the multi-row insert writes both rows of its VALUES list"
            );

            assertEquals(
                2,
                invoke(repository, "addMembers", organizationId, List.of(authorId, otherAuthorId)),
                "the unnest bulk insert writes one membership per array element"
            );

            assertEquals(
                0,
                invoke(repository, "addMembers", organizationId, List.of(authorId, otherAuthorId)),
                "the unnest bulk insert keeps the existing memberships"
            );

            List<?> uncommented = (List<?>) invoke(
                repository,
                "listTasksUncommentedBy",
                nestedProjectId,
                authorId
            );

            assertEquals(
                List.of("Alpha", "Beta"),
                componentsOf(uncommented, "title"),
                "both inserted tasks are uncommented"
            );

            Long alphaId = (Long) component(uncommented.get(0), "id");

            assertNotNull(invoke(repository, "addComment", alphaId, authorId, "Mine."));

            assertEquals(
                List.of("Beta"),
                componentsOf(
                    (List<?>) invoke(
                        repository,
                        "listTasksUncommentedBy",
                        nestedProjectId,
                        authorId
                    ),
                    "title"
                ),
                "NOT EXISTS drops the task its correlated author commented on"
            );

            assertEquals(
                List.of("Alpha", "Beta"),
                componentsOf(
                    (List<?>) invoke(
                        repository,
                        "listTasksUncommentedBy",
                        nestedProjectId,
                        otherAuthorId
                    ),
                    "title"
                ),
                "NOT EXISTS keeps every task the other author left uncommented"
            );

            List<?> onePriority = (List<?>) invoke(
                repository,
                "countOpenTasksByProject",
                (short) 1,
                organizationId
            );

            assertEquals(List.of("NEST"), componentsOf(onePriority, "code"));

            assertEquals(
                List.of(1L),
                componentsOf(onePriority, "openTasks"),
                "the CTE report counts the tasks its placeholder admits"
            );

            List<?> everyPriority = (List<?>) invoke(
                repository,
                "countOpenTasksByProject",
                (short) 5,
                organizationId
            );

            assertEquals(List.of("NEST"), componentsOf(everyPriority, "code"));

            assertEquals(List.of(2L), componentsOf(everyPriority, "openTasks"));

            assertEquals(
                List.of("Beta"),
                componentsOf(
                    (List<?>) invoke(repository, "listRecentTaskTitles", nestedProjectId, 1),
                    "title"
                ),
                "the subquery source applies its own LIMIT placeholder"
            );

            assertEquals(
                2,
                invoke(repository, "copyOpenTasks", copyProjectId, nestedProjectId),
                "INSERT ... SELECT copies the source project's open tasks"
            );

            List<?> raised = (List<?>) invoke(
                repository,
                "raiseProjectTaskPriority",
                (short) 5,
                organizationId,
                "COPY"
            );

            assertEquals(2, raised.size(), "the joined update raises the copied tasks");

            assertEquals(
                "TasksRow",
                raised.get(0).getClass().getSimpleName(),
                "RETURNING t.* returns the shared row record"
            );

            assertEquals(
                List.of("Alpha", "Beta"),
                componentsOf(raised, "title").stream()
                    .map(String.class::cast)
                    .sorted()
                    .toList()
            );

            assertEquals(
                List.of(copyProjectId, copyProjectId),
                componentsOf(raised, "projectId")
            );

            assertEquals(
                List.of((short) 5, (short) 5),
                componentsOf(raised, "priority"),
                "the joined update sets the priority its placeholder states"
            );

            assertEquals(
                1,
                invoke(repository, "deleteProjectComments", nestedProjectId),
                "the USING delete removes the comments of its project's tasks"
            );

            List<?> bothProjects = (List<?>) invoke(
                repository,
                "countOpenTasksByProject",
                (short) 5,
                organizationId
            );

            assertEquals(List.of("COPY", "NEST"), componentsOf(bothProjects, "code"));

            assertEquals(List.of(2L, 2L), componentsOf(bothProjects, "openTasks"));
        }
    }

    /**
     * The generated repository executes the result shapes this milestone adds
     * against the migrated database: the list-with-count and the
     * latest-activity reads, whose uncast {@code COUNT} and {@code MAX} stand
     * beside an embedded table row, and the inner-joined and left-joined
     * {@code SELECT t.*, p.*} reads, whose left-joined task row is absent for
     * a project without tasks.
     *
     * <p>The database is shared with the other tests of this class, so this one
     * works inside its own organization, projects, and user, and writes no
     * audit entry.
     */
    @Test
    void shouldExecuteTheGeneratedResultShapes() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-rows");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-rows"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Long organizationId = (Long) component(
                invoke(repository, "createOrganization", "Row Test", "{}"),
                "id"
            );

            Object rowsProject = invoke(
                repository,
                "createProject",
                organizationId,
                "ROWS",
                "Rows"
            );

            Object emptyProject = invoke(
                repository,
                "createProject",
                organizationId,
                "EMPTY",
                "Empty"
            );

            Long rowsId = (Long) component(rowsProject, "id");

            Object open = enumConstant(classLoader, "TaskStatus", "OPEN");
            Object doing = enumConstant(classLoader, "TaskStatus", "DOING");

            Object alpha = createShapeTask(repository, rowsId, "Alpha", (short) 1, open);
            Object beta = createShapeTask(repository, rowsId, "Beta", (short) 2, doing);

            Long alphaId = (Long) component(alpha, "id");

            Long authorId = (Long) component(
                invoke(repository, "createUser", organizationId, "alan@example.com", "Alan Turing"),
                "id"
            );

            Object firstComment = invoke(repository, "addComment", alphaId, authorId, "First.");
            Object secondComment = invoke(repository, "addComment", alphaId, authorId, "Second.");

            Instant firstCommentAt = ((OffsetDateTime) component(firstComment, "createdAt"))
                .toInstant();

            Instant secondCommentAt = ((OffsetDateTime) component(secondComment, "createdAt"))
                .toInstant();

            Instant lastCommentAt = firstCommentAt.isAfter(secondCommentAt)
                ? firstCommentAt
                : secondCommentAt;

            List<?> withOpenTaskCount = (List<?>) invoke(
                repository,
                "listProjectsWithOpenTaskCount",
                organizationId
            );

            assertEquals(
                List.of(rowsProject, emptyProject),
                componentsOf(withOpenTaskCount, "p"),
                "the embedded project row is the shared row record the write returned"
            );

            assertEquals(
                List.of(1L, 0L),
                componentsOf(withOpenTaskCount, "openTasks"),
                "the uncast COUNT counts each project's open tasks"
            );

            List<?> withLastComment = (List<?>) invoke(
                repository,
                "listTasksWithLastComment",
                rowsId
            );

            assertEquals(
                List.of(alpha, beta),
                componentsOf(withLastComment, "t"),
                "the embedded task row is the shared row record the write returned"
            );

            assertEquals(
                lastCommentAt,
                ((OffsetDateTime) component(withLastComment.get(0), "lastCommentAt")).toInstant(),
                "the uncast MAX reads the later comment's timestamp"
            );

            assertNull(
                component(withLastComment.get(1), "lastCommentAt"),
                "the uncast MAX of a task without comments is null"
            );

            List<?> withProject = (List<?>) invoke(
                repository,
                "listTasksWithProject",
                organizationId
            );

            assertEquals(List.of(alpha, beta), componentsOf(withProject, "t"));

            assertEquals(List.of(rowsProject, rowsProject), componentsOf(withProject, "p"));

            assertEquals(
                "TasksRow",
                component(withProject.get(0), "t").getClass().getSimpleName(),
                "the inner-joined read embeds the shared task row"
            );

            assertEquals(
                "ProjectsRow",
                component(withProject.get(0), "p").getClass().getSimpleName(),
                "the inner-joined read embeds the shared project row"
            );

            List<?> withTheirTasks = (List<?>) invoke(
                repository,
                "listProjectsWithTheirTasks",
                organizationId
            );

            assertEquals(
                Arrays.asList(alpha, beta, null),
                componentsOf(withTheirTasks, "t"),
                "the left-joined task row is absent for a project without tasks"
            );

            assertEquals(
                List.of(rowsProject, rowsProject, emptyProject),
                componentsOf(withTheirTasks, "p")
            );
        }
    }

    /**
     * The generated repository executes the set operations this milestone adds
     * against the migrated database: the project and task titles combined with
     * {@code UNION}, which returns one row per distinct title, and with
     * {@code UNION ALL}, which returns one row per selected title.
     *
     * <p>Both queries read every project and task of the database, which the
     * other tests of this class share, so this one works on a title of its own:
     * one project named after it and two tasks titled the same.
     */
    @Test
    void shouldExecuteTheGeneratedSetOperationQueries() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-sets");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-sets"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Long organizationId = (Long) component(
                invoke(repository, "createOrganization", "Set Test", "{}"),
                "id"
            );

            Long projectId = (Long) component(
                invoke(repository, "createProject", organizationId, "SETS", "Shared Title"),
                "id"
            );

            Object open = enumConstant(classLoader, "TaskStatus", "OPEN");

            createShapeTask(repository, projectId, "Shared Title", (short) 1, open);
            createShapeTask(repository, projectId, "Shared Title", (short) 2, open);

            assertEquals(
                1,
                sharedTitles((List<?>) invoke(repository, "listProjectAndTaskTitles")),
                "UNION returns the repeated title once"
            );

            assertEquals(
                3,
                sharedTitles((List<?>) invoke(repository, "listEveryProjectAndTaskTitle")),
                "UNION ALL returns the project name and both task titles"
            );
        }
    }

    /** The rows of a title read whose title is the set operation test's own. */
    private long sharedTitles(List<?> rows) throws Exception {
        return componentsOf(rows, "title").stream()
            .filter("Shared Title"::equals)
            .count();
    }

    /**
     * The generated repository executes the join kinds this milestone adds
     * against the migrated database: the right join and the full join, each of
     * which reads a {@code null} title for a project without tasks, the cross
     * join, which pairs every organization with every project, the
     * comma-separated list, which returns the joined rows alone, the
     * {@code USING} join, which reads its merged organization id, the
     * {@code NATURAL} join of a subquery source, which merges the project id,
     * and the {@code LATERAL} join, whose body reads the latest task of the
     * project it is correlated to and returns no row for a project without
     * tasks.
     *
     * <p>All of them read every organization, project, and task of the
     * database, which the other tests of this class share, so this one asserts
     * over its own two organizations, its own two projects, its own single
     * task, and its own membership.
     */
    @Test
    void shouldExecuteTheGeneratedJoinKindQueries() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-joins");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-joins"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Long firstOrganizationId = (Long) component(
                invoke(repository, "createOrganization", JOIN_ORGANIZATIONS.get(0), "{}"),
                "id"
            );

            Long secondOrganizationId = (Long) component(
                invoke(repository, "createOrganization", JOIN_ORGANIZATIONS.get(1), "{}"),
                "id"
            );

            Long taskedProjectId = (Long) component(
                invoke(
                    repository,
                    "createProject",
                    firstOrganizationId,
                    "JOINED",
                    JOIN_PROJECTS.get(0)
                ),
                "id"
            );

            invoke(
                repository,
                "createProject",
                secondOrganizationId,
                "UNJOINED",
                JOIN_PROJECTS.get(1)
            );

            Object open = enumConstant(classLoader, "TaskStatus", "OPEN");

            createShapeTask(repository, taskedProjectId, "Join Test Task", (short) 1, open);

            List<?> rightJoined = (List<?>) invoke(repository, "listProjectsWithTasks");

            assertEquals(
                List.of("Join Test Task"),
                projectTitles(rightJoined, JOIN_PROJECTS.get(0)),
                "the right join reads the task of a project that has one"
            );

            assertEquals(
                Arrays.asList((Object) null),
                projectTitles(rightJoined, JOIN_PROJECTS.get(1)),
                "the right join reads a null title for a project without tasks"
            );

            List<?> fullJoined = (List<?>) invoke(repository, "listProjectsAndTasks");

            assertEquals(
                List.of("Join Test Task"),
                projectTitles(fullJoined, JOIN_PROJECTS.get(0)),
                "the full join reads the task of a project that has one"
            );

            assertEquals(
                Arrays.asList((Object) null),
                projectTitles(fullJoined, JOIN_PROJECTS.get(1)),
                "the full join reads a null title for a project without tasks"
            );

            List<List<Object>> pairs = ownPairs(
                (List<?>) invoke(repository, "listOrganizationProjectPairs")
            );

            assertEquals(4, pairs.size(), "the cross join returns every pair of the four");

            assertTrue(
                pairs.containsAll(
                    List.of(
                        List.of(JOIN_ORGANIZATIONS.get(0), JOIN_PROJECTS.get(0)),
                        List.of(JOIN_ORGANIZATIONS.get(0), JOIN_PROJECTS.get(1)),
                        List.of(JOIN_ORGANIZATIONS.get(1), JOIN_PROJECTS.get(0)),
                        List.of(JOIN_ORGANIZATIONS.get(1), JOIN_PROJECTS.get(1))
                    )
                ),
                "the cross join pairs each organization with each project"
            );

            List<?> commaJoined = (List<?>) invoke(repository, "listCommaJoinedTasks");

            assertEquals(
                List.of("Join Test Task"),
                projectTitles(commaJoined, JOIN_PROJECTS.get(0)),
                "the comma-separated list returns the joined task"
            );

            assertEquals(
                List.of(),
                projectTitles(commaJoined, JOIN_PROJECTS.get(1)),
                "the comma-separated list returns the joined rows alone"
            );

            Long memberId = (Long) component(
                invoke(
                    repository,
                    "createUser",
                    firstOrganizationId,
                    "joins@example.com",
                    "Join Test Member"
                ),
                "id"
            );

            invoke(
                repository,
                "addMember",
                firstOrganizationId,
                memberId,
                enumConstant(classLoader, "MembershipRole", "MAINTAINER")
            );

            List<?> members = (List<?>) invoke(repository, "listProjectMembers");

            assertEquals(
                List.of(List.of(firstOrganizationId, memberId)),
                ownMemberships(members),
                "the USING join reads the merged organization id of its own member"
            );

            List<?> naturallyJoined = (List<?>) invoke(
                repository,
                "listNaturallyJoinedTasks"
            );

            assertEquals(
                List.of("Join Test Task"),
                projectTitles(naturallyJoined, JOIN_PROJECTS.get(0)),
                "the NATURAL join reads the task of its own project"
            );

            assertEquals(
                List.of(),
                projectTitles(naturallyJoined, JOIN_PROJECTS.get(1)),
                "the NATURAL join merges the project id, so an unjoined project returns no row"
            );

            List<?> laterallyJoined = (List<?>) invoke(
                repository,
                "listProjectsWithLatestTask"
            );

            assertEquals(
                List.of("Join Test Task"),
                projectTitles(laterallyJoined, JOIN_PROJECTS.get(0)),
                "the LATERAL body reads the latest task of the project it is correlated to"
            );

            assertEquals(
                List.of(),
                projectTitles(laterallyJoined, JOIN_PROJECTS.get(1)),
                "the LATERAL join returns no row for a project without tasks"
            );
        }
    }

    /**
     * The merged organization id and the user id one membership read returns
     * for the projects the join kind test owns, in the result's order.
     */
    private List<List<Object>> ownMemberships(List<?> rows) throws Exception {
        List<List<Object>> memberships = new ArrayList<>();

        for (Object row : rows) {
            if (JOIN_PROJECTS.get(0).equals(component(row, "name"))) {
                memberships.add(
                    List.of(component(row, "organizationId"), component(row, "userId"))
                );
            }
        }

        return memberships;
    }

    /**
     * The titles one join read returns beside one project name, in the
     * result's order, where an absent task reads {@code null}.
     */
    private List<Object> projectTitles(List<?> rows, String projectName) throws Exception {
        List<Object> titles = new ArrayList<>();

        for (Object row : rows) {
            if (projectName.equals(component(row, "name"))) {
                titles.add(component(row, "title"));
            }
        }

        return titles;
    }

    /**
     * The organization and project name pairs one cross-join read returns among
     * the names the join kind test owns, in the result's order.
     */
    private List<List<Object>> ownPairs(List<?> rows) throws Exception {
        List<List<Object>> pairs = new ArrayList<>();

        for (Object row : rows) {
            Object organizationName = component(row, "organizationName");
            Object projectName = component(row, "projectName");

            if (
                JOIN_ORGANIZATIONS.contains(organizationName)
                    && JOIN_PROJECTS.contains(projectName)
            ) {
                pairs.add(List.of(organizationName, projectName));
            }
        }

        return pairs;
    }

    /**
     * The generated repository executes the reference service's recursive query
     * against the migrated database: a series whose first term selects its seed
     * row without reading a table and whose recursive term counts up to five.
     */
    @Test
    void shouldExecuteTheGeneratedRecursiveQuery() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-recursive");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-recursive"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            assertEquals(
                List.of(1, 2, 3, 4, 5),
                componentsOf((List<?>) invoke(repository, "listPriorityLevels"), "level")
            );
        }
    }

    /**
     * The generated repository executes the reference service's domain,
     * {@code citext}, {@code inet}, and {@code interval} columns against the
     * migrated database: the uncast writes of the {@code email_address} domain
     * and of the {@code citext} handle, the lookups that match either of them
     * in any case while reading back the stored spelling, the {@code inet}
     * login address, and the retention interval, which holds months, days, and
     * a time part and reads back as {@code null} once it is cleared.
     *
     * <p>The database is shared with the other tests of this class, and
     * {@code users_email_key} is unique over every organization, so this one
     * works inside its own organization and with email addresses no other test
     * writes.
     */
    @Test
    void shouldExecuteTheGeneratedReferenceServiceTypes() throws Exception {
        Path generatedDirectory = tempDir.resolve("generated-types");

        generate(List.of(migrations().toString()), generatedDirectory);

        Path classesDirectory = compile(generatedDirectory, tempDir.resolve("classes-types"));

        try (URLClassLoader classLoader = classLoader(classesDirectory)) {
            Object repository = newRepository(classLoader);

            Long organizationId = (Long) component(
                invoke(repository, "createOrganization", "Type Test", "{}"),
                "id"
            );

            Long userId = (Long) component(
                invoke(
                    repository,
                    "createUser",
                    organizationId,
                    "Mary.Jackson@Example.com",
                    "Mary Jackson"
                ),
                "id"
            );

            Object foundByEmail = ((Optional<?>) invoke(
                repository,
                "getUserByEmail",
                "mary.jackson@example.com"
            )).orElseThrow();

            assertEquals(
                userId,
                component(foundByEmail, "id"),
                "the column of a domain over citext matches an address in another case"
            );

            assertEquals(
                "Mary.Jackson@Example.com",
                component(foundByEmail, "email"),
                "the domain column reads back the spelling it was written with"
            );

            assertEquals(
                Optional.empty(),
                invoke(repository, "getUserByEmail", "no.such.user@example.com"),
                "an unused address reads no user"
            );

            Long handleUserId = (Long) component(
                ((Optional<?>) invoke(
                    repository,
                    "claimHandle",
                    organizationId,
                    "Dorothy.Vaughan@Example.com",
                    "Dorothy",
                    "Dorothy Vaughan"
                )).orElseThrow(),
                "id"
            );

            Object foundByHandle = ((Optional<?>) invoke(
                repository,
                "getUserByHandle",
                organizationId,
                "DOROTHY"
            )).orElseThrow();

            assertEquals(
                handleUserId,
                component(foundByHandle, "id"),
                "the citext handle matches a handle in another case"
            );

            assertEquals(
                "Dorothy",
                component(foundByHandle, "handle"),
                "the citext column reads back the spelling it was written with"
            );

            assertEquals(
                "Dorothy.Vaughan@Example.com",
                component(foundByHandle, "email")
            );

            assertEquals(
                1,
                invoke(repository, "recordLogin", "192.0.2.10", userId),
                "the inet write updates the user its placeholder names"
            );

            assertEquals(
                "192.0.2.10",
                component(
                    ((Optional<?>) invoke(
                        repository,
                        "getUserByEmail",
                        "MARY.JACKSON@EXAMPLE.COM"
                    )).orElseThrow(),
                    "lastLoginIp"
                ),
                "the inet column reads back the address that was recorded"
            );

            assertNull(
                component(retentionPeriod(repository, organizationId), "retentionPeriod"),
                "the interval column of a new organization is null"
            );

            SqlInterval retention = new SqlInterval(14, 3, 3_723_000_004L);

            assertEquals(
                1,
                invoke(repository, "setRetentionPeriod", retention, organizationId)
            );

            assertEquals(
                retention,
                component(retentionPeriod(repository, organizationId), "retentionPeriod"),
                "the interval reads back its months, days, and time part"
            );

            assertEquals(
                1,
                invoke(repository, "setRetentionPeriod", null, organizationId)
            );

            assertNull(
                component(retentionPeriod(repository, organizationId), "retentionPeriod"),
                "a cleared interval reads back null"
            );
        }
    }

    /** The one retention period read of one organization. */
    private Object retentionPeriod(Object repository, Long organizationId) throws Exception {
        return ((Optional<?>) invoke(repository, "getRetentionPeriod", organizationId))
            .orElseThrow();
    }

    /**
     * Every entry of the unsupported query catalogue is rejected with its whole
     * located diagnostic line against the migration history, and writes no
     * file.
     */
    @ParameterizedTest
    @MethodSource("unsupportedQueries")
    void shouldRejectEachUnsupportedCatalogueQuery(UnsupportedQuery entry) throws Exception {
        Path queriesFile = resource("unsupported/queries/" + entry.fileName());

        Path generatedDirectory = tempDir.resolve("generated-unsupported");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> generate(
                List.of(migrations().toString()),
                queriesFile,
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid query '%s' in %s at line 2: %s".formatted(
                entry.queryName(),
                queriesFile,
                entry.reason()
            ),
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /** The catalogue directory holds exactly the catalogued query files. */
    @Test
    void shouldCatalogueExactlyTheUnsupportedQueryFiles() throws Exception {
        try (Stream<Path> files = Files.list(resource("unsupported/queries"))) {
            assertEquals(
                UNSUPPORTED_QUERIES.stream()
                    .map(UnsupportedQuery::fileName)
                    .sorted()
                    .toList(),
                files
                    .map(file -> file.getFileName().toString())
                    .sorted()
                    .toList()
            );
        }
    }

    static List<UnsupportedQuery> unsupportedQueries() {
        return UNSUPPORTED_QUERIES;
    }

    /**
     * The unsupported catalogue's {@code SET DATA TYPE}, applied after the
     * history, is rejected with its file, line, and column, and nothing is
     * written.
     */
    @Test
    void shouldRejectTheUnsupportedSetDataTypeMigration() throws Exception {
        Path unsupported = resource("unsupported/set_data_type.sql");

        Path generatedDirectory = tempDir.resolve("generated-rejected");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> generate(
                List.of(migrations().toString(), unsupported.toString()),
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid schema source %s: ".formatted(unsupported)
                + "Encountered unexpected token: \"DATA\" at line 2, column 51",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /**
     * The reporting schema of the history is invisible, so a query that names
     * its table is rejected as an unknown table rather than resolving to the
     * {@code projects} table of the one modeled namespace.
     */
    @Test
    void shouldRejectAQueryOnASecondSchemaTable() throws Exception {
        Path queriesFile = tempDir.resolve("reporting-queries.sql");

        Files.writeString(
            queriesFile,
            """
                -- name: ListExportedProjects :many
                SELECT *
                FROM reporting.projects;
                """
        );

        Path generatedDirectory = tempDir.resolve("generated-reporting");

        CompilationException exception = assertThrows(
            CompilationException.class,
            () -> generate(
                List.of(migrations().toString()),
                queriesFile,
                generatedDirectory
            )
        );

        assertEquals(
            "Invalid query 'ListExportedProjects' in %s at line 1: ".formatted(queriesFile)
                + "Table not found in schema: reporting.projects",
            exception.getMessage()
        );

        assertFalse(Files.exists(generatedDirectory));
    }

    /** Runs one command inside the database container and fails on its stderr. */
    private static void run(String... command) throws Exception {
        Container.ExecResult result = postgres.execInContainer(command);

        assertEquals(
            0,
            result.getExitCode(),
            String.join(" ", command) + System.lineSeparator() + result.getStderr()
        );
    }

    /** The {@code .sql} file names of the migration directory, in version order. */
    private static List<String> migrationFileNames() throws Exception {
        try (Stream<Path> files = Files.list(migrations())) {
            return files
                .map(file -> file.getFileName().toString())
                .sorted(
                    (first, second) -> Integer.compare(
                        migrationVersion(first),
                        migrationVersion(second)
                    )
                )
                .toList();
        }
    }

    private static int migrationVersion(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }

    private static Path migrations() throws Exception {
        return resource("migrations");
    }

    private static Path snapshot() throws Exception {
        return resource("schema.sql");
    }

    private static Path queries() throws Exception {
        return resource("queries.sql");
    }

    /** One reference service fixture, resolved from the test classpath. */
    private static Path resource(String name) throws Exception {
        URL url = ReferenceServiceIntegrationTest.class.getResource(
            "/reference-service/" + name
        );

        assertNotNull(url, "missing reference service fixture: " + name);

        return Paths.get(url.toURI());
    }

    private void generate(List<String> schema, Path generatedDirectory) throws Exception {
        generate(schema, queries(), generatedDirectory);
    }

    /** Generates the one repository of the {@code Tracker} group. */
    private void generate(List<String> schema, Path queriesFile, Path generatedDirectory) {
        Config config = new Config(
            List.of(
                new SqlConfig(
                    GROUP,
                    schema,
                    queriesFile.toString()
                )
            ),
            new JavaConfig(
                generatedDirectory.toString(),
                "generated"
            )
        );

        new SqlcjCompiler().compile(config);
    }

    /** Every generated file of one output directory, by its relative path. */
    private Map<String, byte[]> generatedFiles(Path generatedDirectory) throws Exception {
        Map<String, byte[]> files = new TreeMap<>();

        try (Stream<Path> paths = Files.walk(generatedDirectory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(
                    generatedDirectory.relativize(path).toString().replace('\\', '/'),
                    Files.readAllBytes(path)
                );
            }
        }

        return files;
    }

    private void assertIdentical(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());

        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            assertArrayEquals(
                file.getValue(),
                actual.get(file.getKey()),
                file.getKey() + " differs"
            );
        }
    }

    /** Compiles every generated source of one output directory together. */
    private Path compile(Path generatedDirectory, Path classesDirectory) throws Exception {
        Files.createDirectories(classesDirectory);

        JavaCompiler compilerApi = ToolProvider.getSystemJavaCompiler();

        assertNotNull(compilerApi);

        List<String> arguments = new ArrayList<>(
            List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDirectory.toString()
            )
        );

        try (Stream<Path> generatedFiles = Files.walk(generatedDirectory)) {
            generatedFiles
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
                .sorted()
                .forEach(arguments::add);
        }

        assertEquals(
            0,
            compilerApi.run(
                null,
                null,
                null,
                arguments.toArray(new String[0])
            )
        );

        return classesDirectory;
    }

    private URLClassLoader classLoader(Path classesDirectory) throws Exception {
        return new URLClassLoader(
            new URL[] { classesDirectory.toUri().toURL() },
            getClass().getClassLoader()
        );
    }

    private Object newRepository(URLClassLoader classLoader) throws Exception {
        Class<?> generatedClass = Class.forName(
            "generated." + GROUP + "Repository",
            true,
            classLoader
        );

        Constructor<?> constructor = generatedClass.getConstructors()[0];

        return constructor.newInstance(new JdbcQueryExecutor(dataSource));
    }

    /** One constant of a generated enum, loaded from the compiled output. */
    private Object enumConstant(
        URLClassLoader classLoader,
        String enumName,
        String constant
    ) throws Exception {
        return Class.forName("generated." + enumName, true, classLoader)
            .getField(constant)
            .get(null);
    }

    /** Calls one generated repository method, which has one name of its own. */
    private Object invoke(Object repository, String methodName, Object... arguments)
        throws Exception {
        for (Method method : repository.getClass().getMethods()) {
            if (method.getName().equals(methodName)) {
                return method.invoke(repository, arguments);
            }
        }

        return fail("missing generated method: " + methodName);
    }

    private Object component(Object record, String componentName) throws Exception {
        return record
            .getClass()
            .getMethod(componentName)
            .invoke(record);
    }

    /** One component of every row of a result list, in the list's order. */
    private List<Object> componentsOf(List<?> rows, String componentName) throws Exception {
        List<Object> values = new ArrayList<>();

        for (Object row : rows) {
            values.add(component(row, componentName));
        }

        return values;
    }

    /** One task of the milestone shapes, with the labels and metadata it needs. */
    private Object createShapeTask(
        Object repository,
        Long projectId,
        String title,
        Short priority,
        Object status
    ) throws Exception {
        return invoke(
            repository,
            "createTask",
            projectId,
            title,
            status,
            priority,
            List.of("shape"),
            "{\"source\": \"fixture\"}"
        );
    }

    private List<String> recordComponentNames(Class<?> recordClass) {
        assertTrue(recordClass.isRecord());

        return Arrays.stream(recordClass.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }

    /**
     * One entry of the unsupported query catalogue: the query file, the name of
     * the single query it holds, and the reason sqlcj reports for it.
     */
    record UnsupportedQuery(String fileName, String queryName, String reason) {

        @Override
        public String toString() {
            return fileName;
        }
    }
}
