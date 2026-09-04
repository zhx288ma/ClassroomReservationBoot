package com.xuan.boot.service.impl;

import com.xuan.boot.domain.FeedbackStatus;
import com.xuan.boot.domain.FeedbackTicket;
import com.xuan.boot.domain.User;
import com.xuan.boot.dto.FeedbackCreateRequest;
import com.xuan.boot.dto.FeedbackReplyRequest;
import com.xuan.boot.mapper.FeedbackMapper;
import com.xuan.boot.mapper.UserMapper;
import com.xuan.boot.service.FeedbackService;
import com.xuan.boot.service.IdGeneratorService;
import com.xuan.boot.service.NotificationOutboxService;
import com.xuan.boot.support.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FeedbackServiceImpl implements FeedbackService {
    private final FeedbackMapper feedbackMapper;
    private final UserMapper userMapper;
    private final IdGeneratorService idGeneratorService;
    private final NotificationOutboxService notificationOutboxService;

    public FeedbackServiceImpl(FeedbackMapper feedbackMapper,
                               UserMapper userMapper,
                               IdGeneratorService idGeneratorService,
                               NotificationOutboxService notificationOutboxService) {
        this.feedbackMapper = feedbackMapper;
        this.userMapper = userMapper;
        this.idGeneratorService = idGeneratorService;
        this.notificationOutboxService = notificationOutboxService;
    }

    @Override
    @Transactional
    public FeedbackTicket create(FeedbackCreateRequest request) {
        User user = UserContext.getRequired();
        if (!"USER".equals(user.getRole())) {
            throw new IllegalArgumentException("只有学生账号可以提交反馈");
        }
        FeedbackTicket ticket = new FeedbackTicket();
        ticket.setId(idGeneratorService.nextId("feedback"));
        ticket.setUserId(user.getId());
        ticket.setTitle(request.getTitle().trim());
        ticket.setContent(request.getContent().trim());
        ticket.setStatus(FeedbackStatus.OPEN);
        feedbackMapper.insert(ticket);
        notifyAdmins("新的学生反馈", user.getUsername() + " 提交了反馈：" + ticket.getTitle());
        return feedbackMapper.findById(ticket.getId());
    }

    @Override
    public List<FeedbackTicket> list(Integer status, Integer limit) {
        User user = UserContext.getRequired();
        Long userId = "ADMIN".equals(user.getRole()) ? null : user.getId();
        int safeLimit = Math.min(Math.max(limit == null ? 50 : limit, 1), 100);
        return feedbackMapper.list(userId, status, safeLimit);
    }

    @Override
    @Transactional
    public FeedbackTicket reply(Long id, FeedbackReplyRequest request) {
        User admin = requireAdmin();
        FeedbackTicket ticket = feedbackMapper.findById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("反馈工单不存在");
        }
        int updated = feedbackMapper.reply(id, admin.getId(), request.getReply().trim());
        if (updated == 0) {
            throw new IllegalArgumentException("当前反馈状态不允许回复");
        }
        notificationOutboxService.enqueue(ticket.getUserId(), "管理员已回复反馈",
                "你的反馈「" + ticket.getTitle() + "」已有回复，请到问题反馈页查看");
        return feedbackMapper.findById(id);
    }

    @Override
    @Transactional
    public void close(Long id) {
        User user = UserContext.getRequired();
        FeedbackTicket ticket = feedbackMapper.findById(id);
        if (ticket == null) {
            throw new IllegalArgumentException("反馈工单不存在");
        }
        if (!"ADMIN".equals(user.getRole()) && !ticket.getUserId().equals(user.getId())) {
            throw new IllegalArgumentException("只能关闭自己的反馈");
        }
        int updated = feedbackMapper.close(id);
        if (updated == 0) {
            throw new IllegalArgumentException("当前反馈状态不允许关闭");
        }
        if ("ADMIN".equals(user.getRole())) {
            notificationOutboxService.enqueue(ticket.getUserId(), "反馈已关闭",
                    "你的反馈「" + ticket.getTitle() + "」已由管理员关闭");
        } else {
            notifyAdmins("学生关闭反馈", user.getUsername() + " 关闭了反馈：" + ticket.getTitle());
        }
    }

    private User requireAdmin() {
        User user = UserContext.getRequired();
        if (!"ADMIN".equals(user.getRole())) {
            throw new IllegalArgumentException("只有管理员可以处理反馈");
        }
        return user;
    }

    private void notifyAdmins(String title, String content) {
        for (User admin : userMapper.listActiveAdmins()) {
            notificationOutboxService.enqueue(admin.getId(), title, content);
        }
    }
}
