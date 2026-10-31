package org.sqlcj.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The configuration file of a directory, when no configuration file was named
 * explicitly.
 *
 * <p>A directory declares its configuration as either {@code sqlcj.yaml} or
 * {@code sqlcj.yml}. Neither name takes precedence, so a directory that holds
 * both is refused instead of chosen between.
 */
public final class ConfigFileLocator {

    private static final String YAML_NAME = "sqlcj.yaml";

    private static final String YML_NAME = "sqlcj.yml";

    private ConfigFileLocator() {
    }

    /**
     * The configuration file to load from {@code directory}.
     *
     * @throws ConfigurationException if the directory holds both supported
     *     names or neither of them
     */
    public static Path locate(Path directory) {
        Path base = directory.toAbsolutePath().normalize();

        Path yaml = base.resolve(YAML_NAME);
        Path yml = base.resolve(YML_NAME);

        boolean hasYaml = Files.exists(yaml);
        boolean hasYml = Files.exists(yml);

        if (hasYaml && hasYml) {
            throw new ConfigurationException(
                "Conflicting configuration files: %s and %s: remove one or pass --config"
                    .formatted(yaml, yml)
            );
        }

        if (hasYaml) {
            return yaml;
        }

        if (hasYml) {
            return yml;
        }

        throw new ConfigurationException(
            "Cannot find configuration file: %s: neither %s nor %s exists"
                .formatted(base, YAML_NAME, YML_NAME)
        );
    }
}
