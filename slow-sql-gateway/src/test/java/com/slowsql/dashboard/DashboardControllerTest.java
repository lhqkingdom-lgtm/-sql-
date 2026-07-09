package com.slowsql.dashboard;

import com.slowsql.capture.CapturedSql;
import com.slowsql.capture.CapturedSqlRepository;
import com.slowsql.persistence.DiagnosisRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DashboardControllerTest {

    private MockMvc mvc;
    private CapturedSqlRepository repository;
    private DiagnosisRecordRepository diagnosisRepo;

    @BeforeEach
    void setUp() {
        repository = mock(CapturedSqlRepository.class);
        diagnosisRepo = mock(DiagnosisRecordRepository.class);
        DashboardController controller = new DashboardController(repository, diagnosisRepo);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void stats_shouldReturnDashboardData() throws Exception {
        when(repository.countToday(any())).thenReturn(42);
        when(repository.countTotal(any())).thenReturn(1024);
        when(repository.countBySource(any())).thenReturn(List.of(
                Map.of("source", "slow_log_table", "cnt", 1000L),
                Map.of("source", "manual", "cnt", 24L)));
        CapturedSql top = new CapturedSql();
        top.setSqlText("SELECT * FROM big_table");
        top.setOccurrenceCount(99);
        when(repository.findTopFrequent(any(), eq(5))).thenReturn(List.of(top));

        mvc.perform(get("/api/dashboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayCount").value(42))
                .andExpect(jsonPath("$.totalCount").value(1024))
                .andExpect(jsonPath("$.sourceDistribution[0].source").value("slow_log_table"))
                .andExpect(jsonPath("$.topFrequent[0].occurrenceCount").value(99));
    }

    @Test
    void stats_shouldHandleEmptyData() throws Exception {
        when(repository.countToday(any())).thenReturn(0);
        when(repository.countTotal(any())).thenReturn(0);
        when(repository.countBySource(any())).thenReturn(List.of());
        when(repository.findTopFrequent(any(), eq(5))).thenReturn(List.of());

        mvc.perform(get("/api/dashboard/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayCount").value(0));
    }

    // 注：DashboardController 无全局异常处理器，Repository 异常会传播为 500
}
