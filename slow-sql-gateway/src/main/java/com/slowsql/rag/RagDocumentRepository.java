package com.slowsql.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 知识库文档持久化层。
 */
@Repository
public class RagDocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentRepository.class);

    private final JdbcTemplate jdbc;

    public RagDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<RagDocument> ROW_MAPPER = (rs, rowNum) -> {
        RagDocument doc = new RagDocument();
        doc.setId(rs.getLong("id"));
        doc.setTitle(rs.getString("title"));
        doc.setContent(rs.getString("content"));
        doc.setCategory(rs.getString("category"));
        doc.setTags(rs.getString("tags"));
        doc.setEnabled(rs.getBoolean("enabled"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) doc.setCreatedAt(ts.toLocalDateTime());
        return doc;
    };

    public List<RagDocument> findAllEnabled() {
        try {
            return jdbc.query(
                    "SELECT * FROM rag_document WHERE enabled = 1 ORDER BY category, id",
                    ROW_MAPPER);
        } catch (Exception e) {
            log.warn("查询知识库文档失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<RagDocument> findByCategory(String category) {
        try {
            return jdbc.query(
                    "SELECT * FROM rag_document WHERE category = ? AND enabled = 1 ORDER BY id",
                    ROW_MAPPER, category);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 按标签模糊搜索（用于 RAG 检索匹配）。
     */
    public List<RagDocument> searchByTag(String tag) {
        try {
            return jdbc.query(
                    "SELECT * FROM rag_document WHERE enabled = 1 AND tags LIKE ? ORDER BY category, id",
                    ROW_MAPPER, "%" + tag + "%");
        } catch (Exception e) {
            return List.of();
        }
    }

    public void save(RagDocument doc) {
        try {
            jdbc.update("""
                    INSERT INTO rag_document (title, content, category, tags, enabled, created_at)
                    VALUES (?, ?, ?, ?, ?, NOW())
                    """,
                    doc.getTitle(), doc.getContent(), doc.getCategory(),
                    doc.getTags(), doc.isEnabled() ? 1 : 0);
        } catch (Exception e) {
            log.warn("保存知识库文档失败: {}", e.getMessage());
        }
    }

    public int update(RagDocument doc) {
        try {
            return jdbc.update("""
                    UPDATE rag_document SET title=?, content=?, category=?, tags=?, enabled=?
                    WHERE id=?
                    """,
                    doc.getTitle(), doc.getContent(), doc.getCategory(),
                    doc.getTags(), doc.isEnabled() ? 1 : 0, doc.getId());
        } catch (Exception e) {
            return 0;
        }
    }

    public int deleteById(Long id) {
        try {
            return jdbc.update("DELETE FROM rag_document WHERE id = ?", id);
        } catch (Exception e) {
            return 0;
        }
    }
}
