package org.sqlcj.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Records the generated files of one package of one successful run and deletes
 * the files a previous run of that package recorded that this run did not
 * produce.
 *
 * <p>One manifest governs one {@code java.package} inside one {@code java.out}
 * and sits in the directory of that package, so configurations with different
 * packages may share an output directory. Its first line names the
 * configuration file that owns the package, and every line after it is one
 * generated file path relative to the output directory, with {@code /} between
 * its name elements, so a manifest written on one filesystem reads the same on
 * another.
 */
public final class GeneratedOutputManifest {

    /** The manifest file name, inside the generated package's directory. */
    public static final String FILE_NAME = "sqlcj-manifest.txt";

    /** The start of the first line, which names the owning configuration. */
    private static final String CONFIGURATION_PREFIX = "# config: ";

    /** The configuration and the entries one manifest holds. */
    public record Contents(String configuration, List<String> entries) {
    }

    /**
     * The owning configuration one manifest records: the configuration file's
     * path relative to the output directory, with {@code /} between its name
     * elements, so an output directory generated on another machine names the
     * same configuration file.
     */
    public String configuration(Path outputDirectory, Path configFile) {
        return entry(
            outputDirectory.toAbsolutePath()
                .normalize()
                .relativize(configFile.toAbsolutePath().normalize())
        );
    }

    /**
     * The contents of the manifest a previous run of this package wrote, or
     * nothing when the package holds no manifest.
     */
    public Optional<Contents> read(Path outputDirectory, String packageName) throws IOException {
        Path manifestPath = manifestPath(outputDirectory, packageName);

        if (!Files.exists(manifestPath)) {
            return Optional.empty();
        }

        List<String> lines;

        try {
            lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException(
                "Cannot read output manifest: %s: %s"
                    .formatted(manifestPath, FileSystemReason.of(e)),
                e
            );
        }

        if (lines.isEmpty()) {
            return Optional.of(new Contents("", List.of()));
        }

        return Optional.of(
            new Contents(
                configurationOf(lines.get(0)),
                List.copyOf(lines.subList(1, lines.size()))
            )
        );
    }

    /** Reports whether the entries of a previous manifest list one path. */
    public boolean lists(List<String> entries, Path generatedPath) {
        return entries.contains(entry(generatedPath));
    }

    /**
     * Deletes every generated file the previous manifest listed that this run
     * did not produce, and nothing else. An entry that does not name a regular
     * file inside the output directory is left alone.
     */
    public void deleteStale(
        Path outputDirectory,
        List<String> previousEntries,
        List<Path> generatedPaths
    ) throws IOException {
        List<String> producedEntries = entries(generatedPaths);

        for (String previousEntry : previousEntries) {
            if (producedEntries.contains(previousEntry)) {
                continue;
            }

            deleteStaleEntry(outputDirectory, previousEntry, generatedPaths);
        }
    }

    /**
     * Records the owning configuration and the generated paths of one
     * successful run of one package, one entry per line.
     */
    public void write(
        Path outputDirectory,
        String packageName,
        String configuration,
        List<Path> generatedPaths
    ) throws IOException {
        Path manifestPath = manifestPath(outputDirectory, packageName);

        StringBuilder content = new StringBuilder()
            .append(CONFIGURATION_PREFIX)
            .append(configuration)
            .append('\n');

        entries(generatedPaths)
            .stream()
            .sorted()
            .forEach(entry -> content.append(entry).append('\n'));

        try {
            Files.createDirectories(manifestPath.getParent());

            Files.writeString(
                manifestPath,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IOException(
                "Cannot write output manifest: %s: %s"
                    .formatted(manifestPath, FileSystemReason.of(e)),
                e
            );
        }
    }

    /**
     * Deletes one stale entry, skipping an entry that does not name a regular
     * file inside the output directory and an entry that names a file this run
     * wrote, which a rename differing only by case produces on a
     * case-insensitive filesystem.
     */
    private void deleteStaleEntry(
        Path outputDirectory,
        String entry,
        List<Path> generatedPaths
    ) throws IOException {
        Path relativePath = relativePath(entry);

        if (relativePath == null) {
            return;
        }

        Path file = outputDirectory.resolve(relativePath);

        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }

            if (isGenerated(file, outputDirectory, generatedPaths)) {
                return;
            }

            Files.delete(file);
        } catch (IOException e) {
            throw new IOException(
                "Cannot delete stale generated file: %s: %s"
                    .formatted(file, FileSystemReason.of(e)),
                e
            );
        }
    }

    /**
     * The relative path of one manifest entry, or {@code null} when the entry
     * is not a normalized relative path that stays inside the output
     * directory.
     */
    private Path relativePath(String entry) {
        if (entry.isEmpty()) {
            return null;
        }

        Path path;

        try {
            path = Path.of(entry);
        } catch (InvalidPathException e) {
            return null;
        }

        if (path.getRoot() != null) {
            return null;
        }

        if (path.getName(0).toString().equals("..")) {
            return null;
        }

        if (!entry.equals(entry(path.normalize()))) {
            return null;
        }

        return path;
    }

    private boolean isGenerated(
        Path file,
        Path outputDirectory,
        List<Path> generatedPaths
    ) throws IOException {
        for (Path generatedPath : generatedPaths) {
            if (Files.isSameFile(file, outputDirectory.resolve(generatedPath))) {
                return true;
            }
        }

        return false;
    }

    private List<String> entries(List<Path> generatedPaths) {
        List<String> entries = new ArrayList<>(generatedPaths.size());

        for (Path generatedPath : generatedPaths) {
            entries.add(entry(generatedPath));
        }

        return entries;
    }

    /** One entry, joining the path's name elements with {@code /}. */
    private String entry(Path path) {
        StringBuilder entry = new StringBuilder();

        for (Path name : path) {
            if (!entry.isEmpty()) {
                entry.append('/');
            }

            entry.append(name);
        }

        return entry.toString();
    }

    /** The manifest of one package, inside that package's own directory. */
    private Path manifestPath(Path outputDirectory, String packageName) {
        String[] segments = packageName.split("\\.");

        return outputDirectory
            .resolve(Path.of(segments[0], Arrays.copyOfRange(segments, 1, segments.length)))
            .resolve(FILE_NAME);
    }

    /** The configuration the first line of a manifest names. */
    private String configurationOf(String line) {
        if (!line.startsWith(CONFIGURATION_PREFIX)) {
            return "";
        }

        return line.substring(CONFIGURATION_PREFIX.length());
    }
}
