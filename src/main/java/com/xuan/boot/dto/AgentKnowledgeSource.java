package com.xuan.boot.dto;

public class AgentKnowledgeSource {
    private Long documentId;
    private Long chunkId;
    private String title;
    private String category;
    private String excerpt;
    private double score;

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}
