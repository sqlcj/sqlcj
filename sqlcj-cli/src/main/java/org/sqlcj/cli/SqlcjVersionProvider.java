package org.sqlcj.cli;

import org.sqlcj.Main;
import picocli.CommandLine.IVersionProvider;

/**
 * Single source of the version that {@code version}, {@code --version}, and
 * {@code -v} print: the {@code Implementation-Version} of the manifest the
 * packaged CLI JAR carries.
 */
public class SqlcjVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] { Main.class.getPackage().getImplementationVersion() };
    }
}
