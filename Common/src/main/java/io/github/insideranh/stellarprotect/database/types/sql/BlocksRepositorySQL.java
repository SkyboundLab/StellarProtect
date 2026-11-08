package io.github.insideranh.stellarprotect.database.types.sql;

import io.github.insideranh.stellarprotect.StellarProtect;
import io.github.insideranh.stellarprotect.blocks.BlockTemplate;
import io.github.insideranh.stellarprotect.database.repositories.BlocksRepository;
import io.github.insideranh.stellarprotect.utils.Debugger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class BlocksRepositorySQL implements BlocksRepository {

    private final StellarProtect stellarProtect = StellarProtect.getInstance();
    private final Connection connection;

    public BlocksRepositorySQL(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void saveBlocks(List<BlockTemplate> blockTemplates, Runnable onSaved) {
        stellarProtect.getExecutor().execute(() -> {
            String sql = "INSERT OR REPLACE INTO " + stellarProtect.getConfigManager().getTablesBlockTemplates() + " (id, block_data) VALUES (?, ?)";

            // Same shared-connection race as ItemsRepositorySQL: the
            // SQLite Connection is used from several executor threads at
            // once, and a logger save restoring auto-commit mid-batch
            // made this save's commit() throw "cannot commit - no
            // transaction is active". Serialise all use of the
            // connection, and always leave auto-commit restored.
            synchronized (connection) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {

                    connection.setAutoCommit(false);

                    int batchSize = 0;
                    int maxBatchSize = 1000;

                    for (BlockTemplate template : blockTemplates) {
                        if (template == null) continue;
                        statement.setLong(1, template.getId());
                        statement.setString(2, template.getDataBlock().getBlockDataString());
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
                    Debugger.debugSave("Saved " + blockTemplates.size() + " block templates in SQLite");
                    if (onSaved != null) onSaved.run();
                } catch (Exception e) {
                    try {
                        connection.rollback();
                    } catch (Exception ignored) {
                    }
                    stellarProtect.getLogger().info("Error on save blocks in SQLite: " + e.getMessage());
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    @Override
    public void loadBlockDatas() {
        stellarProtect.getExecutor().execute(() -> {
            String sql = "SELECT id, block_data FROM " + stellarProtect.getConfigManager().getTablesBlockTemplates();

            // Read the rows under the connection lock, then feed the
            // manager outside it: building DataBlocks and inserting
            // templates must not hold the shared connection while other
            // threads wait to save.
            List<Integer> ids = new ArrayList<>();
            List<String> blockDatas = new ArrayList<>();
            synchronized (connection) {
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {

                    while (resultSet.next()) {
                        ids.add(resultSet.getInt("id"));
                        blockDatas.add(resultSet.getString("block_data"));
                    }
                } catch (Exception e) {
                    stellarProtect.getLogger().info("Error en loadMostUsedItems: " + e.getMessage());
                    return;
                }
            }

            int maxId = -1;
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i);
                stellarProtect.getBlocksManager().loadBlockData(id, blockDatas.get(i));
                maxId = Math.max(maxId, id);
            }
            int count = stellarProtect.getBlocksManager().getBlockDataCount();

            // Next id must clear the highest id in the database, not
            // just the row count: ids are not necessarily dense, and
            // count + 1 could reuse an id that still exists.
            final int loadedMaxId = maxId;
            stellarProtect.getBlocksManager().getCurrentId().updateAndGet(current -> Math.max(current, loadedMaxId + 1));
            stellarProtect.getLogger().info("Loaded " + count + " block templates.");
        });
    }

}
