package org.sqlcj.io;

import org.sqlcj.generator.JavaCodeGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The generated-code notice at the start of an existing output file, which only
 * sqlcj writes, so sqlcj replaces or deletes only a file that carries it.
 */
final class GeneratedNotice {

    private GeneratedNotice() {
    }

    /**
     * Reports whether one existing file begins with the generated-code notice.
     * A file shorter than the notice cannot carry it.
     */
    static boolean begins(Path file) throws IOException {
        byte[] notice = JavaCodeGenerator.GENERATED_NOTICE.getBytes(StandardCharsets.UTF_8);

        try (InputStream source = Files.newInputStream(file)) {
            return Arrays.equals(notice, source.readNBytes(notice.length));
        } catch (IOException e) {
            throw new IOException(
                "Cannot read existing output file: %s: %s"
                    .formatted(file, FileSystemReason.of(e)),
                e
            );
        }
    }
}
