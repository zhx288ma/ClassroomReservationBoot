package com.xuan.boot.service;

import com.xuan.boot.domain.ReservationOrder;
import com.xuan.boot.domain.WaitlistOrder;
import com.xuan.boot.dto.ReserveRequest;
import com.xuan.boot.dto.ReserveResponse;
import com.xuan.boot.dto.SignRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ReservationService {
    String createSubmitToken();

    ReserveResponse reserve(ReserveRequest request, String submitToken);

    void cancel(Long orderId);

    void cancelWaitlist(Long waitlistId);

    void sign(SignRequest request);

    List<ReservationOrder> list(Long roomId, LocalDate reserveDate, Integer status, Integer limit);

    List<WaitlistOrder> listWaitlist(Long roomId, LocalDate reserveDate, Integer status, Integer limit);

    Map<String, Object> dashboard();

    int expireExpiredWaitlists();

    int markNoShowReservations();
}
