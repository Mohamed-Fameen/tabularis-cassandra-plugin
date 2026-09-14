package dev.tabularis.plugin.cassandra.driver;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Values delivered by the host via the optional {@code initialize} RPC call
 * (see manifest {@code settings} array in .tabularium and
 * plugins/PLUGIN_GUIDE.md's "Plugin Settings" section). Immutable snapshot -
 * a fresh {@code initialize} call simply replaces it.
 */
public final class PluginSettings {

    public static final PluginSettings DEFAULTS = new PluginSettings(
            "datacenter1", "LOCAL_QUORUM", 10_000, false);

    private final String localDatacenter;
    private final String consistencyLevel;
    private final int requestTimeoutMs;
    private final boolean shardAwarePort;

    private PluginSettings(String localDatacenter, String consistencyLevel, int requestTimeoutMs, boolean shardAwarePort) {
        this.localDatacenter = localDatacenter;
        this.consistencyLevel = consistencyLevel;
        this.requestTimeoutMs = requestTimeoutMs;
        this.shardAwarePort = shardAwarePort;
    }

    public static PluginSettings fromJson(JsonNode settingsNode) {
        if (settingsNode == null || settingsNode.isNull() || settingsNode.isMissingNode()) {
            return DEFAULTS;
        }
        String dc = settingsNode.path("local_datacenter").asText(DEFAULTS.localDatacenter);
        String cl = settingsNode.path("consistency_level").asText(DEFAULTS.consistencyLevel);
        int timeout = settingsNode.path("request_timeout_ms").asInt(DEFAULTS.requestTimeoutMs);
        boolean shardAware = settingsNode.path("scylla_shard_aware").asBoolean(DEFAULTS.shardAwarePort);
        return new PluginSettings(dc, cl, timeout, shardAware);
    }

    public String localDatacenter() {
        return localDatacenter;
    }

    public String consistencyLevel() {
        return consistencyLevel;
    }

    public int requestTimeoutMs() {
        return requestTimeoutMs;
    }

    /**
     * When true, hints that the cluster is ScyllaDB and the driver should
     * prefer the shard-aware native port (19042/19142) if the caller connects
     * through one. Kept as a documented no-op flag for now — see README
     * "Cassandra vs ScyllaDB" — until shard-aware routing is added.
     */
    public boolean scyllaShardAware() {
        return shardAwarePort;
    }
}
