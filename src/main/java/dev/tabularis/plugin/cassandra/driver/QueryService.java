package dev.tabularis.plugin.cassandra.driver;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tabularis.plugin.cassandra.model.ConnectionParams;
import dev.tabularis.plugin.cassandra.rpc.RpcException;
import dev.tabularis.plugin.cassandra.util.JsonConverter;
import dev.tabularis.plugin.cassandra.util.PagingCache;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Implements {@code execute_query}. See {@link PagingCache}'s class doc for
 * why CQL paging needs a forward-state cache rather than plain
 * page-number/offset math, and README "Query paging &amp; total_count" for
 * the {@code total_count} semantics this returns.
 */
public class QueryService {

    private final SessionRegistry sessions;
    private final ObjectMapper mapper;
    private final PagingCache pagingCache = new PagingCache();

    public QueryService(SessionRegistry sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public ObjectNode executeQuery(ConnectionParams params, String query, int page, int pageSize) throws RpcException {
        if (query == null || query.isBlank()) {
            throw RpcException.invalidParams("\"query\" is required");
        }
        int safePage = Math.max(page, 1);
        int safePageSize = pageSize > 0 ? pageSize : 100;

        CqlSession session = sessions.getOrOpen(params);
        String fingerprint = pagingCache.fingerprint(params.signature(), query, safePageSize);

        long start = System.nanoTime();
        try {
            ByteBuffer startState = resolveStartState(session, query, safePage, safePageSize, fingerprint);
            if (startState == NO_MORE_DATA) {
                return emptyResult(0, System.nanoTime() - start);
            }

            SimpleStatement statement = SimpleStatement.newInstance(query).setPageSize(safePageSize);
            if (startState != null) {
                statement = statement.setPagingState(startState);
            }
            ResultSet rs = session.execute(statement);

            List<Row> rows = takeCurrentPage(rs, safePageSize);
            ByteBuffer nextState = rs.getExecutionInfo().getPagingState();
            pagingCache.rememberNextPageState(fingerprint, safePage, nextState);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return toResultNode(rs.getColumnDefinitions(), rows, safePage, safePageSize, nextState == null, elapsedMs);
        } catch (RpcException e) {
            throw e;
        } catch (Exception e) {
            throw RpcException.internal("CQL query failed: " + rootMessage(e), e);
        }
    }

    /** Sentinel distinguishing "no paging state needed (page 1)" from "ran out of pages before reaching the target". */
    private static final ByteBuffer NO_MORE_DATA = ByteBuffer.allocate(0);

    private ByteBuffer resolveStartState(CqlSession session, String query, int page, int pageSize, String fingerprint) {
        if (page <= 1) {
            return null;
        }
        ByteBuffer cached = pagingCache.stateForPage(fingerprint, page);
        if (cached != null) {
            return cached;
        }
        // Cache miss for a page beyond the first: replay forward from page 1
        // to obtain the token. Correct but O(page) - see PagingCache javadoc.
        ByteBuffer state = null;
        for (int current = 1; current < page; current++) {
            SimpleStatement statement = SimpleStatement.newInstance(query).setPageSize(pageSize);
            if (state != null) {
                statement = statement.setPagingState(state);
            }
            ResultSet rs = session.execute(statement);
            takeCurrentPage(rs, pageSize); // drain without triggering an extra fetch
            ByteBuffer next = rs.getExecutionInfo().getPagingState();
            pagingCache.rememberNextPageState(fingerprint, current, next);
            if (next == null) {
                return NO_MORE_DATA;
            }
            state = next;
        }
        return state;
    }

    private static List<Row> takeCurrentPage(ResultSet rs, int pageSize) {
        int available = Math.min(rs.getAvailableWithoutFetching(), pageSize);
        List<Row> rows = new ArrayList<>(available);
        Iterator<Row> it = rs.iterator();
        for (int i = 0; i < available && it.hasNext(); i++) {
            rows.add(it.next());
        }
        return rows;
    }

    private ObjectNode toResultNode(ColumnDefinitions defs, List<Row> rows, int page, int pageSize, boolean isLastPage, long elapsedMs) {
        ArrayNode columns = mapper.createArrayNode();
        for (int i = 0; i < defs.size(); i++) {
            columns.add(defs.get(i).getName().asInternal());
        }

        ArrayNode resultRows = mapper.createArrayNode();
        for (Row row : rows) {
            ArrayNode rowArray = mapper.createArrayNode();
            for (int i = 0; i < defs.size(); i++) {
                rowArray.add(JsonConverter.toJson(mapper, row.getObject(i)));
            }
            resultRows.add(rowArray);
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("columns", columns);
        result.set("rows", resultRows);
        // total_count is exact once the last page is reached; otherwise -1
        // signals "unknown / more rows exist" - CQL has no cheap COUNT(*)
        // for an arbitrary statement (it's a full coordinator-side scan), so
        // we deliberately never issue one. See README.
        result.put("total_count", isLastPage ? (page - 1) * pageSize + rows.size() : -1);
        result.put("execution_time_ms", elapsedMs);
        return result;
    }

    private ObjectNode emptyResult(int totalCount, long elapsedNanos) {
        ObjectNode result = mapper.createObjectNode();
        result.set("columns", mapper.createArrayNode());
        result.set("rows", mapper.createArrayNode());
        result.put("total_count", totalCount);
        result.put("execution_time_ms", elapsedNanos / 1_000_000);
        return result;
    }

    private static String rootMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
