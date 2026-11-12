package org.sqlcj.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlcj.config.Config;
import org.sqlcj.config.JavaConfig;
import org.sqlcj.config.SqlConfig;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles generated trees under a null checker, so that generated source
 * never contradicts the nullness annotations it carries and no later generator
 * change brings a warning back.
 *
 * <p>Two trees are checked: the reference service's, generated from its
 * hand-written snapshot, and a sqlcj-owned fixture that reads every mapped
 * type, scalar and array, nullable and {@code NOT NULL}, through every result
 * shape a generated repository has.
 *
 * <p>Each tree is compiled in a {@code javac} process of the build's own JDK,
 * so no compiler or surefire setting of this build changes, with
 * {@code -Xlint:all -Werror} and NullAway in JSpecify mode at error level,
 * every other Error Prone check disabled. Nothing but generated source is
 * compiled.
 */
class GeneratedSourceCheckTest {

    /**
     * The text NullAway reports on a JDK that cannot run its JSpecify mode,
     * which the test turns into a message naming the JDKs that can rather than
     * skipping the check.
     */
    private static final String JSPECIFY_MODE_UNSUPPORTED = "NullAway in JSpecify mode";

    /** The marker every NullAway finding carries. */
    private static final String NULLAWAY_FINDING = "[NullAway]";

    /** The packages of {@code javac} Error Prone reads the compilation from. */
    private static final List<String> EXPORTED_COMPILER_PACKAGES = List.of(
        "api",
        "file",
        "main",
        "model",
        "parser",
        "processing",
        "tree",
        "util",
        "code",
        "comp"
    );

    /** The packages of {@code javac} Error Prone reflects on. */
    private static final List<String> OPENED_COMPILER_PACKAGES = List.of("code", "comp");

    /**
     * Error Prone with every check but NullAway disabled, and NullAway in
     * JSpecify mode over the types a {@code @NullMarked} annotation marks,
     * which is every type generated code declares and the runtime's one
     * package.
     */
    private static final String ERROR_PRONE_PLUGIN = "-Xplugin:ErrorProne"
        + " -XepDisableAllChecks"
        + " -Xep:NullAway:ERROR"
        + " -XepOpt:NullAway:OnlyNullMarked=true"
        + " -XepOpt:NullAway:JSpecifyMode=true";

    @TempDir
    Path tempDir;

    /** The reference service's own tree carries no finding. */
    @Test
    void shouldReportNoFindingOnTheReferenceServiceTree() throws Exception {
        Path generatedDirectory = tempDir.resolve("reference-service");

        generate(
            "Tracker",
            fixture("/reference-service/schema.sql"),
            fixture("/reference-service/queries.sql"),
            generatedDirectory
        );

        assertNoFinding(generatedDirectory, "classes-reference-service");
    }

    /**
     * The fixture tree carries none either, so every read of every mapped
     * type, scalar and array, nullable and {@code NOT NULL}, passes the
     * checker in every result shape.
     */
    @Test
    void shouldReportNoFindingOnTheEveryTypeFixtureTree() throws Exception {
        Path generatedDirectory = tempDir.resolve("null-checker");

        generate(
            "Readings",
            fixture("/null-checker/schema.sql"),
            fixture("/null-checker/queries.sql"),
            generatedDirectory
        );

        assertNoFinding(generatedDirectory, "classes-null-checker");
    }

    /**
     * The negative control: the same check reports a nullness error on a
     * {@code @NullMarked} source that returns {@code null} from a non-null
     * method, so a clean run of the two trees above is evidence that NullAway
     * ran.
     */
    @Test
    void shouldReportANullnessErrorOnABrokenSource() throws Exception {
        Path sourceDirectory = tempDir.resolve("control");
        Path source = sourceDirectory.resolve("control/Control.java");

        Files.createDirectories(source.getParent());

        Files.writeString(source, """
            package control;

            @org.jspecify.annotations.NullMarked
            final class Control {

                static String broken() {
                    return null;
                }
            }
            """);

        Check check = check(List.of(source), tempDir.resolve("classes-control"));

        assertNotEquals(0, check.exitCode(), check.output());
        assertTrue(check.output().contains(NULLAWAY_FINDING), check.output());
    }

