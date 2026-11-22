package org.sqlcj.io;

import org.sqlcj.generator.GeneratedFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class GeneratedFileWriter {

    /**
     * Reports whether one generated file may replace what stands at its path:
     * nothing, or a file that begins with the generated-code notice, which only
     * sqlcj writes. Output written before any manifest existed is therefore
     * replaced, and a hand-written file is not.
     */
    public boolean isReplaceable(GeneratedFile file, Path outputDirectory) throws IOException {
        Path outputFile = outputDirectory.resolve(file.path());

        if (!Files.isRegularFile(outputFile)) {
            return true;
        }

        return GeneratedNotice.begins(outputFile);
    }

    public void write(GeneratedFile file, Path outputDirectory) throws IOException {
        Path outputFile = outputDirectory.resolve(file.path());

        Path parent = outputFile.getParent();

        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IOException(
                    "Cannot create output directory: %s: %s"
                        .formatted(parent, FileSystemReason.of(e)),
                    e
                );
            }
        }

        try {
            Files.writeString(
                outputFile,
                file.content(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IOException(
                "Cannot write generated file: %s: %s"
                    .formatted(outputFile, FileSystemReason.of(e)),
                e
            );
        }
    }
}
