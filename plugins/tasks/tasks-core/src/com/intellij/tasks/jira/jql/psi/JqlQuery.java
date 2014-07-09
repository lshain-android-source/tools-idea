package com.intellij.tasks.jira.jql.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Mikhail Golubev
 */
public interface JqlQuery extends JqlElement {
  @Nullable
  JqlClause getClause();
  boolean isOrdered();
  @NotNull
  JqlSortKey[] getOrderKeys();
}
