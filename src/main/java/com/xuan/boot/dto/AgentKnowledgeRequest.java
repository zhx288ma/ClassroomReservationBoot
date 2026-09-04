package com.xuan.boot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AgentKnowledgeRequest {
    @NotBlank(message = "知识标题不能为空")
    @Size(max = 160)
    private String title;
    @Size(max = 64)
    private String category = "POLICY";
    @NotBlank(message = "知识内容不能为空")
    @Size(max = 12000)
    private String content;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
