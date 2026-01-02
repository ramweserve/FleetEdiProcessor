package com.weserve.fleetex.service;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BatchStatusService {

    public static class StatusInfo {
        public String status;
        public int progress;
        public String message;

        public StatusInfo(String status, int progress, String message) {
            this.status = status;
            this.progress = progress;
            this.message = message;
        }
    }

    private final Map<String, StatusInfo> statusMap = new ConcurrentHashMap<>();

    public void updateStatus(String processId, String status, int progress, String message) {
        statusMap.put(processId, new StatusInfo(status, progress, message));
    }

    public StatusInfo getStatus(String processId) {
        return statusMap.get(processId);
    }
    
    public void removeStatus(String processId) {
        statusMap.remove(processId);
    }
}
