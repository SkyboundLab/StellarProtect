package io.github.insideranh.stellarprotect.managers;

import io.github.insideranh.stellarprotect.StellarProtect;
import io.github.insideranh.stellarprotect.cache.ItemsCache;
import io.github.insideranh.stellarprotect.items.ItemReference;
import io.github.insideranh.stellarprotect.items.ItemTemplate;
import io.github.insideranh.stellarprotect.utils.InventorySerializable;
import io.github.insideranh.stellarprotect.utils.StringCleanerUtils;
import lombok.Getter;
import lombok.NonNull;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Getter
public class ItemsManager {

    private final ConcurrentHashMap<String, Long> itemHashToId = new ConcurrentHashMap<>(1000);
    private final ConcurrentHashMap<Long, ItemTemplate> idToItemTemplate = new ConcurrentHashMap<>(1000);
    private final ItemsCache itemCache = new ItemsCache();
    private final Set<Long> unsavedTemplates = com.google.common.collect.Sets.newConcurrentHashSet();
    private final StellarProtect plugin = StellarProtect.getInstance();
    // Item ids are 1-based everywhere (getItemTemplate treats id <= 0 as
    // "no template", and BlocksManager already starts at 1). Starting at
    // 0 meant the very first template on a fresh database got id 0 and
    // could never be retrieved or saved: it stayed in unsavedTemplates
    // forever and its row never reached the database.
    private final AtomicLong currentId = new AtomicLong(1);

    public void load() {
        String selectedLang = plugin.getConfigManager().getItemsLang();
        if (selectedLang.equalsIgnoreCase("en")) {
            StringCleanerUtils.setHasTranslation(false);
            return;
        }

        if (selectedLang.equalsIgnoreCase("zh")) {
            selectedLang = "zh_cn";
            plugin.getLogger().info("Legacy 'zh' language detected, using 'zh_cn' (Simplified Chinese) instead. Consider updating your config to use 'zh_cn' or 'zh_tw' explicitly.");
        }

        plugin.getLogger().info("Loading items translations...");

        File folder = new File(plugin.getDataFolder(), "translations");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File file = new File(folder, "items_" + selectedLang + ".yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("translations/items_" + selectedLang + ".yml", false);
                plugin.getLogger().info("Saved default items translations file for " + selectedLang + ".");
            } catch (Exception exception) {
                plugin.getLogger().info("The translation file for " + selectedLang + " does not exist. Using default en, if you need a translation, please contact InsiderAnh on Discord.");
                return;
            }
        }

        StringCleanerUtils.setHasTranslation(true);

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        Set<String> keys = config.getKeys(false);
        for (String key : keys) {
            StringCleanerUtils.addMaterialTranslation(key.toUpperCase(), config.getString(key));
        }
        plugin.getLogger().info("Loaded " + keys.size() + " items translations for " + selectedLang + ".");
    }

    // Called from the async database loader (loadMostUsedItems) while the
    // main thread is already creating templates from events, so the whole
    // insert must be atomic with createItemTemplate below.
    public synchronized void loadItemReference(ItemTemplate template, String fullBase64) {
        ItemTemplate existing = idToItemTemplate.get(template.getId());
        if (existing != null) {
            // Already loaded (or created live under this same id). Never
            // overwrite it: a live-created template and a database row
            // sharing an id would otherwise corrupt each other's lookups.
            if (!fullBase64.equals(existing.getBase64())) {
                plugin.getLogger().warning("Skipping database item template id " + template.getId() + ": id already in use by a different item created while loading.");
            }
            return;
        }
        // Keep newly created ids ahead of every id seen in the database,
        // so a template created live during the load can never reuse an
        // id the loader has not reached yet.
        currentId.updateAndGet(current -> Math.max(current, template.getId() + 1));
        itemHashToId.put(fullBase64, template.getId());
        idToItemTemplate.put(template.getId(), template);
        itemCache.put(template);
    }

    @NonNull
    public ItemReference getItemReference(ItemStack itemStack) {
        return getItemReference(itemStack, itemStack.getAmount());
    }

    @NonNull
    public ItemReference getItemReference(ItemStack itemStack, int amount) {
        if (itemStack == null || itemStack.getType().equals(Material.AIR)) {
            return new ItemReference(-1, 1);
        }

        ItemStack reduced = itemStack.clone();
        reduced.setAmount(1);

        // Serialisation touches no shared state, so it stays outside the
        // lock; only the lookup-or-create below must be atomic, otherwise
        // two threads racing the same unseen item create two templates
        // with different ids for it.
        String base64 = InventorySerializable.itemStackToBase64(reduced).replace("\n", "").replace("\r", "");
        synchronized (this) {
            Long templateId = itemHashToId.get(base64);
            if (templateId != null) {
                return new ItemReference(templateId, amount);
            }

            templateId = createItemTemplate(itemStack, base64);
            return new ItemReference(templateId, amount);
        }
    }

    public ItemTemplate getItemTemplate(long id) {
        if (id <= 0) return null;
        ItemTemplate template = idToItemTemplate.get(id);
        if (template != null) return template;
        return itemCache.getById(id);
    }

    public synchronized long createItemTemplate(ItemStack itemStack, String base64) {
        // Re-check under the lock: another thread may have created this
        // exact item while the caller was serialising it.
        Long existingId = itemHashToId.get(base64);
        if (existingId != null) {
            return existingId;
        }

        long id = currentId.getAndIncrement();

        ItemTemplate template = new ItemTemplate(id, itemStack, base64);
        unsavedTemplates.add(id);
        idToItemTemplate.put(id, template);
        itemCache.put(template);
        itemHashToId.put(base64, id);
        return id;
    }

    public void saveItems() {
        if (unsavedTemplates.isEmpty()) return;

        // Snapshot, don't drain: the ids are only removed once the
        // database confirms the save. A failed save stays queued and is
        // retried on the next tick instead of being silently lost.
        List<Long> ids = new ArrayList<>(unsavedTemplates);
        List<ItemTemplate> templates = new ArrayList<>();
        List<Long> savedIds = new ArrayList<>();
        for (Long id : ids) {
            ItemTemplate t = getItemTemplate(id);
            if (t != null) {
                templates.add(t);
                savedIds.add(id);
            }
        }
        if (!templates.isEmpty()) {
            plugin.getProtectDatabase().saveItems(templates, () -> {
                for (Long id : savedIds) unsavedTemplates.remove(id);
            });
        }
    }

    public long getItemReferenceCount() {
        return itemCache.size();
    }

}