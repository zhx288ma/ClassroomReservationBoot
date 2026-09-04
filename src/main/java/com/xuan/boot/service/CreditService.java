package com.xuan.boot.service;

import com.xuan.boot.domain.CreditAccount;
import com.xuan.boot.domain.CreditRecord;

import java.util.List;

public interface CreditService {
    CreditAccount getOrCreate(Long userId);

    void assertCanReserve(Long userId);

    void rewardCheckin(Long userId, Long reservationId);

    void punishNoShow(Long userId, Long reservationId);

    List<CreditRecord> listRecords(Long userId, Integer limit);
}