    /**
     * Asserts that compiling one generated tree under the checker reports
     * nothing at all and succeeds.
     */
    private void assertNoFinding(Path generatedDirectory, String classesName) throws Exception {
        Check check = check(sources(generatedDirectory), tempDir.resolve(classesName));

        if (check.output().contains(JSPECIFY_MODE_UNSUPPORTED)) {
            fail(
                ("NullAway cannot run in JSpecify mode on this JDK (%s). "
                    + "Run the build on JDK 17.0.19 or later, 21.0.8 or later, or 22 or later.%n%s")
                    .formatted(System.getProperty("java.version"), check.output())
            );
        }

        assertEquals("", check.output());
        assertEquals(0, check.exitCode());
    }

    /** Generates the one repository of one query group into its own directory. */
    private void generate(String group, Path schema, Path queries, Path generatedDirectory) {
        new SqlcjCompiler().compile(
            new Config(
                List.of(new SqlConfig(group, schema.toString(), queries.toString())),
                new JavaConfig(generatedDirectory.toString(), "generated")
            ),
            tempDir.resolve("sqlcj.yaml")
        );
    }

    /** Every generated source of one output directory, in a stable order. */
    private List<Path> sources(Path generatedDirectory) throws IOException {
        try (Stream<Path> paths = Files.walk(generatedDirectory)) {
            List<Path> sources = paths
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();

            assertFalse(sources.isEmpty());

            return sources;
        }
    }

    /**
     * Compiles the given sources in a {@code javac} process of the JDK running
     * the build, with {@code -Xlint:all -Werror} and NullAway at error level,
     * and returns its exit code and everything it wrote. The process runs in
     * the temporary classes directory, so a crash dump {@code javac} writes
     * beside its working directory leaves the repository alone; every path it
     * is given is absolute.
     */
    private Check check(List<Path> sources, Path classesDirectory) throws Exception {
        Files.createDirectories(classesDirectory);

        String classpath = System.getProperty("java.class.path");

        List<String> command = new ArrayList<>();

        command.add(
            Paths.get(System.getProperty("java.home"), "bin", "javac").toString()
        );

        for (String compilerPackage : EXPORTED_COMPILER_PACKAGES) {
            command.add(
                "-J--add-exports=jdk.compiler/com.sun.tools.javac.%s=ALL-UNNAMED"
                    .formatted(compilerPackage)
            );
        }

        for (String compilerPackage : OPENED_COMPILER_PACKAGES) {
            command.add(
                "-J--add-opens=jdk.compiler/com.sun.tools.javac.%s=ALL-UNNAMED"
                    .formatted(compilerPackage)
            );
        }

        command.addAll(
            List.of(
                "-XDcompilePolicy=simple",
                "--should-stop=ifError=FLOW",
                "-XDaddTypeAnnotationsToSymbol=true",
                "--release",
                "17",
                "-Xlint:all",
                "-Werror",
                "-processorpath",
                classpath,
                "-classpath",
                classpath,
                "-d",
                classesDirectory.toString(),
                ERROR_PRONE_PLUGIN
            )
        );

        sources.stream().map(Path::toString).forEach(command::add);

        Process process = new ProcessBuilder(command)
            .directory(classesDirectory.toFile())
            .redirectErrorStream(true)
            .start();

        String output;

        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes());
        }

        return new Check(process.waitFor(), output);
    }

    /** One fixture file of the test resources. */
    private Path fixture(String resource) throws Exception {
        URL url = GeneratedSourceCheckTest.class.getResource(resource);

        assertNotNull(url, "missing fixture: " + resource);

        return Paths.get(url.toURI());
    }

    /** The outcome of one checked compilation. */
    private record Check(int exitCode, String output) {
    }
}
