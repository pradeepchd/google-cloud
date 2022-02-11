package io.cdap.plugin.gcp.bigquery.relational;

import io.cdap.cdap.etl.api.aggregation.DeduplicateAggregationDefinition;
import io.cdap.cdap.etl.api.aggregation.GroupByAggregationDefinition;
import io.cdap.cdap.etl.api.relational.Expression;
import io.cdap.cdap.etl.api.relational.InvalidRelation;
import io.cdap.cdap.etl.api.relational.Relation;
import io.cdap.plugin.gcp.bigquery.sqlengine.BigQuerySQLDataset;
import io.cdap.plugin.gcp.bigquery.sqlengine.builder.BigQueryDeduplicateSQLBuilder;
import io.cdap.plugin.gcp.bigquery.sqlengine.builder.BigQueryGroupBySQLBuilder;
import io.cdap.plugin.gcp.bigquery.sqlengine.builder.BigQueryNestedSelectSQLBuilder;
import io.cdap.plugin.gcp.bigquery.sqlengine.builder.BigQuerySelectSQLBuilder;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * An implementation of {@link Relation} designed to operate on BigQuery.
 */
public class BigQueryRelation implements Relation {
  private static final SQLExpressionFactory factory = new SQLExpressionFactory();

  private final Set<String> columns;
  private final BigQuerySQLDataset sourceDataset;
  private final BigQueryRelation parent;
  private final String transformExpression;
  private final boolean isValid;
  private final String validationError;

  /**
   * Gets a new BigQueryRelation instance
   *
   * @param bqProject     Project where the source SQL table is stored
   * @param bqDataset     Dataset where the source SQL table is stored
   * @param sourceDataset source SQL Dataset instance
   * @param columnNames   column names to use when initializing this relation.
   * @return new BigQueryRelation instance for this table.
   */
  public static BigQueryRelation getInstance(String bqProject,
                                             String bqDataset,
                                             BigQuerySQLDataset sourceDataset,
                                             Set<String> columnNames) {
    Map<String, Expression> selectedColumns = getSelectedColumns(columnNames);
    String sourceTableName = String.format("`%s.%s.%s`", bqProject, bqDataset, sourceDataset.getBigQueryTableName());
    String transformExpression = buildBaseSelect(selectedColumns, sourceTableName, sourceDataset.getDatasetName());

    return new BigQueryRelation(sourceDataset, columnNames, null, transformExpression, true, null);
  }

  protected BigQueryRelation(BigQuerySQLDataset sourceDataset,
                          Set<String> columns,
                          BigQueryRelation parent,
                          String transformExpression,
                          boolean isValid,
                          String validationError) {
    this.columns = columns;
    this.sourceDataset = sourceDataset;
    this.parent = parent;
    this.transformExpression = transformExpression;
    this.isValid = true;
    this.validationError = null;
  }

  private Relation getInvalidRelation(String validationError) {
    return new InvalidRelation(validationError);
  }

  @Override
  public boolean isValid() {
    return isValid;
  }

  @Override
  public String getValidationError() {
    return isValid() ? null : validationError;
  }

  public Relation getParent() {
    return parent;
  }

  public String getTransformExpression() {
    return transformExpression;
  }

  @Override
  public Relation setColumn(String column, Expression value) {
    // check if expression is supported and valid
    if (!supportsExpression(value)) {
      return getInvalidRelation("Unsupported or invalid expression type.");
    }

    Map<String, Expression> selectedColumns = getSelectedColumns(columns);
    selectedColumns.put(column, value);

    // Build new transform expression and return new instance.
    String expression = buildNestedSelect(selectedColumns, transformExpression, sourceDataset.getDatasetName(), null);
    return new BigQueryRelation(sourceDataset, selectedColumns.keySet(), this, expression, true, null);
  }

  @Override
  public Relation dropColumn(String column) {
    Map<String, Expression> selectedColumns = getSelectedColumns(columns);
    selectedColumns.remove(column);

    // Build new transform expression and return new instance.
    String expression = buildNestedSelect(selectedColumns, transformExpression, sourceDataset.getDatasetName(), null);
    return new BigQueryRelation(sourceDataset, selectedColumns.keySet(), this, expression, true, null);
  }

  @Override
  public Relation select(Map<String, Expression> columns) {
    // check if all expressions are supported and valid
    if (!supportsExpressions(columns.values())) {
      return getInvalidRelation("Unsupported or invalid expression type.");
    }

    // Build new transform expression and return new instance.
    String expression = buildNestedSelect(columns, transformExpression, sourceDataset.getDatasetName(), null);
    return new BigQueryRelation(sourceDataset, columns.keySet(), this, expression, true, null);
  }

  @Override
  public Relation filter(Expression filter) {
    // check if expression is supported and valid
    if (!supportsExpression(filter)) {
      return getInvalidRelation("Unsupported or invalid expression type.");
    }

    Map<String, Expression> selectedColumns = getSelectedColumns(columns);
    String expression = buildNestedSelect(selectedColumns, transformExpression, sourceDataset.getDatasetName(), filter);
    return new BigQueryRelation(sourceDataset, columns, this, expression, true, null);
  }

