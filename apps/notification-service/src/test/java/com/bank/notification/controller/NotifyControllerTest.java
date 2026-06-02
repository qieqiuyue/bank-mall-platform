package com.bank.notification.controller;

import com.bank.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotifyControllerTest {

    private MockMvc mvc;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NotifyController(service)).build();
    }

    @Test
    void send_success() throws Exception {
        when(service.send(any())).thenReturn(null);
        mvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNo\":\"A1001\",\"channel\":\"SMS\",\"template\":\"PAYMENT_SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void templates() throws Exception {
        mvc.perform(get("/api/notifications/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/notifications/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }
}
