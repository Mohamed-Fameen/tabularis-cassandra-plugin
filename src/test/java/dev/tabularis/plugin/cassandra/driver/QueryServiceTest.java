package dev.tabularis.plugin.cassandra.driver;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.tabularis.plugin.cassandra.model.ConnectionParams;
import dev.tabularis.plugin.cassandra.rpc.RpcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates the {@link PagingCache} integration end-to-end: sequential
 * forward paging must reuse the token captured from the previous page
 * instead of replaying from page 1 - see PagingCache's class doc.
 */
@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private SessionRegistry sessions;
    @Mock
    private CqlSession session;

    private final ObjectMapper mapper = new ObjectMapper();
    private QueryService queryService;
    private ConnectionParams params;

    @BeforeEach
    void setUp() throws RpcException {
        queryService = new QueryService(sessions, mapper);
        params = new ConnectionParams("localhost", 9042, "user", "pw", "ks", false);
    }

    @Test
    void sequentialPagingReusesCachedStateInsteadOfReplaying() throws RpcException {
        when(sessions.getOrOpen(any())).thenReturn(session);
        ResultSet page1 = fakeResultSet(List.of("v1", "v2"), ByteBuffer.wrap(new byte[]{1, 2, 3}));
        ResultSet page2 = fakeResultSet(List.of("v3"), null);
        when(session.execute(any(SimpleStatement.class))).thenReturn(page1).thenReturn(page2);

        ObjectNode firstPage = queryService.executeQuery(params, "SELECT id FROM widgets", 1, 2);
        assertThat(toList(firstPage.get("columns"))).containsExactly("id");
        assertThat(firstPage.get("rows").size()).isEqualTo(2);
        assertThat(firstPage.get("total_count").asInt()).isEqualTo(-1); // more pages, unknown total

        ObjectNode secondPage = queryService.executeQuery(params, "SELECT id FROM widgets", 2, 2);
        assertThat(secondPage.get("rows").size()).isEqualTo(1);
        assertThat(secondPage.get("total_count").asInt()).isEqualTo(3); // last page reached: exact total

        // Exactly one execute() per page - a cache miss would have replayed
        // page 1 again before fetching page 2, costing a third call.
        verify(session, times(2)).execute(any(SimpleStatement.class));
    }

    @Test
    void blankQueryIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queryService.executeQuery(params, "  ", 1, 10))
                .isInstanceOf(RpcException.class);
    }

    private ResultSet fakeResultSet(List<String> values, ByteBuffer nextPagingState) {
        ColumnDefinition columnDefinition = mock(ColumnDefinition.class);
        when(columnDefinition.getName()).thenReturn(CqlIdentifier.fromCql("id"));

        ColumnDefinitions defs = mock(ColumnDefinitions.class);
        when(defs.size()).thenReturn(1);
        when(defs.get(0)).thenReturn(columnDefinition);

        List<Row> rows = values.stream().map(v -> {
            Row row = mock(Row.class);
            when(row.getObject(0)).thenReturn(v);
            return row;
        }).toList();

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getColumnDefinitions()).thenReturn(defs);
        when(resultSet.getAvailableWithoutFetching()).thenReturn(rows.size());
        when(resultSet.iterator()).thenAnswer(invocation -> rows.iterator());

        ExecutionInfo executionInfo = mock(ExecutionInfo.class);
        when(executionInfo.getPagingState()).thenReturn(nextPagingState);
        when(resultSet.getExecutionInfo()).thenReturn(executionInfo);

        return resultSet;
    }

    private List<String> toList(com.fasterxml.jackson.databind.JsonNode array) {
        List<String> values = new java.util.ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
