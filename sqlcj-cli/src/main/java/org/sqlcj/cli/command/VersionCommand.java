package org.sqlcj.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
    name = "version",
    description = "Print SQLCJ version"
)
public class VersionCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.root()
            .commandLine()
            .printVersionHelp(spec.commandLine().getOut());
    }
}
