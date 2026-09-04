package com.xuan.boot.domain;

import java.time.LocalDateTime;

public class AgentKnowledgeDocument {
    private Long id;
    private String title;
    private String category;
    private String sourceType;
    private String sourceFileName;
    private String sourceFilePath;
    private String content;
    private String contentHash;
    private Integer status;
    private String indexStatus;
    private Integer chunkCount;
    private Integer vectorCount;
    private LocalDateTime lastIndexedAt;
    private String lastIndexError;
    private Long createdBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceFileName() { return sourceFileName; }
    public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }
    public String getSourceFilePath() { return sourceFilePath; }
    public void setSourceFilePath(String sourceFilePath) { this.sourceFilePath = sourceFilePath; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public Integer getVectorCount() { return vectorCount; }
    public void setVectorCount(Integer vectorCount) { this.vectorCount = vectorCount; }
    public LocalDateTime getLastIndexedAt() { return lastIndexedAt; }
    public void setLastIndexedAt(LocalDateTime lastIndexedAt) { this.lastIndexedAt = lastIndexedAt; }
    public String getLastIndexError() { return lastIndexError; }
    public void setLastIndexError(String lastIndexError) { this.lastIndexError = lastIndexError; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
