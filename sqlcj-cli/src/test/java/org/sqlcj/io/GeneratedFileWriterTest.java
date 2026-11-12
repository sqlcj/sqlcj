package org.sqlcj.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlcj.generator.GeneratedFile;
import org.sqlcj.generator.JavaCodeGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class GeneratedFileWriterTest {

    @TempDir
    Path tempDir;

    private final GeneratedFileWriter writer = new GeneratedFileWriter();

    /** The one generated file the replacement checks are made over. */
    private GeneratedFile generatedFile() {
        return new GeneratedFile(Path.of("GetUser.java"), "public final class GetUser {}");
    }

    @Test
    void shouldWriteGeneratedFileToOutputDirectory() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = new GeneratedFile(
            Path.of("GetUser.java"),
            "public final class GetUser {}"
        );

        writer.write(file, outputDirectory);

        Path outputFile = outputDirectory.resolve("GetUser.java");

        assertTrue(Files.exists(outputFile));
        assertEquals(
            "public final class GetUser {}",
            Files.readString(outputFile)
        );
    }

    @Test
    void shouldCreateParentDirectories() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = new GeneratedFile(
            Path.of("nested", "GetUser.java"),
            "public final class GetUser {}"
        );

        writer.write(file, outputDirectory);

        Path outputFile = outputDirectory.resolve("nested").resolve("GetUser.java");

        assertTrue(Files.exists(outputFile));
        assertEquals(
            "public final class GetUser {}",
            Files.readString(outputFile)
        );
    }

    @Test
    void shouldReportTheDirectoryItCannotCreate() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        Files.writeString(outputDirectory, "not a directory");

        GeneratedFile file = new GeneratedFile(
            Path.of("nested", "GetUser.java"),
            "public final class GetUser {}"
        );

        IOException failure = assertThrows(
            IOException.class,
            () -> writer.write(file, outputDirectory)
        );

        assertEquals(
            "Cannot create output directory: %s: Not a directory"
                .formatted(outputDirectory.resolve(file.path()).getParent()),
            failure.getMessage()
        );
    }

    /** Nothing stands at the generated path, so the run may write it. */
    @Test
    void shouldReplaceAnAbsentFile() throws IOException {
        assertTrue(writer.isReplaceable(generatedFile(), tempDir.resolve("generated")));
    }

    /**
     * A file that begins with the generated-code notice was written by sqlcj,
     * so output written before any manifest existed is replaced.
     */
    @Test
    void shouldReplaceAFileCarryingTheGeneratedNotice() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = generatedFile();

        Files.createDirectories(outputDirectory);

        Files.writeString(
            outputDirectory.resolve(file.path()),
            JavaCodeGenerator.GENERATED_NOTICE + "\n\npublic final class GetUser {}"
        );

        assertTrue(writer.isReplaceable(file, outputDirectory));
    }

    /** A file without the notice is hand-written and is never replaced. */
    @Test
    void shouldNotReplaceAHandWrittenFile() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = generatedFile();

        Files.createDirectories(outputDirectory);

        Files.writeString(outputDirectory.resolve(file.path()), "// hand written\n");

        assertFalse(writer.isReplaceable(file, outputDirectory));
    }

    /** A file shorter than the notice cannot carry it. */
    @Test
    void shouldNotReplaceAFileShorterThanTheNotice() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = generatedFile();

        Files.createDirectories(outputDirectory);

        Files.writeString(outputDirectory.resolve(file.path()), "// Code");

        assertFalse(writer.isReplaceable(file, outputDirectory));
    }

    @Test
    void shouldReportTheExistingFileItCannotRead() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = generatedFile();

        Path outputFile = outputDirectory.resolve(file.path());

        Files.createDirectories(outputDirectory);
        Files.writeString(outputFile, JavaCodeGenerator.GENERATED_NOTICE + "\n");

        Files.setPosixFilePermissions(outputFile, PosixFilePermissions.fromString("-w-------"));

        try {
            assumeFalse(
                Files.isReadable(outputFile),
                "requires a filesystem that enforces file permissions"
            );

            IOException failure = assertThrows(
                IOException.class,
                () -> writer.isReplaceable(file, outputDirectory)
            );

            assertEquals(
                "Cannot read existing output file: %s: Permission denied".formatted(outputFile),
                failure.getMessage()
            );
        } finally {
            Files.setPosixFilePermissions(outputFile, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void shouldReportTheFileItCannotWrite() throws IOException {
        Path outputDirectory = tempDir.resolve("generated");

        GeneratedFile file = new GeneratedFile(
            Path.of("GetUser.java"),
            "public final class GetUser {}"
        );

        Files.createDirectories(outputDirectory.resolve(file.path()));

        IOException failure = assertThrows(
            IOException.class,
            () -> writer.write(file, outputDirectory)
        );

        assertEquals(
            "Cannot write generated file: %s: Is a directory"
                .formatted(outputDirectory.resolve(file.path())),
            failure.getMessage()
        );
    }
}
