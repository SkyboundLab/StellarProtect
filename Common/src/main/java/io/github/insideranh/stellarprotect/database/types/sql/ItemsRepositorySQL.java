package io.github.insideranh.stellarprotect.database.types.sql;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.insideranh.stellarprotect.StellarProtect;
import io.github.insideranh.stellarprotect.database.repositories.ItemsRepository;
import io.github.insideranh.stellarprotect.items.ItemTemplate;
import io.github.insideranh.stellarprotect.utils.Debugger;
import io.github.insideranh.stellarprotect.utils.InventorySerializable;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ItemsRepositorySQL implements ItemsRepository {

    private final StellarProtect stellarProtect = StellarProtect.getInstance();
    private final Connection connection;

    public ItemsRepositorySQL(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void saveItems(List<ItemTemplate> itemTemplates, Runnable onSaved) {
        stellarProtect.getExecutor().execute(() -> {
            String sql = "INSERT OR REPLACE INTO " + stellarProtect.getConfigManager().getTablesItemTemplates() + " (id, base64, s) VALUES (?, ?, ?)";

            // The SQLite Connection is shared by every repository and used
            // from several executor threads at once (log flush every 15s,
            // template saves every 30s, lookups). A JDBC Connection must
            // never be used concurrently, and these saves toggle its
            // auto-commit mode: a logger save restoring auto-commit
            // mid-batch made this save's commit() throw "cannot commit -
            // no transaction is active". Serialise all use of the
            // connection so each operation's auto-commit handling is
            // atomic, and always leave auto-commit restored.
            synchronized (connection) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {

                    connection.setAutoCommit(false);

                    int batchSize = 0;
                    int maxBatchSize = 1000;

                    for (ItemTemplate template : itemTemplates) {
                        if (template == null || template.getBase64() == null) continue;
                        statement.setLong(1, template.getId());
                        statement.setString(2, template.getBase64());
                        statement.setByte(3, (byte) 0);
                        statement.addBatch();

                        batchSize++;

                        if (batchSize >= maxBatchSize) {
                            statement.executeBatch();
                            connection.commit();
                            statement.clearBatch();
                            batchSize = 0;
                        }
                    }

                    if (batchSize > 0) {
                        statement.executeBatch();
                    }

                    connection.commit();
                    Debugger.debugSave("Saved " + itemTemplates.size() + " item templates in SQLite");
                    if (onSaved != null) onSaved.run();
                } catch (SQLException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException ignored) {
                    }
                    stellarProtect.getLogger().info("Error on save items in SQLite: " + e.getMessage());
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException ignored) {
                    }
                }
            }
        });
    }

    @Override
    public void loadMostUsedItems() {
        ListeningExecutorService executor = MoreExecutors.listeningDecorator(
                new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1024))
        );

        executor.execute(() -> {
            String sql = "SELECT id, base64, s, access_count, last_accessed, total_quantity_used, created_at " +
                    "FROM " + stellarProtect.getConfigManager().getTablesItemTemplates() + " " +
                    "ORDER BY access_count DESC, total_quantity_used DESC";

            // Read the rows under the connection lock, then feed the
            // manager outside it: deserialising and inserting templates
            // must not hold the shared connection while other threads
            // wait to save.
            List<Long> ids = new ArrayList<>();
            List<String> base64s = new ArrayList<>();
            synchronized (connection) {
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {

                    while (resultSet.next()) {
                        ids.add(resultSet.getLong("id"));
                        base64s.add(resultSet.getString("base64"));
                    }
                } catch (SQLException e) {
                    stellarProtect.getLogger().info("Error en loadMostUsedItems: " + e.getMessage());
                    return;
                }
            }

            long maxId = -1;
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i);
                String base64 = base64s.get(i);

                ItemStack bukkitItem = InventorySerializable.itemStackFromBase64(base64);

                ItemTemplate template = new ItemTemplate(id, bukkitItem, base64);

                stellarProtect.getItemsManager().loadItemReference(template, base64);
                maxId = Math.max(maxId, id);
            }
            long count = stellarProtect.getItemsManager().getItemReferenceCount();

            // Next id must clear the highest id in the database, not
            // just the row count: ids are not necessarily dense, and
            // count + 1 could reuse an id that still exists.
            final long loadedMaxId = maxId;
            stellarProtect.getItemsManager().getCurrentId().updateAndGet(current -> Math.max(current, loadedMaxId + 1L));
            Debugger.debugLog("Loaded " + count + " item references.");
        });

        executor.shutdown();
    }

}
