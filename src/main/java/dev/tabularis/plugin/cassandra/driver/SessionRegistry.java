package dev.tabularis.plugin.cassandra.driver;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import dev.tabularis.plugin.cassandra.model.ConnectionParams;
import dev.tabularis.plugin.cassandra.rpc.RpcException;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the {@link CqlSession} lifecycle for this plugin process.
 *
 * <p>Per plugins/PLUGIN_GUIDE.md, "the host reuses the same process instance
 * throughout the entire session" for one connection, so in the overwhelmingly
 * common case there is exactly one active session. We still key by
 * {@link ConnectionParams#signature()} rather than assuming a singleton: it
 * costs nothing, protects against the host ever reusing a process across
 * connections, and makes the class trivially testable.
 */
public class SessionRegistry implements AutoCloseable {

    private final Map<String, CqlSession> sessions = new ConcurrentHashMap<>();
    private volatile PluginSettings settings = PluginSettings.DEFAULTS;

    public void applySettings(PluginSettings settings) {
        this.settings = settings;
    }

    public PluginSettings settings() {
        return settings;
    }

    /** Returns the cached session for these params, opening a new one if needed. */
    public CqlSession getOrOpen(ConnectionParams params) throws RpcException {
        try {
            return sessions.computeIfAbsent(params.signature(), key -> openSession(params));
        } catch (SessionOpenException e) {
            throw RpcException.internal("Could not open Cassandra/Scylla session: " + describe(e), e.getCause());
        }
    }

    /** Opens a throwaway session, runs it, and closes it - used by test_connection. */
    public boolean probe(ConnectionParams params) throws RpcException {
        CqlSession session = getOrOpen(params);
        try {
            session.execute("SELECT release_version FROM system.local");
            return true;
        } catch (Exception e) {
            // A failed probe invalidates the cached session so the next
            // attempt (or the next real query) doesn't reuse a broken one.
            invalidate(params);
            throw RpcException.internal("Connection test failed: " + describe(e), e);
        }
    }

    public void invalidate(ConnectionParams params) {
        CqlSession removed = sessions.remove(params.signature());
        if (removed != null) {
            removed.closeAsync();
        }
    }

    private CqlSession openSession(ConnectionParams params) {
        try {
            PluginSettings s = this.settings;
            CqlSessionBuilder builder = CqlSession.builder()
                    .addContactPoint(new InetSocketAddress(params.host(), params.port()))
                    .withLocalDatacenter(s.localDatacenter())
                    .withConfigLoader(DriverConfigLoader.programmaticBuilder()
                            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofMillis(s.requestTimeoutMs()))
                            .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofMillis(s.requestTimeoutMs()))
                            .withString(DefaultDriverOption.REQUEST_CONSISTENCY, resolveConsistency(s.consistencyLevel()).name())
                            .build());

            if (params.user() != null && !params.user().isBlank()) {
                builder = builder.withAuthCredentials(params.user(), params.password() == null ? "" : params.password());
            }
            if (params.keyspace() != null && !params.keyspace().isBlank()) {
                builder = builder.withKeyspace(params.keyspace());
            }
            if (params.ssl()) {
                builder = builder.withSslContext(javax.net.ssl.SSLContext.getDefault());
            }
            return builder.build();
        } catch (Exception e) {
            throw new SessionOpenException(e);
        }
    }

    private static ConsistencyLevel resolveConsistency(String name) {
        try {
            return DefaultConsistencyLevel.valueOf(name.trim().toUpperCase());
        } catch (Exception e) {
            return DefaultConsistencyLevel.LOCAL_QUORUM;
        }
    }

    private static String describe(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @Override
    public void close() {
        sessions.values().forEach(CqlSession::closeAsync);
        sessions.clear();
    }

    /** Unchecked wrapper so {@link #openSession} can be used inside computeIfAbsent. */
    static final class SessionOpenException extends RuntimeException {
        SessionOpenException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }
}
