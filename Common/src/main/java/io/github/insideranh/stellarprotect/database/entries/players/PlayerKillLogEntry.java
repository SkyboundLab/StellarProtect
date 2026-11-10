package io.github.insideranh.stellarprotect.database.entries.players;

import com.google.gson.JsonObject;
import io.github.insideranh.stellarprotect.StellarProtect;
import io.github.insideranh.stellarprotect.database.entries.LogEntry;
import io.github.insideranh.stellarprotect.enums.ActionType;
import io.github.insideranh.stellarprotect.utils.SerializerUtils;
import lombok.Getter;
import org.bukkit.entity.Entity;

import java.sql.ResultSet;
import java.util.HashMap;

@Getter
public class PlayerKillLogEntry extends LogEntry {

    private final String entityType;
    private final EntityData entityData;

    public PlayerKillLogEntry(ResultSet resultSet, JsonObject jsonObject) {
        super(resultSet);
        this.entityType = jsonObject.get("et").getAsString();
        if (jsonObject.has("ed")) {
            this.entityData = SerializerUtils.getGson().fromJson(jsonObject.get("ed").getAsString(), EntityData.class);
        } else {
            this.entityData = new EntityData();
        }
    }

    public PlayerKillLogEntry(long playerId, Entity killed, ActionType actionType) {
        super(playerId, actionType.getId(), killed.getLocation(), System.currentTimeMillis());
        this.entityType = killed.getType().name();
        setEntityType(this.entityType);

        this.entityData = new EntityData(StellarProtect.getInstance().getDataEntity(killed).getData());
    }

    @Override
    public String getDataString() {
        return entityType;
    }

    @Override
    public String toSaveJson() {
        JsonObject obj = new JsonObject();

        PlayerKillLogEntry entry = this;
        obj.addProperty("et", entry.getEntityType());
        if (!this.entityData.getEntityData().isEmpty()) {
            try {
                obj.addProperty("ed", SerializerUtils.getGson().toJson(this.entityData, EntityData.class));
            } catch (RuntimeException e) {
                // One unserialisable entity must never fail the whole log
                // batch: save the kill without its entity data instead.
                StellarProtect.getInstance().getLogger().warning("Failed to serialise killed " + entityType
                        + " entity data, saving kill log without it: " + e);
            }
        }

        return obj.toString();
    }

    @Getter
    public static class EntityData {

        private final HashMap<String, Object> entityData;

        public EntityData() {
            this.entityData = new HashMap<>();
        }

        public EntityData(HashMap<String, Object> entityData) {
            this.entityData = entityData;
        }

    }

}