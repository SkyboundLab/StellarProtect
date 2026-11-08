package io.github.insideranh.stellarprotect.database.repositories;

import io.github.insideranh.stellarprotect.blocks.BlockTemplate;

import java.util.List;

public interface BlocksRepository {

    // onSaved runs only after the batch committed: the caller must not
    // drop the templates from its unsaved set before then, or a failed
    // save would be silently lost instead of retried.
    void saveBlocks(List<BlockTemplate> blockTemplates, Runnable onSaved);

    void loadBlockDatas();

}