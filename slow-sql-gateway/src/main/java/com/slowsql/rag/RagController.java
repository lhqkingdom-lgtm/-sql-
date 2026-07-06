package com.slowsql.rag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagDocumentRepository repository;

    public RagController(RagDocumentRepository repository) { this.repository = repository; }

    @GetMapping("/documents")
    public ResponseEntity<?> list(@RequestParam(required = false) String category,
                                   @RequestParam(required = false) String keyword) {
        List<RagDocument> docs;
        if (category != null) docs = repository.findByCategory(category);
        else if (keyword != null) docs = repository.searchByTag(keyword);
        else docs = repository.findAllEnabled();
        return ResponseEntity.ok(docs);
    }

    @PostMapping("/documents")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        RagDocument doc = new RagDocument();
        doc.setTitle((String) body.get("title"));
        doc.setContent((String) body.get("content"));
        doc.setCategory((String) body.getOrDefault("category", "业务规则"));
        doc.setTags((String) body.getOrDefault("tags", ""));
        doc.setEnabled(true);
        repository.save(doc);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/documents/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setTitle((String) body.get("title"));
        doc.setContent((String) body.get("content"));
        doc.setCategory((String) body.getOrDefault("category", "业务规则"));
        doc.setTags((String) body.getOrDefault("tags", ""));
        doc.setEnabled((Boolean) body.getOrDefault("enabled", true));
        int updated = repository.update(doc);
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
