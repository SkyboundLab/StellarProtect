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

    public void loadBlockData(int id, String blockDataString) {
        DataBlock dataBlock = plugin.getDataBlock(blockDataString);
        synchronized (this) {
            BlockTemplate existing = idToBlockTemplate.get(id);
            if (existing != null) {
                if (!blockDataString.equals(existing.getDataBlock().getBlockDataString())) {
                    plugin.getLogger().warning("Skipping database block template id " + id + ": id already in use by a different block created while loading.");
                }
                return;
            }
            currentId.updateAndGet(current -> Math.max(current, id + 1));
            BlockTemplate template = new BlockTemplate(id, dataBlock);
            blockHashToId.put(blockDataString, template);
            blocksCache.put(template);
            idToBlockTemplate.put(template.getId(), template);
        }
    }

    public BlockTemplate getBlockTemplate(String blockDataString) {
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

        List<Integer> ids = new ArrayList<>(unsavedBlocks);
        List<BlockTemplate> templates = new ArrayList<>();
        List<Integer> savedIds = new ArrayList<>();
        for (Integer id : ids) {
            BlockTemplate t = idToBlockTemplate.get(id);
            if (t != null) {
                templates.add(t);
                savedIds.add(id);
            }
        }
        if (!templates.isEmpty()) {
            plugin.getProtectDatabase().saveBlocks(templates, () -> {
                for (Integer id : savedIds) unsavedBlocks.remove(id);
            });
        }
    }

    public int getBlockDataCount() {
        return blockHashToId.size();
    }

}