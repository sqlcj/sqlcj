package org.sqlcj.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedOutputManifestTest {

    @TempDir
    Path tempDir;

    private final GeneratedOutputManifest manifest = new GeneratedOutputManifest();

    @Test
    void shouldReadNoContentsWithoutAManifest() throws IOException {
        assertEquals(
            Optional.empty(),
            manifest.read(tempDir.resolve("generated"), "dev.example.generated")
        );
    }

    /** The manifest of one package sits in that package's own directory. */
    @Test
    void shouldReadTheManifestOfThePackageDirectory() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Files.createDirectories(outputDirectory.resolve("dev/example/generated"));

        Files.writeString(
            outputDirectory.resolve("dev/example/generated/sqlcj-manifest.txt"),
            """
                # config: ../sqlcj.yaml
                dev/example/generated/repositories/OrdersRepository.java
                dev/example/generated/repositories/UsersRepository.java
                """
        );

        assertEquals(
            Optional.of(
                new GeneratedOutputManifest.Contents(
                    "../sqlcj.yaml",
                    List.of(
                        "dev/example/generated/repositories/OrdersRepository.java",
                        "dev/example/generated/repositories/UsersRepository.java"
                    )
                )
            ),
            manifest.read(outputDirectory, "dev.example.generated")
        );
    }

    /** A manifest of another package in the same output directory is not read. */
    @Test
    void shouldReadNoContentsForAnotherPackage() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        manifest.write(
            outputDirectory,
            "dev.example.generated",
            "../sqlcj.yaml",
            List.of(Path.of("dev", "example", "generated", "UsersRepository.java"))
        );

        assertEquals(
            Optional.empty(),
            manifest.read(outputDirectory, "dev.other.generated")
        );
    }

    @Test
    void shouldWriteTheConfigurationAndSortedEntriesTerminatedByNewlines() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        manifest.write(
            outputDirectory,
            "dev.example.generated",
            "../sqlcj.yaml",
            List.of(
                Path.of("dev", "example", "generated", "UsersRepository.java"),
                Path.of("dev", "example", "generated", "OrdersRepository.java")
            )
        );

        assertEquals(
            """
                # config: ../sqlcj.yaml
                dev/example/generated/OrdersRepository.java
                dev/example/generated/UsersRepository.java
                """,
            Files.readString(
                outputDirectory.resolve("dev/example/generated/sqlcj-manifest.txt")
            )
        );
    }

    /** What one run writes, the next run of the same package reads back. */
    @Test
    void shouldReadBackTheConfigurationAndEntriesItWrote() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        manifest.write(
            outputDirectory,
            "dev.example.generated",
            manifest.configuration(outputDirectory, tempDir.resolve("sqlcj.yaml")),
            List.of(Path.of("dev", "example", "generated", "UsersRepository.java"))
        );

        assertEquals(
            Optional.of(
                new GeneratedOutputManifest.Contents(
                    "../sqlcj.yaml",
                    List.of("dev/example/generated/UsersRepository.java")
                )
            ),
            manifest.read(outputDirectory, "dev.example.generated")
        );
    }

    /**
     * The owning configuration is the configuration file relative to the output
     * directory, with {@code /} between its name elements, so the same file run
     * from another working directory names the same configuration.
     */
    @Test
    void shouldStateTheConfigurationRelativeToTheOutputDirectory() {
        Path outputDirectory = tempDir.resolve("target/generated-sources/sqlcj");

        assertEquals(
            "../../../sqlcj.yaml",
            manifest.configuration(outputDirectory, tempDir.resolve("sqlcj.yaml"))
        );

        assertEquals(
            "../../../config/sqlcj.yaml",
            manifest.configuration(
                outputDirectory,
                tempDir.resolve("config").resolve("sqlcj.yaml")
            )
        );

        assertEquals(
            "../../../sqlcj.yaml",
            manifest.configuration(
                outputDirectory,
                tempDir.resolve("config/../sqlcj.yaml")
            )
        );
    }

    /** An entry the previous manifest lists names a path this run generates. */
    @Test
    void shouldReportWhetherTheEntriesListAGeneratedPath() {
        List<String> entries = List.of("dev/example/generated/repositories/UsersRepository.java");

        assertTrue(
            manifest.lists(
                entries,
                Path.of("dev", "example", "generated", "repositories", "UsersRepository.java")
            )
        );

        assertFalse(
            manifest.lists(
                entries,
                Path.of("dev", "example", "generated", "repositories", "OrdersRepository.java")
            )
        );
    }

    @Test
    void shouldReportTheManifestItCannotRead() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");
        Path manifestFile = outputDirectory
            .resolve("dev/example/generated")
            .resolve("sqlcj-manifest.txt");

        Files.createDirectories(manifestFile);

        IOException failure = assertThrows(
            IOException.class,
            () -> manifest.read(outputDirectory, "dev.example.generated")
        );

        assertEquals(
            "Cannot read output manifest: %s: Is a directory".formatted(manifestFile),
            failure.getMessage()
        );
    }

    @Test
    void shouldReportTheManifestItCannotWrite() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");
        Path manifestFile = outputDirectory
            .resolve("dev/example/generated")
            .resolve("sqlcj-manifest.txt");

        Files.createDirectories(manifestFile);

        IOException failure = assertThrows(
            IOException.class,
            () -> manifest.write(
                outputDirectory,
                "dev.example.generated",
                "../sqlcj.yaml",
                List.of(Path.of("generated", "UsersRepository.java"))
            )
        );

        assertEquals(
            "Cannot write output manifest: %s: Is a directory".formatted(manifestFile),
            failure.getMessage()
        );
    }

    @Test
    void shouldDeleteOnlyAListedFileThisRunDidNotProduce() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Path produced = write(outputDirectory, "generated/UsersRepository.java");
        Path stale = write(outputDirectory, "generated/OrdersRepository.java");

        manifest.deleteStale(
            outputDirectory,
            List.of(
                "generated/UsersRepository.java",
                "generated/OrdersRepository.java"
            ),
            List.of(Path.of("generated", "UsersRepository.java"))
        );

        assertTrue(Files.exists(produced));
        assertFalse(Files.exists(stale));
    }

    @Test
    void shouldKeepAnUnlistedFile() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        write(outputDirectory, "generated/UsersRepository.java");

        Path unlisted = write(outputDirectory, "generated/Helper.java");

        manifest.deleteStale(
            outputDirectory,
            List.of("generated/UsersRepository.java"),
            List.of(Path.of("generated", "UsersRepository.java"))
        );

        assertTrue(Files.exists(unlisted));
    }

    /**
     * An entry that escapes the output directory, is absolute, is not
     * normalized, is blank, names a directory, or names the same file this run
     * produced is never deleted.
     */
    @Test
    void shouldKeepAListedEntryItMustNotDelete() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Path produced = write(outputDirectory, "generated/UsersRepository.java");

        Path outside = tempDir.resolve("outside.java");

        Files.writeString(outside, "// outside\n");

        Path absolute = write(outputDirectory, "generated/AbsoluteRepository.java");
        Path notNormalized = write(outputDirectory, "generated/StaleRepository.java");
        Path directory = outputDirectory.resolve("generated/nested");

        Files.createDirectories(directory);

        Path hardLink = outputDirectory.resolve("generated/LinkedRepository.java");

        Files.createLink(hardLink, produced);

        manifest.deleteStale(
            outputDirectory,
            List.of(
                "../outside.java",
                absolute.toString(),
                "generated/./StaleRepository.java",
                "",
                "generated/nested",
                "generated/LinkedRepository.java"
            ),
            List.of(Path.of("generated", "UsersRepository.java"))
        );

        assertTrue(Files.exists(outside));
        assertTrue(Files.exists(absolute));
        assertTrue(Files.exists(notNormalized));
        assertTrue(Files.isDirectory(directory));
        assertTrue(Files.exists(hardLink));
        assertTrue(Files.exists(produced));
    }

    private Path write(Path outputDirectory, String entry) throws IOException {
        Path file = outputDirectory.resolve(entry);

        Files.createDirectories(file.getParent());

        Files.writeString(file, "// " + entry + "\n");

        return file;
    }
}
