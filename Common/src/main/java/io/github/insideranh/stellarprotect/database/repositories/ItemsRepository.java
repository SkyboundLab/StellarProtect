package io.github.insideranh.stellarprotect.database.repositories;

import io.github.insideranh.stellarprotect.items.ItemTemplate;

import java.util.List;

public interface ItemsRepository {

    // onSaved runs only after the batch committed: the caller must not
    // drop the templates from its unsaved set before then, or a failed
    // save would be silently lost instead of retried.
    void saveItems(List<ItemTemplate> itemTemplates, Runnable onSaved);

    void loadMostUsedItems();

}