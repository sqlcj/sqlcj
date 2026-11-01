package org.sqlcj.cli.command;

import org.sqlcj.compiler.CompilationException;
import org.sqlcj.compiler.SqlcjCompiler;
import org.sqlcj.config.Config;
import org.sqlcj.config.ConfigFileLocator;
import org.sqlcj.config.ConfigLoader;
import org.sqlcj.config.ConfigurationException;
import org.sqlcj.config.YamlConfigLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "generate",
    description = "Generate Java sources from SQL queries"
)
public class GenerateCommand implements Callable<Integer> {

    private final ConfigLoader configLoader = new YamlConfigLoader();

    @Spec
    CommandSpec spec;

    @Option(
        names = "--config",
        paramLabel = "<path>",
        description = """
            Configuration file to generate from. A relative path is \
            resolved against the current directory, while relative paths \
            inside the file are resolved against its own directory. \
            Without this option, sqlcj.yaml or sqlcj.yml in the current \
            directory is used, and holding both is an error.\
            """
    )
    Path configFile;

    @Override
    public Integer call() {
        try {
            Path file = configFile == null
                ? ConfigFileLocator.locate(Path.of("").toAbsolutePath())
                : configFile;

            Config config = configLoader.load(file);
            SqlcjCompiler compiler = new SqlcjCompiler();
            compiler.compile(config);
            return CommandLine.ExitCode.OK;
        } catch (ConfigurationException e) {
            spec.commandLine()
                .getErr()
                .println("sqlcj: " + e.getMessage());

            return CommandLine.ExitCode.SOFTWARE;
        } catch (CompilationException e) {
            PrintWriter err = spec.commandLine().getErr();

            for (String diagnostic : e.diagnostics()) {
                err.println("sqlcj: " + diagnostic);
            }

            return CommandLine.ExitCode.SOFTWARE;
        }
    }
}
