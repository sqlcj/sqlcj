package org.sqlcj.generator;

import org.sqlcj.analysis.QueryGroupModel;

import java.util.List;

/**
 * Generates the source files of one configured package from the query groups
 * of that package.
 *
 * <p>A generator is used for one configured package: every group of that
 * package is generated through the same generator, in configuration order, and
 * {@link #generateRows()} and {@link #generateEnums()} then yield the row
 * records and enum types those groups share. Each kind of file is written to a
 * subpackage of the configured package, so no two kinds share a directory.
 */
public interface CodeGenerator {

    /**
     * Generates the repository of one query group and records the row records
     * it returns, which the package generates once and every repository
     * returning them shares.
     */
    GeneratedFile generate(QueryGroupModel group);

    /**
     * Generates one row record per table whose complete row a generated group
     * returns, in the order the groups first returned them.
     */
    List<GeneratedFile> generateRows();

    /**
     * Generates one Java enum per enum type a column or parameter of a
     * generated group uses, in the order the groups first used them.
     */
    List<GeneratedFile> generateEnums();
}
