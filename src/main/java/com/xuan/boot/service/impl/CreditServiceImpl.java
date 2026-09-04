package com.xuan.boot.service.impl;

import com.xuan.boot.domain.CreditAccount;
import com.xuan.boot.domain.CreditRecord;
import com.xuan.boot.mapper.CreditMapper;
import com.xuan.boot.service.CreditService;
import com.xuan.boot.service.DomainEventService;
import com.xuan.boot.service.IdGeneratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CreditServiceImpl implements CreditService {
    private final CreditMapper creditMapper;
    private final IdGeneratorService idGeneratorService;
    private final DomainEventService domainEventService;
    private final int minReserveScore;
    private final int checkinReward;
    private final int noShowPenalty;

    public CreditServiceImpl(CreditMapper creditMapper,
                             IdGeneratorService idGeneratorService,
                             DomainEventService domainEventService,
                             @Value("${reservation.credit.min-reserve-score:60}") int minReserveScore,
                             @Value("${reservation.credit.checkin-reward:1}") int checkinReward,
                             @Value("${reservation.credit.no-show-penalty:5}") int noShowPenalty) {
        this.creditMapper = creditMapper;
        this.idGeneratorService = idGeneratorService;
        this.domainEventService = domainEventService;
        this.minReserveScore = minReserveScore;
        this.checkinReward = checkinReward;
        this.noShowPenalty = noShowPenalty;
    }

    @Override
    public CreditAccount getOrCreate(Long userId) {
        creditMapper.insertAccountIfAbsent(userId);
        return creditMapper.findAccount(userId);
    }

    @Override
    public void assertCanReserve(Long userId) {
        CreditAccount account = getOrCreate(userId);
        if (account.getCreditScore() != null && account.getCreditScore() < minReserveScore) {
            throw new IllegalArgumentException("信用分低于 " + minReserveScore + "，暂时不能预约，请先恢复信用分");
        }
    }

    @Override
    @Transactional
    public void rewardCheckin(Long userId, Long reservationId) {
        changeScore(userId, reservationId, checkinReward, 0, "CHECKIN_SUCCESS", "按时签到，信用分奖励");
    }

    @Override
    @Transactional
    public void punishNoShow(Long userId, Long reservationId) {
        changeScore(userId, reservationId, -Math.abs(noShowPenalty), 1, "NO_SHOW", "超过签到窗口未签到，扣减信用分");
    }

    @Override
    public List<CreditRecord> listRecords(Long userId, Integer limit) {
        getOrCreate(userId);
        return creditMapper.listRecords(userId, limit == null ? 20 : limit);
    }

    private void changeScore(Long userId, Long reservationId, int changeScore, int violationDelta, String reason, String remark) {
        CreditAccount account = getOrCreate(userId);
        int before = account.getCreditScore() == null ? 100 : account.getCreditScore();
        int after = Math.max(0, Math.min(120, before + changeScore));
        creditMapper.updateAccount(userId, after, violationDelta);

        CreditRecord record = new CreditRecord();
        record.setId(idGeneratorService.nextId("credit"));
        record.setUserId(userId);
        record.setReservationId(reservationId);
        record.setChangeScore(after - before);
        record.setBeforeScore(before);
        record.setAfterScore(after);
        record.setReason(reason);
        record.setRemark(remark);
        creditMapper.insertRecord(record);
        recordCreditEvent(record);
    }

    private void recordCreditEvent(CreditRecord record) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("changeScore", record.getChangeScore());
        attributes.put("beforeScore", record.getBeforeScore());
        attributes.put("afterScore", record.getAfterScore());
        attributes.put("reason", record.getReason());
        domainEventService.recordEvent("CREDIT_CHANGED", "CREDIT_RECORD", record.getId(), record.getUserId(),
                null, null, record.getReservationId(), null, null, null, attributes);
    }
}
