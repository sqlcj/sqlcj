package org.sqlcj.cli;

import org.sqlcj.cli.command.GenerateCommand;
import org.sqlcj.cli.command.VersionCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

@Command(
    name = "sqlcj",
    description = "SQL compiler and type-safe code generator for Java",
    versionProvider = SqlcjVersionProvider.class,
    subcommands = {
        VersionCommand.class,
        GenerateCommand.class
    }
)
public class SqlcjCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Option(
        names = { "-h", "--help" },
        usageHelp = true,
        scope = ScopeType.INHERIT,
        description = "Show this help message and exit."
    )
    boolean helpRequested;

    @Option(
        names = { "-v", "--version" },
        versionHelp = true,
        description = "Print version information and exit."
    )
    boolean versionRequested;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
