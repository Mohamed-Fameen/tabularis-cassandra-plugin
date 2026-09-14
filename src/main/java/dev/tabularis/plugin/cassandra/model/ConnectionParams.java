package dev.tabularis.plugin.cassandra.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * The connection form Tabularis collects for a "network" style driver
 * (host/port/user/password/database) plus the optional SSL flag. Mirrors the
 * {@code ConnectionParams} shape referenced throughout plugins/PLUGIN_GUIDE.md
 * ({@code request.params.params}).
 *
 * <p>For this driver, {@code database} holds the CQL keyspace to use as the
 * session's default (Cassandra has no separate "schema" level - a keyspace
 * *is* the database, see manifest {@code capabilities.schemas: false}).
 */
public final class ConnectionParams {

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String keyspace;
    private final boolean ssl;

    public ConnectionParams(String host, int port, String user, String password, String keyspace, boolean ssl) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.keyspace = keyspace;
        this.ssl = ssl;
    }

    public static ConnectionParams fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new IllegalArgumentException("Missing connection \"params\"");
        }
        String host = textOrNull(node, "host");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Connection \"host\" is required");
        }
        int port = node.hasNonNull("port") ? node.get("port").asInt(9042) : 9042;
        String user = textOrNull(node, "user");
        if (user == null) {
            user = textOrNull(node, "username");
        }
        String password = textOrNull(node, "password");
        String keyspace = textOrNull(node, "database");
        boolean ssl = node.path("ssl").asBoolean(false);
        return new ConnectionParams(host, port, user, password, keyspace, ssl);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText(null);
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    /** The CQL keyspace this connection targets, if one was selected. */
    public String keyspace() {
        return keyspace;
    }

    public boolean ssl() {
        return ssl;
    }

    /** Identifies a distinct logical connection so sessions can be cached/reused. */
    public String signature() {
        return host + ':' + port + '/' + Objects.toString(keyspace, "") + '/' + Objects.toString(user, "") + '/' + ssl;
    }
}
