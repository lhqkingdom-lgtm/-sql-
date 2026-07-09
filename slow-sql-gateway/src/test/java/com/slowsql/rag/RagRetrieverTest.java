package com.slowsql.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagRetrieverTest {

    private RagDocumentRepository repository;
    private RagRetriever retriever;

    @BeforeEach
    void setUp() {
        repository = mock(RagDocumentRepository.class);
        retriever = new RagRetriever(repository);
    }

    // ===== 按表名匹配 =====

    @Test
    void retrieve_shouldMatchByTableName() {
        RagDocument doc = buildDoc("SELECT * 优化", "军规", "orders");
        when(repository.searchByTag("orders")).thenReturn(List.of(doc));

        String result = retriever.retrieve(List.of("orders"), "SELECT * FROM orders");

        assertTrue(result.contains("企业知识库匹配规则"));
        assertTrue(result.contains("军规"));
        assertTrue(result.contains("SELECT * 优化"));
    }

    // ===== 按 SQL 关键字匹配 =====

    @Test
    void retrieve_shouldMatchBySqlKeyword() {
        RagDocument doc = buildDoc("JOIN 优化", "军规", "JOIN");
        when(repository.searchByTag("orders")).thenReturn(List.of());
        when(repository.searchByTag("JOIN")).thenReturn(List.of(doc));

        String result = retriever.retrieve(List.of("orders"), "SELECT * FROM a JOIN b ON a.id=b.id");

        assertTrue(result.contains("JOIN 优化"));
    }

    @Test
    void retrieve_shouldMatchMultipleKeywords() {
        when(repository.searchByTag(anyString())).thenReturn(List.of());
        when(repository.searchByTag("JOIN")).thenReturn(List.of(buildDoc("JOIN优化", "军规", "join")));
        when(repository.searchByTag("ORDER BY")).thenReturn(List.of(buildDoc("排序优化", "业务规则", "sort")));

        String result = retriever.retrieve(List.of("t"), "SELECT * FROM t JOIN u ON t.id=u.id ORDER BY t.name");

        assertTrue(result.contains("JOIN优化"));
        assertTrue(result.contains("排序优化"));
    }

    // ===== 空结果 =====

    @Test
    void retrieve_shouldReturnEmptyWhenNoMatch() {
        when(repository.searchByTag(anyString())).thenReturn(List.of());

        String result = retriever.retrieve(List.of("t"), "SELECT 1");

        assertEquals("", result);
    }

    // ===== null tableNames =====

    @Test
    void retrieve_shouldHandleNullTableNames() {
        when(repository.searchByTag("JOIN")).thenReturn(List.of(buildDoc("JOIN", "军规", "join")));

        String result = retriever.retrieve(null, "SELECT * FROM a JOIN b");

        assertTrue(result.contains("JOIN"));
    }

    // ===== null sql =====

    @Test
    void retrieve_shouldHandleNullSql() {
        when(repository.searchByTag("orders")).thenReturn(List.of(buildDoc("表优化", "业务规则", "orders")));

        String result = retriever.retrieve(List.of("orders"), null);

        assertTrue(result.contains("表优化"));
    }

    // ===== 优先级排序：军规 > 事故复盘 > 业务规则 =====

    @Test
    void retrieve_shouldSortByCategoryPriority() {
        RagDocument biz = buildDoc("业务知识", "业务规则", "t");
        RagDocument incident = buildDoc("事故记录", "事故复盘", "t");
        RagDocument rule = buildDoc("军规知识", "军规", "t");

        when(repository.searchByTag("t")).thenReturn(List.of(biz, incident, rule));

        String result = retriever.retrieve(List.of("t"), "SELECT * FROM t");

        int rulePos = result.indexOf("军规知识");
        int incidentPos = result.indexOf("事故记录");
        int bizPos = result.indexOf("业务知识");

        assertTrue(rulePos < incidentPos, "军规应在事故复盘之前");
        assertTrue(incidentPos < bizPos, "事故复盘应在业务规则之前");
    }

    // ===== 去重——LinkedHashSet 由 Java 保证，不需要显式测试 =====

    private RagDocument buildDoc(String title, String category, String tag) {
        return buildDocFixedId((long) (title.hashCode() & 0x7FFFFFFF), title, category, tag);
    }

    private RagDocument buildDocFixedId(Long id, String title, String category, String tag) {
        RagDocument d = new RagDocument();
        d.setId(id);
        d.setTitle(title);
        d.setContent(title + "的详细内容...");
        d.setCategory(category);
        d.setTags(tag);
        d.setEnabled(true);
        return d;
    }
}
