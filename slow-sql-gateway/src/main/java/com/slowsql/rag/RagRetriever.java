package com.slowsql.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    private static final Map<String, Integer> CATEGORY_PRIORITY = Map.of(
            "军规", 1, "事故复盘", 2, "业务规则", 3);

    private static final List<String> SQL_KEYWORDS = List.of(
            "JOIN", "LEFT JOIN", "ORDER BY", "GROUP BY", "LIMIT", "DISTINCT", "UNION", "HAVING");

    private final RagDocumentRepository repository;

    public RagRetriever(RagDocumentRepository repository) {
        this.repository = repository;
    }

    public String retrieve(List<String> tableNames, String sql) {
        Set<RagDocument> matched = new LinkedHashSet<>();

        if (tableNames != null) {
            for (String name : tableNames) {
                matched.addAll(repository.searchByTag(name));
            }
        }
        if (sql != null) {
            String upper = sql.toUpperCase();
            for (String kw : SQL_KEYWORDS) {
                if (upper.contains(kw)) matched.addAll(repository.searchByTag(kw));
            }
        }

        if (matched.isEmpty()) return "";

        List<RagDocument> sorted = matched.stream()
                .sorted(Comparator.comparingInt(d -> CATEGORY_PRIORITY.getOrDefault(d.getCategory(), 99)))
                .collect(Collectors.toList());

        log.info("RAG命中{}条", sorted.size());
        StringBuilder sb = new StringBuilder("【企业知识库匹配规则】\n");
        for (int i = 0; i < sorted.size(); i++) {
            RagDocument d = sorted.get(i);
            sb.append("**规则").append(i + 1).append("** [").append(d.getCategory())
              .append("]: ").append(d.getTitle()).append("\n> ").append(d.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
