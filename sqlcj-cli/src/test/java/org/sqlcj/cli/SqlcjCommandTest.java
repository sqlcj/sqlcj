package org.sqlcj.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises every version and help form of the command line tool, each in an
 * isolated working directory, for its standard output, its standard error, and
 * its exit status. The version value itself is not asserted: the child JVM runs
 * from class directories, which carry no manifest.
 */
class SqlcjCommandTest {

    @TempDir
    Path workingDirectory;

    @Test
    void shouldPrintVersionOnOneLineForTheVersionCommand() throws Exception {
        Result result = run("version");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.error());
        assertEquals(1, result.output().lines().count(), result.output());
    }

    @Test
    void shouldPrintTheSameVersionForTheLongAndShortOption() throws Exception {
        Result command = run("version");
        Result longOption = run("--version");
        Result shortOption = run("-v");

        assertEquals(0, longOption.exitCode(), longOption.error());
        assertEquals("", longOption.error());
        assertEquals(1, longOption.output().lines().count(), longOption.output());
        assertEquals(command.output(), longOption.output());

        assertEquals(0, shortOption.exitCode(), shortOption.error());
        assertEquals("", shortOption.error());
        assertEquals(1, shortOption.output().lines().count(), shortOption.output());
        assertEquals(command.output(), shortOption.output());
    }

    @Test
    void shouldRejectTheRemovedUppercaseVersionOption() throws Exception {
        Result result = run("-V");

        assertEquals(2, result.exitCode(), result.error());
        assertEquals("", result.output());
        assertEquals("Unknown option: '-V'", result.error().lines().findFirst().orElse(""));
    }

    @Test
    void shouldPrintTheSameUsageWithoutArgumentsAndForBothHelpOptions() throws Exception {
        Result noArguments = run();
        Result longOption = run("--help");
        Result shortOption = run("-h");

        for (Result result : List.of(noArguments, longOption, shortOption)) {
            assertEquals(0, result.exitCode(), result.error());
            assertEquals("", result.error());
        }

        assertEquals(noArguments.output(), longOption.output());
        assertEquals(noArguments.output(), shortOption.output());

        String usage = noArguments.output();

        assertTrue(usage.startsWith("Usage: sqlcj"), usage);
        assertTrue(usage.contains("-h, --help"), usage);
        assertTrue(usage.contains("-v, --version"), usage);
        assertTrue(usage.contains("Commands:"), usage);
        assertTrue(usage.contains("version"), usage);
        assertTrue(usage.contains("generate"), usage);
        assertFalse(usage.contains("-V"), usage);
    }

    @Test
    void shouldPrintGenerateUsageForBothHelpOptionsWithoutRunningGenerate() throws Exception {
        for (String option : List.of("--help", "-h")) {
            Result result = run("generate", option);

            assertEquals(0, result.exitCode(), result.error());
            assertEquals("", result.error());

            String usage = result.output();

            assertTrue(usage.startsWith("Usage: sqlcj generate"), usage);
            assertTrue(usage.contains("--config=<path>"), usage);
            assertTrue(usage.contains("-h, --help"), usage);
            assertTrue(usage.lines().noneMatch(String::isBlank), usage);

            try (Stream<Path> entries = Files.list(workingDirectory)) {
                assertEquals(List.of(), entries.toList());
            }
        }
    }

    @Test
    void shouldPrintVersionCommandUsageForItsHelpOption() throws Exception {
        Result result = run("version", "--help");

        assertEquals(0, result.exitCode(), result.error());
        assertEquals("", result.error());
        assertTrue(result.output().startsWith("Usage: sqlcj version"), result.output());
        assertTrue(result.output().contains("-h, --help"), result.output());
    }

    private Result run(String... arguments) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");

        List<String> command = new ArrayList<>(
            List.of(
                java.toString(),
                "-classpath",
                System.getProperty("java.class.path"),
                "org.sqlcj.Main"
            )
        );

        command.addAll(List.of(arguments));

        Process process = new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .start();

        String output = new String(process.getInputStream().readAllBytes());
        String error = new String(process.getErrorStream().readAllBytes());

        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sqlcj did not terminate");

        return new Result(process.exitValue(), output, error);
    }

    private record Result(
        int exitCode,
        String output,
        String error
    ) {
    }
}
