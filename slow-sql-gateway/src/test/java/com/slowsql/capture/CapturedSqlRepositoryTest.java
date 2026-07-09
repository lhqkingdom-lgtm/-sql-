package com.slowsql.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapturedSqlRepositoryTest {

    private JdbcTemplate jdbc;
    private CapturedSqlRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new CapturedSqlRepository(jdbc);
    }

    // ===== upsert =====

    @Test
    void upsert_shouldExecuteInsert() {
        when(jdbc.update(anyString(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        CapturedSql c = buildCapturedSql();
        repository.upsert(c);

        verify(jdbc).update(contains("INSERT INTO captured_sql"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void upsert_shouldSurviveSqlException() {
        doThrow(new RuntimeException("DB down")).when(jdbc).update(anyString(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        CapturedSql c = buildCapturedSql();
        // 不应抛异常
        assertDoesNotThrow(() -> repository.upsert(c));
    }

    // ===== findAll =====

    @Test
    void findAll_shouldReturnList() {
        CapturedSql c = buildCapturedSql();
        c.setId(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(50))).thenReturn(List.of(c));

        List<CapturedSql> result = repository.findAll(50);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void findAll_shouldReturnEmptyOnError() {
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenThrow(new RuntimeException("DB down"));

        List<CapturedSql> result = repository.findAll(50);

        assertTrue(result.isEmpty());
    }

    // ===== findByInstance =====

    @Test
    void findByInstance_shouldFilterByInstance() {
        CapturedSql c = buildCapturedSql();
        c.setInstanceId("tc-dev-mysql");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("tc-dev-mysql"), eq(10)))
                .thenReturn(List.of(c));

        List<CapturedSql> result = repository.findByInstance("tc-dev-mysql", 10);

        assertEquals(1, result.size());
        assertEquals("tc-dev-mysql", result.get(0).getInstanceId());
    }

    @Test
    void findByInstance_shouldReturnEmptyOnError() {
        when(jdbc.query(anyString(), any(RowMapper.class), anyString(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));

        List<CapturedSql> result = repository.findByInstance("tc-dev-mysql", 10);

        assertTrue(result.isEmpty());
    }

    // ===== findByFingerprint =====

    @Test
    void findByFingerprint_shouldReturnMatch() {
        CapturedSql c = buildCapturedSql();
        c.setFingerprint("abc123");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("abc123"))).thenReturn(List.of(c));

        CapturedSql result = repository.findByFingerprint("abc123");

        assertNotNull(result);
        assertEquals("abc123", result.getFingerprint());
    }

    @Test
    void findByFingerprint_shouldReturnNullWhenNotFound() {
        when(jdbc.query(anyString(), any(RowMapper.class), eq("nonexistent"))).thenReturn(List.of());

        CapturedSql result = repository.findByFingerprint("nonexistent");

        assertNull(result);
    }

    @Test
    void findByFingerprint_shouldReturnNullOnError() {
        when(jdbc.query(anyString(), any(RowMapper.class), anyString()))
                .thenThrow(new RuntimeException("DB down"));

        assertNull(repository.findByFingerprint("abc123"));
    }

    // ===== deleteById =====

    @Test
    void deleteById_shouldDelete() {
        when(jdbc.update("DELETE FROM captured_sql WHERE id = ?", 1L)).thenReturn(1);

        int result = repository.deleteById(1L);

        assertEquals(1, result);
    }

    @Test
    void deleteById_shouldReturnZeroOnError() {
        when(jdbc.update(anyString(), anyLong())).thenThrow(new RuntimeException("DB down"));

        assertEquals(0, repository.deleteById(1L));
    }

    // ===== deleteAll =====

    @Test
    void deleteAll_shouldDeleteAll() {
        when(jdbc.update("DELETE FROM captured_sql")).thenReturn(100);

        assertEquals(100, repository.deleteAll());
    }

    // ===== countToday =====

    @Test
    void countToday_shouldReturnCount() {
        when(jdbc.queryForObject(contains("CURDATE()"), eq(Integer.class))).thenReturn(42);

        assertEquals(42, repository.countToday());
    }

    @Test
    void countToday_shouldReturnZeroOnNull() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);

        assertEquals(0, repository.countToday());
    }

    @Test
    void countToday_shouldReturnZeroOnError() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenThrow(new RuntimeException("DB down"));

        assertEquals(0, repository.countToday());
    }

    // ===== countTotal =====

    @Test
    void countTotal_shouldReturnCount() {
        when(jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql", Integer.class)).thenReturn(1024);

        assertEquals(1024, repository.countTotal());
    }

    // ===== countBySource =====

    @Test
    void countBySource_shouldReturnDistribution() {
        List<Map<String, Object>> rows = List.of(
                Map.of("source", "slow_log_table", "cnt", 100L),
                Map.of("source", "manual", "cnt", 5L));
        when(jdbc.queryForList(anyString())).thenReturn(rows);

        List<Map<String, Object>> result = repository.countBySource();

        assertEquals(2, result.size());
    }

    // ===== findTopFrequent =====

    @Test
    void findTopFrequent_shouldReturnTop() {
        CapturedSql c = buildCapturedSql();
        c.setOccurrenceCount(99);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(5))).thenReturn(List.of(c));

        List<CapturedSql> result = repository.findTopFrequent(5);

        assertEquals(1, result.size());
        assertEquals(99, result.get(0).getOccurrenceCount());
    }

    private CapturedSql buildCapturedSql() {
        CapturedSql c = new CapturedSql();
        c.setSqlText("SELECT * FROM orders");
        c.setDatabaseName("test_sql");
        c.setInstanceId("tc-dev-mysql");
        c.setProjectCode("tongcheng-club");
        c.setQueryTimeSec(3.5);
        c.setLockTimeSec(0.01);
        c.setRowsExamined(50000L);
        c.setRowsSent(10L);
        c.setFingerprint("abc123");
        c.setSource("slow_log_table");
        c.setSeverity("P2");
        return c;
    }
}