  @Override
  public Relation groupBy(GroupByAggregationDefinition definition) {
    // Ensure all expressions supplied in this definition are supported and valid
    if (!supportsGroupByAggregationDefinition(definition)) {
      return getInvalidRelation("DeduplicateAggregationDefinition contains " +
                                  "unsupported or invalid expressions");
    }

    Set<String> columns = definition.getSelectExpressions().keySet();
    String expression = buildGroupBy(definition, transformExpression, sourceDataset.getDatasetName());
    return new BigQueryRelation(sourceDataset, columns, this, expression, true, null);
  }

  @Override
  public Relation deduplicate(DeduplicateAggregationDefinition definition) {
    // Ensure all expressions supplied in this definition are supported and valid
    if (!supportsDeduplicateAggregationDefinition(definition)) {
      return getInvalidRelation("DeduplicateAggregationDefinition contains " +
                                  "unsupported or invalid expressions");
    }

    Set<String> columns = definition.getSelectExpressions().keySet();
    String expression = buildDeduplicate(definition, transformExpression, sourceDataset.getDatasetName());
    return new BigQueryRelation(sourceDataset, columns, this, expression, true, null);
  }

  private static String buildBaseSelect(Map<String, Expression> columns,
                                        String sourceExpression,
                                        String datasetName) {
    // Instantiate query builder and generate select expression
    BigQuerySelectSQLBuilder builder = new BigQuerySelectSQLBuilder(columns,
                                                                    sourceExpression,
                                                                    datasetName,
                                                                    null);
    return builder.getQuery();
  }

  private static String buildNestedSelect(Map<String, Expression> columns,
                                          String sourceExpression,
                                          String datasetName,
                                          @Nullable Expression filter) {
    // Get filter conditions
    String filterCondition = filter != null ? ((SQLExpression) filter).getExpression() : null;

    // Instantiate query builder and generate select expression
    BigQueryNestedSelectSQLBuilder builder = new BigQueryNestedSelectSQLBuilder(columns,
                                                                                sourceExpression,
                                                                                datasetName,
                                                                                filterCondition);
    return builder.getQuery();
  }

  private static String buildGroupBy(GroupByAggregationDefinition group,
                                     String sourceExpression,
                                     String datasetName) {
    // Instantiate query builder and generate select expression
    BigQueryGroupBySQLBuilder builder = new BigQueryGroupBySQLBuilder(group,
                                                                      sourceExpression,
                                                                      datasetName);
    return builder.getQuery();
  }

  private static String buildDeduplicate(DeduplicateAggregationDefinition deduplicateAggregationDefinition,
                                         String sourceExpression,
                                         String datasetName) {
    // Instantiate query builder and generate select expression
    BigQueryDeduplicateSQLBuilder builder = new BigQueryDeduplicateSQLBuilder(deduplicateAggregationDefinition,
                                                                              sourceExpression,
                                                                              datasetName);
    return builder.getQuery();
  }

  /**
   * Builds selected columns map based on an input set of columns.
   * <p>
   * The output Map maintains the field order from the supplied set.
   *
   * @param columns set containing columns to select
   * @return Map containing column aliases and column values.
   */
  private static Map<String, Expression> getSelectedColumns(Set<String> columns) {
    Map<String, Expression> selectedColumns = new LinkedHashMap<>();
    columns.forEach(c -> selectedColumns.put(c, factory.compile(c)));
    return selectedColumns;
  }

  /**
   * Check if all expressions contained in a {@link GroupByAggregationDefinition} are supported.
   *
   * @param def {@link GroupByAggregationDefinition} to verify.
   * @return boolean specifying if all expressions are supported or not.
   */
  private boolean supportsGroupByAggregationDefinition(GroupByAggregationDefinition def) {
    // Gets all expressions defined in this definition
    Collection<Expression> selectExpressions = def.getSelectExpressions().values();
    Collection<Expression> groupByExpressions = def.getGroupByExpressions();

    // Verify all supplied expressions are both supported and valid.
    return supportsExpressions(selectExpressions)
      && supportsExpressions(groupByExpressions);

  }

  /**
   * Check if all expressions contained in a {@link DeduplicateAggregationDefinition} are supported.
   *
   * @param def {@link DeduplicateAggregationDefinition} to verify.
   * @return boolean specifying if all expressions are supported or not.
   */
  private boolean supportsDeduplicateAggregationDefinition(DeduplicateAggregationDefinition def) {
    // Gets all expressions defined in this definition
    Collection<Expression> selectExpressions = def.getSelectExpressions().values();
    Collection<Expression> dedupExpressions = def.getGroupByExpressions();
    Collection<Expression> orderExpressions = def.getFilterExpressions()
      .stream()
      .map(DeduplicateAggregationDefinition.FilterExpression::getExpression)
      .collect(Collectors.toSet());

    // Verify all supplied expressions are both supported and valid.
    return supportsExpressions(selectExpressions)
      && supportsExpressions(dedupExpressions)
      && supportsExpressions(orderExpressions);

  }

  /**
   * Check if a collection of expressions are all valid
   *
   * @param expressions collection containing expressions to verify
   * @return boolean specifying if all expressions are supported or not.
   */
  private boolean supportsExpressions(Collection<Expression> expressions) {
    return expressions.stream().allMatch(this::supportsExpression);
  }

  /**
   * Check if an expression is valid
   *
   * @param expression expression to verity
   * @return boolean specifying if the expression is supported and valid.
   */
  private boolean supportsExpression(Expression expression) {
    return expression instanceof SQLExpression && expression.isValid();
  }

}
