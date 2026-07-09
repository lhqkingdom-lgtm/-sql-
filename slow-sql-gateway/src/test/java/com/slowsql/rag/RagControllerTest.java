package com.slowsql.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RagControllerTest {

    private MockMvc mvc;
    private RagDocumentRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(RagDocumentRepository.class);
        RagController controller = new RagController(repository);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ===== 列表 =====

    @Test
    void list_shouldReturnAllDocuments() throws Exception {
        RagDocument doc = new RagDocument();
        doc.setId(1L);
        doc.setTitle("SELECT * 优化");
        doc.setCategory("军规");
        when(repository.findAllEnabled()).thenReturn(List.of(doc));

        mvc.perform(get("/api/rag/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("SELECT * 优化"));
    }

    @Test
    void list_shouldFilterByCategory() throws Exception {
        RagDocument doc = new RagDocument();
        doc.setId(2L);
        doc.setCategory("军规");
        when(repository.findByCategory("军规")).thenReturn(List.of(doc));

        mvc.perform(get("/api/rag/documents?category=军规"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("军规"));
    }

    @Test
    void list_shouldSearchByKeyword() throws Exception {
        when(repository.searchByTag("JOIN")).thenReturn(List.of());

        mvc.perform(get("/api/rag/documents?keyword=JOIN"))
                .andExpect(status().isOk());
    }

    // ===== 创建 =====

    @Test
    void create_shouldSaveDocument() throws Exception {
        doNothing().when(repository).save(any(RagDocument.class));

        mvc.perform(post("/api/rag/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新规则\",\"content\":\"避免使用SELECT *\",\"category\":\"军规\",\"tags\":\"SELECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void create_shouldUseDefaultCategory() throws Exception {
        doNothing().when(repository).save(any(RagDocument.class));

        mvc.perform(post("/api/rag/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"规则\",\"content\":\"内容\"}"))
                .andExpect(status().isOk());
    }

    // ===== 更新 =====

    @Test
    void update_shouldUpdateDocument() throws Exception {
        when(repository.update(any(RagDocument.class))).thenReturn(1);

        mvc.perform(put("/api/rag/documents/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"更新标题\",\"content\":\"更新内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));
    }

    // ===== 删除 =====

    @Test
    void delete_shouldDeleteDocument() throws Exception {
        when(repository.deleteById(1L)).thenReturn(1);

        mvc.perform(delete("/api/rag/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
