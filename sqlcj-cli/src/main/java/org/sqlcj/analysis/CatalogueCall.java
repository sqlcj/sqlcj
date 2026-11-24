package org.sqlcj.analysis;

import net.sf.jsqlparser.expression.Expression;

import java.util.List;

/**
 * One call the function catalogue may type, read from the parse tree: the
 * called name as the query writes it, the form the query writes it in, and
 * the arguments in that order.
 */
record CatalogueCall(
    String name,
    FunctionCatalogue.Form form,
    List<Expression> arguments
) {
}
