package com.xuan.boot.service;

import com.xuan.boot.domain.AgentTrace;

import java.util.List;

public interface AgentTraceService {
    void record(AgentTrace trace);

    List<AgentTrace> listLatest(int limit);
}
