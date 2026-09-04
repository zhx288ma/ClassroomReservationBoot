package com.xuan.boot.dto;

public class NotificationMessage {
    private Long eventId;
    private Long userId;
    private String title;
    private String content;

    public NotificationMessage() {
    }

    public NotificationMessage(Long eventId, Long userId, String title, String content) {
        this.eventId = eventId;
        this.userId = userId;
        this.title = title;
        this.content = content;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
