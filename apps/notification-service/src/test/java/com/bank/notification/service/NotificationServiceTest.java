package com.bank.notification.service;

import com.bank.notification.dto.NotificationRequest;
import com.bank.notification.dto.NotificationResponse;
import com.bank.notification.entity.Notification;
import com.bank.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository repo;
    @InjectMocks NotificationService service;

    @Test
    void send_success() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationRequest req = new NotificationRequest();
        req.setAccountNo("A1001");
        req.setChannel("SMS");
        req.setTemplate("PAYMENT_SUCCESS");
        req.setContent("Payment of 299 CNY processed.");

        NotificationResponse resp = service.send(req);

        assertEquals("SENT", resp.getStatus());
        assertEquals("A1001", resp.getAccountNo());
        assertNotNull(resp.getNotificationNo());
    }

    @Test
    void send_defaultsApplied() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationRequest req = new NotificationRequest();
        req.setAccountNo("A1001");

        NotificationResponse resp = service.send(req);
        assertEquals("SENT", resp.getStatus());
    }

    @Test
    void getByAccount() {
        Notification n = new Notification();
        n.setAccountNo("A1001");
        n.setType("SMS");
        n.setTitle("PAYMENT_SUCCESS");
        n.setContent("Paid");
        n.setStatus("SENT");
        when(repo.findByAccountNoOrderByCreatedAtDesc("A1001")).thenReturn(List.of(n));

        List<NotificationResponse> list = service.getByAccount("A1001");
        assertEquals(1, list.size());
        assertEquals("A1001", list.get(0).getAccountNo());
    }
}
