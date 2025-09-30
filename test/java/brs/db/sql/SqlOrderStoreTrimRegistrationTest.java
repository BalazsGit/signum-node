package brs.db.sql;

import brs.db.store.DerivedTableManager;
import brs.db.store.OrderStore;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SqlOrderStoreTrimRegistrationTest {

  @Test
  public void askAndBidTablesAreRegisteredForTrim() {
    DerivedTableManager derivedTableManager = new DerivedTableManager();
    OrderStore orderStore = new SqlOrderStore(derivedTableManager);

    Set<String> registeredTables = derivedTableManager.getDerivedTables().stream()
      .map(table -> table.getTable())
      .collect(Collectors.toSet());

    assertTrue(registeredTables.contains("ask_order"), "ask_order should be registered for trimming");
    assertTrue(registeredTables.contains("bid_order"), "bid_order should be registered for trimming");
  }
}
