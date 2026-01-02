package com.weserve.fleetex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class FleetEdiLoaderService {
    private static final Logger log = LoggerFactory.getLogger(FleetEdiLoaderService.class);

    private final JobLauncher jobLauncher;
    private final Job importFleetEdiJob;
    private final JdbcTemplate jdbcTemplate;
    private final FleetEdiComparisonService comparisonService;
    private final BatchStatusService batchStatusService;

    public FleetEdiLoaderService(JobLauncher jobLauncher, Job importFleetEdiJob, JdbcTemplate jdbcTemplate, FleetEdiComparisonService comparisonService, BatchStatusService batchStatusService) {
        this.jobLauncher = jobLauncher;
        this.importFleetEdiJob = importFleetEdiJob;
        this.jdbcTemplate = jdbcTemplate;
        this.comparisonService = comparisonService;
        this.batchStatusService = batchStatusService;
    }

    @Async
    public void loadEdiFile(String filePath, String processId, String fieldOrder) {
        try {
            batchStatusService.updateStatus(processId, "PROCESSING", 35, "Cleaning up staging table...");
            log.info("Cleaning up fleet_edi_staging table before loading new data.");
            jdbcTemplate.execute("TRUNCATE TABLE fleet_edi_staging");

            batchStatusService.updateStatus(processId, "PROCESSING", 40, "Starting batch job...");
            log.info("Starting loading EDI file from: {}", filePath);
            JobParametersBuilder paramsBuilder = new JobParametersBuilder()
                    .addString("filePath", filePath)
                    .addLong("time", System.currentTimeMillis());
            
            if (fieldOrder != null && !fieldOrder.isEmpty()) {
                paramsBuilder.addString("fieldOrder", fieldOrder);
            }
            
            JobParameters jobParameters = paramsBuilder.toJobParameters();
            jobLauncher.run(importFleetEdiJob, jobParameters);
            batchStatusService.updateStatus(processId, "PROCESSING", 80, "Batch job completed. Comparing records...");
            log.info("Successfully triggered batch job for EDI file: {}", filePath);

            // After loading, compare and generate file
            comparisonService.compareAndGenerateFile(processId);
            batchStatusService.updateStatus(processId, "COMPLETED", 100, "Process completed successfully!");
            
        } catch (Exception e) {
            log.error("Error triggering batch job for EDI file: {}", filePath, e);
            batchStatusService.updateStatus(processId, "FAILED", 0, "Error: " + e.getMessage());
            throw new RuntimeException("Failed to load EDI file: " + e.getMessage(), e);
        }
    }
}
