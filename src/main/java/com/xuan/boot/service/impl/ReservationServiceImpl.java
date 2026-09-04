package com.xuan.boot.service.impl;

import com.xuan.boot.domain.ReservationOrder;
import com.xuan.boot.domain.ReservationStatus;
import com.xuan.boot.domain.RoomSlot;
import com.xuan.boot.domain.User;
import com.xuan.boot.domain.WaitlistOrder;
import com.xuan.boot.domain.WaitlistStatus;
import com.xuan.boot.dto.ReserveRequest;
import com.xuan.boot.dto.ReserveResponse;
import com.xuan.boot.dto.SignRequest;
import com.xuan.boot.mapper.ReservationOrderMapper;
import com.xuan.boot.mapper.RoomSlotMapper;
import com.xuan.boot.mapper.WaitlistMapper;
import com.xuan.boot.service.DomainEventService;
import com.xuan.boot.service.CreditService;
import com.xuan.boot.service.IdGeneratorService;
import com.xuan.boot.service.NotificationOutboxService;
import com.xuan.boot.service.ReservationService;
import com.xuan.boot.support.RedisKeys;
import com.xuan.boot.support.ReservationTimePolicy;
import com.xuan.boot.support.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ReservationServiceImpl implements ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationServiceImpl.class);
    private static final DateTimeFormatter WAITLIST_EXPIRE_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>(
            "local stock = tonumber(redis.call('get', KEYS[1]));" +
                    "if stock == nil then return 3 end;" +
                    "if stock <= 0 then return 1 end;" +
                    "if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then return 2 end;" +
                    "if redis.call('exists', KEYS[3]) == 1 then return 4 end;" +
                    "redis.call('decr', KEYS[1]);" +
                    "redis.call('sadd', KEYS[2], ARGV[1]);" +
                    "redis.call('set', KEYS[3], ARGV[3], 'EX', ARGV[2]);" +
                    "redis.call('expire', KEYS[2], ARGV[2]);" +
                    "return 0;",
            Long.class);

    private final RoomSlotMapper roomSlotMapper;
    private final ReservationOrderMapper reservationOrderMapper;
    private final WaitlistMapper waitlistMapper;
    private final IdGeneratorService idGeneratorService;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final NotificationOutboxService notificationOutboxService;
    private final CreditService creditService;
    private final DomainEventService domainEventService;
    public ReservationServiceImpl(RoomSlotMapper roomSlotMapper,
                                  ReservationOrderMapper reservationOrderMapper,
                                  WaitlistMapper waitlistMapper,
                                  IdGeneratorService idGeneratorService,
                                  StringRedisTemplate stringRedisTemplate,
                                  RedissonClient redissonClient,
                                  NotificationOutboxService notificationOutboxService,
                                  CreditService creditService,
                                  DomainEventService domainEventService) {
        this.roomSlotMapper = roomSlotMapper;
        this.reservationOrderMapper = reservationOrderMapper;
        this.waitlistMapper = waitlistMapper;
        this.idGeneratorService = idGeneratorService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.notificationOutboxService = notificationOutboxService;
        this.creditService = creditService;
        this.domainEventService = domainEventService;
    }

    @Override
    public String createSubmitToken() {
        User user = UserContext.getRequired();
        String token = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(submitTokenKey(user.getId(), token), "1", Duration.ofMinutes(5));
        return token;
    }

    @Override
    @Transactional
    public ReserveResponse reserve(ReserveRequest request, String submitToken) {
        User user = UserContext.getRequired();
        ReservationTimePolicy.validateReservable(request.getReserveDate(), request.getTimeSlot());
        RLock lock = redissonClient.getLock("lock:reserve:user:" + user.getId());
        AtomicBoolean redisReservationActive = new AtomicBoolean(false);
        try {
            if (!lock.tryLock(2, 8, TimeUnit.SECONDS)) {
                throw new IllegalArgumentException("请求处理中，请勿重复提交");
            }
            consumeSubmitToken(user.getId(), submitToken);
            creditService.assertCanReserve(user.getId());
            RoomSlot slot = loadOpenSlotAndInitRedisStock(request);
            Long redisResult = executeReserveScript(request, user.getId());
            if (redisResult != null && redisResult == 1) {
                if (request.isJoinWaitlist()) {
                    return joinWaitlist(request, user, slot);
                }
                throw new IllegalArgumentException("该教室时间段库存不足");
            }
            if (redisResult != null && redisResult == 2) {
                throw new IllegalArgumentException("该时间段已有预约，请勿重复提交");
            }
            if (redisResult != null && redisResult == 4) {
                throw new IllegalArgumentException("同一用户同一时间段只能预约一个教室");
            }
            if (redisResult == null || redisResult != 0) {
                throw new IllegalArgumentException("预约库存尚未初始化，请稍后重试");
            }

            redisReservationActive.set(true);
            registerRedisRollbackOnTransactionRollback(request, user.getId(), redisReservationActive);

            int dbStockUpdated = roomSlotMapper.decreaseStockById(slot.getId());
            if (dbStockUpdated == 0) {
                syncRedisReservationFromDb(request, user.getId());
                redisReservationActive.set(false);
                if (request.isJoinWaitlist()) {
                    return joinWaitlist(request, user, slot);
                }
                throw new IllegalArgumentException("库存扣减失败，请稍后重试");
            }
            ReservationOrder order = buildOrder(request, user, "抢约成功");
            order.setRoomSlotId(slot.getId());
            try {
                reservationOrderMapper.insert(order);
            } catch (DuplicateKeyException exception) {
                rollbackRedisReservation(request, user.getId());
                redisReservationActive.set(false);
                throw new IllegalArgumentException("同一用户同一时间段只能预约一个教室");
            }
            stringRedisTemplate.opsForZSet().incrementScore(RedisKeys.HOT_ROOM_RANK, String.valueOf(order.getRoomId()), 5);
            publishNotify(user.getId(), "预约成功", "预约单 " + order.getId() + " 已创建，签到码：" + order.getSignCode());
            recordReservationEvent("RESERVATION_SUCCESS", order);
            return reserveSuccess(order);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("预约请求被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public void cancel(Long orderId) {
        User user = UserContext.getRequired();
        ReservationOrder order = reservationOrderMapper.findById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("预约单不存在");
        }
        RLock lock = redissonClient.getLock("lock:reserve:order:" + orderId);
        lock.lock(8, TimeUnit.SECONDS);
        try {
            int updated = "ADMIN".equals(user.getRole())
                    ? reservationOrderMapper.cancelByAdmin(orderId)
                    : reservationOrderMapper.cancelByUser(orderId, user.getId());
            if (updated == 0) {
                throw new IllegalArgumentException("预约单状态不允许取消");
            }
            roomSlotMapper.increaseStock(order.getRoomId(), order.getReserveDate(), order.getTimeSlot());
            rollbackRedisReservationForCancel(order);
            publishNotify(order.getUserId(), "预约取消", "预约单 " + order.getId() + " 已取消");
            recordReservationEvent("RESERVATION_CANCELLED", order);
            tryPromoteWaiter(order.getRoomId(), order.getReserveDate(), order.getTimeSlot());
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public void cancelWaitlist(Long waitlistId) {
        expireExpiredWaitlists();
        User user = UserContext.getRequired();
        WaitlistOrder waitlist = waitlistMapper.findById(waitlistId);
        if (waitlist == null) {
            throw new IllegalArgumentException("\u5019\u8865\u5355\u4e0d\u5b58\u5728");
        }
        RLock lock = redissonClient.getLock("lock:waitlist:" + waitlistId);
        lock.lock(8, TimeUnit.SECONDS);
        try {
            int updated = "ADMIN".equals(user.getRole())
                    ? waitlistMapper.cancelByAdmin(waitlistId)
                    : waitlistMapper.cancelByUser(waitlistId, user.getId());
            if (updated == 0) {
                throw new IllegalArgumentException("\u5019\u8865\u5355\u72b6\u6001\u4e0d\u5141\u8bb8\u53d6\u6d88");
            }
            publishNotify(waitlist.getUserId(), "\u5019\u8865\u53d6\u6d88", "\u5019\u8865\u5355 " + waitlist.getId() + " \u5df2\u53d6\u6d88");
            recordWaitlistEvent("WAITLIST_CANCELLED", waitlist);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public void sign(SignRequest request) {
        User user = UserContext.getRequired();
        ReservationOrder order = reservationOrderMapper.findById(request.getOrderId());
        if (order == null || !user.getId().equals(order.getUserId())) {
            throw new IllegalArgumentException("预约单不存在或不属于当前用户");
        }
        if (!isWithinSignWindow(order)) {
            throw new IllegalArgumentException("当前不在签到窗口内，签到时间为预约开始前 15 分钟到开始后 15 分钟");
        }
        int updated = reservationOrderMapper.sign(request.getOrderId(), user.getId(), request.getSignCode());
        if (updated == 0) {
            throw new IllegalArgumentException("签到失败，请检查预约状态或签到码");
        }
        String key = RedisKeys.USER_SIGN + user.getId() + ":" + YearMonth.now();
        stringRedisTemplate.opsForValue().setBit(key, LocalDate.now().getDayOfMonth() - 1, true);
        creditService.rewardCheckin(user.getId(), request.getOrderId());
        publishNotify(user.getId(), "签到成功", "预约单 " + request.getOrderId() + " 已签到");
        recordReservationEvent("CHECKIN_SUCCESS", order);
    }

    @Override
    public List<ReservationOrder> list(Long roomId, LocalDate reserveDate, Integer status, Integer limit) {
        User user = UserContext.getRequired();
        Long userId = "ADMIN".equals(user.getRole()) ? null : user.getId();
        return reservationOrderMapper.list(userId, roomId, reserveDate, status, limit == null ? 50 : limit);
    }

    @Override
    public List<WaitlistOrder> listWaitlist(Long roomId, LocalDate reserveDate, Integer status, Integer limit) {
        expireExpiredWaitlists();
        User user = UserContext.getRequired();
        Long userId = "ADMIN".equals(user.getRole()) ? null : user.getId();
        return waitlistMapper.list(userId, roomId, reserveDate, status, limit == null ? 50 : limit);
    }

    @Override
    public Map<String, Object> dashboard() {
        expireExpiredWaitlists();
        User user = UserContext.getRequired();
        boolean admin = "ADMIN".equals(user.getRole());
        Map<String, Object> result = new HashMap<>();
        result.put("todaySubmitCount", admin ? reservationOrderMapper.todayCount() : reservationOrderMapper.todayCountByUser(user.getId()));
        result.put("successCount", countReservationStatus(admin, user.getId(), ReservationStatus.RESERVED));
        result.put("cancelCount", countReservationStatus(admin, user.getId(), ReservationStatus.CANCELED));
        result.put("signedCount", countReservationStatus(admin, user.getId(), ReservationStatus.SIGNED));
        result.put("noShowCount", countReservationStatus(admin, user.getId(), ReservationStatus.NO_SHOW));
        result.put("creditScore", admin ? null : creditService.getOrCreate(user.getId()).getCreditScore());
        result.put("waitingCount", admin ? waitlistMapper.waitingCount() : waitlistMapper.waitingCountByUser(user.getId()));
        result.put("scope", admin ? "GLOBAL" : "MINE");
        List<Map<String, Object>> rank = new ArrayList<>();
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(RedisKeys.HOT_ROOM_RANK, 0, 9);
        if (tuples != null) {
            for (org.springframework.data.redis.core.ZSetOperations.TypedTuple<String> tuple : tuples) {
                Map<String, Object> item = new HashMap<>();
                item.put("roomId", tuple.getValue());
                item.put("score", tuple.getScore());
                rank.add(item);
            }
        }
        result.put("hotRoomRank", rank);
        return result;
    }

    private long countReservationStatus(boolean admin, Long userId, Integer status) {
        return admin ? reservationOrderMapper.countByStatus(status) : reservationOrderMapper.countByStatusAndUser(status, userId);
    }

    @Override
    @Scheduled(fixedDelayString = "${reservation.waitlist.expire-delay-ms:60000}",
            initialDelayString = "${reservation.waitlist.expire-initial-delay-ms:15000}")
    @Transactional
    public int expireExpiredWaitlists() {
        int total = 0;
        while (true) {
            List<WaitlistOrder> expired = waitlistMapper.listExpiredCandidates(
                    LocalDate.now(), LocalTime.now().format(WAITLIST_EXPIRE_FORMATTER), 200);
            if (expired.isEmpty()) {
                return total;
            }
            int updatedInBatch = 0;
            for (WaitlistOrder waitlist : expired) {
                int updated = waitlistMapper.updateStatus(waitlist.getId(), WaitlistStatus.WAITING, WaitlistStatus.EXPIRED);
                if (updated > 0) {
                    updatedInBatch += updated;
                    publishNotify(waitlist.getUserId(), "\u5019\u8865\u5df2\u8fc7\u671f",
                            "\u5019\u8865\u5355 " + waitlist.getId() + " \u5df2\u8d85\u8fc7\u53ef\u9884\u7ea6\u65f6\u95f4\uff0c\u7cfb\u7edf\u5df2\u81ea\u52a8\u5173\u95ed");
                    recordWaitlistEvent("WAITLIST_EXPIRED", waitlist);
                }
            }
            total += updatedInBatch;
            if (expired.size() < 200 || updatedInBatch == 0) {
                return total;
            }
        }
    }

    @Override
    @Scheduled(fixedDelayString = "${reservation.no-show.scan-delay-ms:60000}",
            initialDelayString = "${reservation.no-show.initial-delay-ms:20000}")
    @Transactional
    public int markNoShowReservations() {
        int total = 0;
        while (true) {
            List<ReservationOrder> candidates = reservationOrderMapper.listReservedCandidates(LocalDate.now(), 200);
            int updatedInBatch = 0;
            for (ReservationOrder order : candidates) {
                if (!isNoShowDeadlinePassed(order)) {
                    continue;
                }
                int updated = reservationOrderMapper.markNoShow(order.getId());
                if (updated == 0) {
                    continue;
                }
                updatedInBatch += updated;
                roomSlotMapper.increaseStock(order.getRoomId(), order.getReserveDate(), order.getTimeSlot());
                rollbackRedisReservationForCancel(order);
                creditService.punishNoShow(order.getUserId(), order.getId());
                publishNotify(order.getUserId(), "未签到扣分",
                        "预约单 " + order.getId() + " 超过签到窗口未签到，已扣减信用分");
                recordReservationEvent("NO_SHOW", order);
                tryPromoteWaiter(order.getRoomId(), order.getReserveDate(), order.getTimeSlot());
            }
            total += updatedInBatch;
            if (candidates.size() < 200 || updatedInBatch == 0) {
                return total;
            }
        }
    }

    private void consumeSubmitToken(Long userId, String submitToken) {
        if (submitToken == null || submitToken.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少预约提交令牌");
        }
        String key = submitTokenKey(userId, submitToken);
        Boolean deleted = stringRedisTemplate.delete(key);
        if (!Boolean.TRUE.equals(deleted)) {
            throw new IllegalArgumentException("预约提交令牌无效或已使用");
        }
    }

    private boolean isNoShowDeadlinePassed(ReservationOrder order) {
        if (order == null || order.getReserveDate() == null || order.getTimeSlot() == null) {
            return false;
        }
        LocalTime startTime = parseStartTime(order.getTimeSlot());
        LocalDateTime deadline = LocalDateTime.of(order.getReserveDate(), startTime).plusMinutes(15);
        return LocalDateTime.now().isAfter(deadline);
    }

    private boolean isWithinSignWindow(ReservationOrder order) {
        if (order == null || order.getReserveDate() == null || order.getTimeSlot() == null) {
            return false;
        }
        LocalDateTime start = LocalDateTime.of(order.getReserveDate(), parseStartTime(order.getTimeSlot()));
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(start.minusMinutes(15)) && !now.isAfter(start.plusMinutes(15));
    }

    private LocalTime parseStartTime(String timeSlot) {
        if (timeSlot == null || timeSlot.length() < 5) {
            throw new IllegalArgumentException("预约时间段格式不正确");
        }
        return LocalTime.parse(timeSlot.substring(0, 5), WAITLIST_EXPIRE_FORMATTER);
    }

    private RoomSlot loadOpenSlotAndInitRedisStock(ReserveRequest request) {
        RoomSlot slot = roomSlotMapper.find(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        if (slot == null) {
            throw new IllegalArgumentException("该教室时段未开放，无法预约");
        }
        if (slot.getStatus() == null || slot.getStatus() != 1) {
            throw new IllegalArgumentException("该教室时间段暂不可预约");
        }
        String stockKey = stockKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(slot.getAvailableCapacity()), Duration.ofDays(2));
        return slot;
    }

    private Long executeReserveScript(ReserveRequest request, Long userId) {
        String stockKey = stockKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        return stringRedisTemplate.execute(RESERVE_SCRIPT,
                Arrays.asList(stockKey,
                        usersKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot()),
                        userTimeKey(userId, request.getReserveDate(), request.getTimeSlot())),
                String.valueOf(userId),
                String.valueOf(Duration.ofDays(2).getSeconds()),
                stockKey);
    }

    private void registerRedisRollbackOnTransactionRollback(ReserveRequest request,
                                                            Long userId,
                                                            AtomicBoolean redisReservationActive) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED && redisReservationActive.get()) {
                    rollbackRedisReservation(request, userId);
                    redisReservationActive.set(false);
                    log.warn("rollback redis reservation after transaction rollback, userId={}, roomId={}, reserveDate={}, timeSlot={}",
                            userId, request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
                }
            }
        });
    }

    private ReserveResponse joinWaitlist(ReserveRequest request, User user, RoomSlot slot) {
        WaitlistOrder order = new WaitlistOrder();
        order.setId(idGeneratorService.nextId("waitlist"));
        order.setRoomId(request.getRoomId());
        order.setRoomSlotId(slot.getId());
        order.setUserId(user.getId());
        order.setReserveDate(request.getReserveDate());
        order.setTimeSlot(request.getTimeSlot());
        order.setStatus(WaitlistStatus.WAITING);
        int inserted = waitlistMapper.insertIgnore(order);
        if (inserted == 0) {
            WaitlistOrder existing = waitlistMapper.findWaiting(user.getId(), request.getRoomId(),
                    request.getReserveDate(), request.getTimeSlot());
            if (existing != null) {
                return waitlistSuccess(existing, false);
            }
            throw new IllegalArgumentException("你已在候补队列中");
        }
        // Keep waitlist_count off the hot reservation path; reconcile it from tb_reserve_waitlist.
        publishNotify(user.getId(), "候补成功", "已加入候补队列，候补单：" + order.getId());
        recordWaitlistEvent("WAITLIST_JOINED", order);
        return waitlistSuccess(order, true);
    }

    private ReserveResponse waitlistSuccess(WaitlistOrder order, boolean created) {
        ReserveResponse response = new ReserveResponse();
        response.setWaitlistId(order.getId());
        response.setStatus("WAITLIST");
        if (!created) {
            response.setStatus("WAITLIST_EXISTS");
        }
        return response;
    }

    @Scheduled(fixedDelayString = "${reservation.waitlist.promote-delay-ms:3000}",
            initialDelayString = "${reservation.waitlist.promote-initial-delay-ms:5000}")
    public int promoteAvailableWaitlists() {
        int promoted = 0;
        List<RoomSlot> slots = roomSlotMapper.listPromotableWaitlistSlots(50);
        for (RoomSlot slot : slots) {
            int attempts = Math.max(0, Math.min(slot.getAvailableCapacity() == null ? 0 : slot.getAvailableCapacity(), 50));
            for (int i = 0; i < attempts; i++) {
                if (!tryPromoteWaiter(slot.getRoomId(), slot.getReserveDate(), slot.getTimeSlot())) {
                    break;
                }
                promoted++;
            }
        }
        return promoted;
    }

    private boolean tryPromoteWaiter(Long roomId, LocalDate reserveDate, String timeSlot) {
        try {
            return promoteWaiter(roomId, reserveDate, timeSlot);
        } catch (RuntimeException exception) {
            log.warn("promote waitlist failed, roomId={}, reserveDate={}, timeSlot={}, error={}",
                    roomId, reserveDate, timeSlot, exception.getMessage());
            return false;
        }
    }

    private boolean promoteWaiter(Long roomId, LocalDate reserveDate, String timeSlot) {
        expireExpiredWaitlists();
        WaitlistOrder waiter = waitlistMapper.firstWaiting(roomId, reserveDate, timeSlot);
        if (waiter == null) {
            return false;
        }
        if (reservationOrderMapper.countActiveByUser(waiter.getUserId(), reserveDate, timeSlot) > 0) {
            waitlistMapper.updateStatus(waiter.getId(), WaitlistStatus.WAITING, WaitlistStatus.SKIPPED);
            return promoteWaiter(roomId, reserveDate, timeSlot);
        }
        ReserveRequest request = new ReserveRequest();
        request.setRoomId(roomId);
        request.setReserveDate(reserveDate);
        request.setTimeSlot(timeSlot);
        Long redisResult = executeReserveScript(request, waiter.getUserId());
        if (redisResult == null || redisResult != 0) {
            return false;
        }
        int stockUpdated = roomSlotMapper.decreaseStock(roomId, reserveDate, timeSlot);
        if (stockUpdated == 0) {
            rollbackRedisReservation(request, waiter.getUserId());
            return false;
        }
        int promoted = waitlistMapper.updateStatus(waiter.getId(), WaitlistStatus.WAITING, WaitlistStatus.PROMOTED);
        if (promoted == 0) {
            rollbackRedisReservation(request, waiter.getUserId());
            roomSlotMapper.increaseStock(roomId, reserveDate, timeSlot);
            return false;
        }
        User waiterUser = new User();
        waiterUser.setId(waiter.getUserId());
        ReservationOrder order = buildOrder(request, waiterUser, "候补自动补位成功");
        RoomSlot slot = roomSlotMapper.find(roomId, reserveDate, timeSlot);
        order.setRoomSlotId(slot == null ? null : slot.getId());
        reservationOrderMapper.insert(order);
        publishNotify(waiter.getUserId(), "候补补位成功", "候补已转为正式预约，预约单：" + order.getId());
        recordWaitlistEvent("WAITLIST_PROMOTED", waiter);
        recordReservationEvent("RESERVATION_SUCCESS", order);
        return true;
    }

    private ReservationOrder buildOrder(ReserveRequest request, User user, String remark) {
        long orderId = idGeneratorService.nextId("reserve");
        ReservationOrder order = new ReservationOrder();
        order.setId(orderId);
        order.setRoomId(request.getRoomId());
        order.setUserId(user.getId());
        order.setReserveDate(request.getReserveDate());
        order.setTimeSlot(request.getTimeSlot());
        order.setActiveKey(user.getId() + ":" + request.getReserveDate() + ":" + request.getTimeSlot());
        order.setStatus(ReservationStatus.RESERVED);
        order.setSignCode(String.format("%06d", Math.abs(orderId + user.getId()) % 1000000));
        order.setRemark(remark);
        order.setCheckinDeadline(LocalDateTime.of(request.getReserveDate(), parseStartTime(request.getTimeSlot())).plusMinutes(15));
        return order;
    }

    private ReserveResponse reserveSuccess(ReservationOrder order) {
        ReserveResponse response = new ReserveResponse();
        response.setOrderId(order.getId());
        response.setSignCode(order.getSignCode());
        response.setStatus("RESERVED");
        return response;
    }

    private void rollbackRedisReservation(ReserveRequest request, Long userId) {
        stringRedisTemplate.opsForValue().increment(stockKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot()));
        stringRedisTemplate.opsForSet().remove(usersKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot()), String.valueOf(userId));
        stringRedisTemplate.delete(userTimeKey(userId, request.getReserveDate(), request.getTimeSlot()));
    }

    private void rollbackRedisReservationForCancel(ReservationOrder order) {
        stringRedisTemplate.opsForValue().increment(stockKey(order.getRoomId(), order.getReserveDate(), order.getTimeSlot()));
        stringRedisTemplate.opsForSet().remove(usersKey(order.getRoomId(), order.getReserveDate(), order.getTimeSlot()), String.valueOf(order.getUserId()));
        stringRedisTemplate.delete(userTimeKey(order.getUserId(), order.getReserveDate(), order.getTimeSlot()));
    }

    private void syncRedisReservationFromDb(ReserveRequest request, Long userId) {
        stringRedisTemplate.opsForSet().remove(usersKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot()), String.valueOf(userId));
        stringRedisTemplate.delete(userTimeKey(userId, request.getReserveDate(), request.getTimeSlot()));
        RoomSlot slot = roomSlotMapper.find(request.getRoomId(), request.getReserveDate(), request.getTimeSlot());
        if (slot != null && slot.getAvailableCapacity() != null) {
            int stock = Math.max(0, slot.getAvailableCapacity());
            stringRedisTemplate.opsForValue().set(
                    stockKey(request.getRoomId(), request.getReserveDate(), request.getTimeSlot()),
                    String.valueOf(stock),
                    Duration.ofDays(2));
        }
    }

    private void recordReservationEvent(String eventType, ReservationOrder order) {
        if (order == null) {
            return;
        }
        Long roomSlotId = resolveRoomSlotId(order.getRoomSlotId(), order.getRoomId(), order.getReserveDate(), order.getTimeSlot());
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("status", order.getStatus());
        attributes.put("signCode", order.getSignCode());
        domainEventService.recordEvent(eventType, "RESERVATION", order.getId(), order.getUserId(),
                order.getRoomId(), roomSlotId, order.getId(), null,
                order.getReserveDate(), order.getTimeSlot(), attributes);
    }

    private void recordWaitlistEvent(String eventType, WaitlistOrder waitlist) {
        if (waitlist == null) {
            return;
        }
        Long roomSlotId = resolveRoomSlotId(waitlist.getRoomSlotId(), waitlist.getRoomId(), waitlist.getReserveDate(), waitlist.getTimeSlot());
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("status", waitlist.getStatus());
        domainEventService.recordEvent(eventType, "WAITLIST", waitlist.getId(), waitlist.getUserId(),
                waitlist.getRoomId(), roomSlotId, null, waitlist.getId(),
                waitlist.getReserveDate(), waitlist.getTimeSlot(), attributes);
    }

    private Long resolveRoomSlotId(Long roomSlotId, Long roomId, LocalDate reserveDate, String timeSlot) {
        if (roomSlotId != null) {
            return roomSlotId;
        }
        RoomSlot slot = roomSlotMapper.find(roomId, reserveDate, timeSlot);
        return slot == null ? null : slot.getId();
    }

    private void publishNotify(Long userId, String title, String content) {
        notificationOutboxService.enqueue(userId, title, content);
    }

    private String submitTokenKey(Long userId, String token) {
        return RedisKeys.SUBMIT_TOKEN + userId + ":" + token;
    }

    private String stockKey(Long roomId, LocalDate reserveDate, String timeSlot) {
        return RedisKeys.RESERVE_STOCK + roomId + ":" + reserveDate + ":" + timeSlot;
    }

    private String usersKey(Long roomId, LocalDate reserveDate, String timeSlot) {
        return RedisKeys.RESERVE_USERS + roomId + ":" + reserveDate + ":" + timeSlot;
    }

    private String userTimeKey(Long userId, LocalDate reserveDate, String timeSlot) {
        return RedisKeys.RESERVE_USER_TIME + userId + ":" + reserveDate + ":" + timeSlot;
    }

}
