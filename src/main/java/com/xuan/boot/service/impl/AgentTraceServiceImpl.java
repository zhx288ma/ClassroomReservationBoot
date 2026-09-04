package com.xuan.boot.service.impl;

import com.xuan.boot.domain.AgentTrace;
import com.xuan.boot.mapper.AgentTraceMapper;
import com.xuan.boot.service.AgentTraceService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentTraceServiceImpl implements AgentTraceService {
    private final AgentTraceMapper agentTraceMapper;

    public AgentTraceServiceImpl(AgentTraceMapper agentTraceMapper) {
        this.agentTraceMapper = agentTraceMapper;
    }

    @Override
    public void record(AgentTrace trace) {
        try {
            agentTraceMapper.insert(trace);
        } catch (RuntimeException ignored) {
            // Trace persistence cannot become a dependency of the user-facing assistant.
        }
    }

    @Override
    public List<AgentTrace> listLatest(int limit) {
        return agentTraceMapper.listLatest(Math.min(Math.max(limit, 1), 100));
    }
}
