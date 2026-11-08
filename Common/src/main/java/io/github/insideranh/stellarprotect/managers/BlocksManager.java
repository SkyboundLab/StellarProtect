package io.github.insideranh.stellarprotect.managers;

import com.google.common.collect.Sets;
import io.github.insideranh.stellarprotect.StellarProtect;
import io.github.insideranh.stellarprotect.blocks.BlockTemplate;
import io.github.insideranh.stellarprotect.blocks.DataBlock;
import io.github.insideranh.stellarprotect.cache.BlocksCache;
import lombok.Getter;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
public class BlocksManager {

    private final StellarProtect plugin = StellarProtect.getInstance();

    private final BlocksCache blocksCache = new BlocksCache();

    // String -> BlockTemplate
    private final Map<String, BlockTemplate> blockHashToId = new ConcurrentHashMap<>(100);
    // BlockId -> BlockTemplate
    private final Map<Integer, BlockTemplate> idToBlockTemplate = new ConcurrentHashMap<>(100);
    private final Set<Integer> unsavedBlocks = Sets.newConcurrentHashSet();
    private final AtomicInteger currentId = new AtomicInteger(1);

    public void load() {

    }

    public BlockTemplate getBlockTemplate(int id) {
        return idToBlockTemplate.get(id);
    }

    // Called from the async database loader (loadBlockDatas) while the main
    // thread is already creating templates from block events, so the whole
    // insert must be atomic with createBlockTemplate below.
    public void loadBlockData(int id, String blockDataString) {
        // getDataBlock touches no shared state, so it stays outside the lock.
        DataBlock dataBlock = plugin.getDataBlock(blockDataString);
        synchronized (this) {
            BlockTemplate existing = idToBlockTemplate.get(id);
            if (existing != null) {
                // Already loaded (or created live under this same id). Never
                // overwrite it: a live-created template and a database row
                // sharing an id would otherwise corrupt each other's lookups.
                if (!blockDataString.equals(existing.getDataBlock().getBlockDataString())) {
                    plugin.getLogger().warning("Skipping database block template id " + id + ": id already in use by a different block created while loading.");
                }
                return;
            }
            // Keep newly created ids ahead of every id seen in the database,
            // so a template created live during the load can never reuse an
            // id the loader has not reached yet.
            currentId.updateAndGet(current -> Math.max(current, id + 1));
            BlockTemplate template = new BlockTemplate(id, dataBlock);
            blockHashToId.put(blockDataString, template);
            blocksCache.put(template);
            idToBlockTemplate.put(template.getId(), template);
        }
    }

    public BlockTemplate getBlockTemplate(String blockDataString) {
        // getDataBlock touches no shared state, so it stays outside the
        // lock; only the lookup-or-create below must be atomic, otherwise
        // two threads racing the same unseen block create two templates
        // with different ids for it.
        synchronized (this) {
            BlockTemplate template = blockHashToId.get(blockDataString);
            if (template != null) {
                return template;
            }
            DataBlock dataBlock = plugin.getDataBlock(blockDataString);
            return createBlockTemplate(dataBlock);
        }
    }

    public BlockTemplate getBlockTemplate(BlockState block) {
        String blockDataString = plugin.getProtectNMS().getBlockData(block);
        synchronized (this) {
            BlockTemplate template = blockHashToId.get(blockDataString);
            if (template != null) {
                return template;
            }
            DataBlock dataBlock = plugin.getDataBlock(block);
            return createBlockTemplate(dataBlock);
        }
    }

    public BlockTemplate getBlockTemplate(Block block) {
        String blockDataString = plugin.getProtectNMS().getBlockData(block);
        synchronized (this) {
            BlockTemplate template = blockHashToId.get(blockDataString);
            if (template != null) {
                return template;
            }
            DataBlock dataBlock = plugin.getDataBlock(block);
            return createBlockTemplate(dataBlock);
        }
    }

    public synchronized BlockTemplate createBlockTemplate(DataBlock dataBlock) {
        // Re-check under the lock: another thread may have created this
        // exact block while the caller was reading its block data.
        BlockTemplate existing = blockHashToId.get(dataBlock.getBlockDataString());
        if (existing != null) {
            return existing;
        }

        int id = currentId.getAndIncrement();

        BlockTemplate template = new BlockTemplate(id, dataBlock);
        blocksCache.put(template);
        unsavedBlocks.add(id);
        blockHashToId.put(dataBlock.getBlockDataString(), template);
        idToBlockTemplate.put(id, template);

        return template;
    }

    public void saveBlocks() {
        if (unsavedBlocks.isEmpty()) return;

        List<BlockTemplate> templates = new ArrayList<>();
        unsavedBlocks.forEach(id -> templates.add(idToBlockTemplate.get(id)));
        unsavedBlocks.clear();
        plugin.getProtectDatabase().saveBlocks(templates);
    }

    public int getBlockDataCount() {
        return blockHashToId.size();
    }

}