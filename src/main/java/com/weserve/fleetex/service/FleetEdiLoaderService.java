package com.weserve.fleetex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${fleet.source.db:SparcsN4_FMS}")
    private String sourceDb;

    public FleetEdiLoaderService(JobLauncher jobLauncher, Job importFleetEdiJob, JdbcTemplate jdbcTemplate, FleetEdiComparisonService comparisonService, BatchStatusService batchStatusService) {
        this.jobLauncher = jobLauncher;
        this.importFleetEdiJob = importFleetEdiJob;
        this.jdbcTemplate = jdbcTemplate;
        this.comparisonService = comparisonService;
        this.batchStatusService = batchStatusService;
    }

    @Async
    public void loadEdiFile(String filePath, String processId, String fieldOrder, String line) {
        try {
            String tableName = (line != null && !line.isEmpty()) ? "fleet_edi_" + line : "fleet_edi";
            
            // Check if table exists before truncate
            String checkTableSql = String.format("IF OBJECT_ID('%s', 'U') IS NOT NULL SELECT 1 ELSE SELECT 0", tableName);
            Integer exists = jdbcTemplate.queryForObject(checkTableSql, Integer.class);

            if (exists != null && exists == 1) {
                batchStatusService.updateStatus(processId, "PROCESSING", 35, "Cleaning up staging table...");
                log.info("Cleaning up {} table before loading new data.", tableName);
                jdbcTemplate.execute("TRUNCATE TABLE " + tableName);
            } else {
                log.info("Table {} does not exist. Creating it from fleet_edi template.", tableName);
                batchStatusService.updateStatus(processId, "PROCESSING", 35, "Creating staging table...");
                jdbcTemplate.execute(String.format("SELECT * INTO %s FROM fleet_edi WHERE 1=0", tableName));
            }

            batchStatusService.updateStatus(processId, "PROCESSING", 40, "Starting batch job...");
            log.info("Starting loading EDI file from: {}", filePath);
            JobParametersBuilder paramsBuilder = new JobParametersBuilder()
                    .addString("filePath", filePath)
                    .addString("tableName", tableName)
                    .addLong("time", System.currentTimeMillis());
            
            if (fieldOrder != null && !fieldOrder.isEmpty()) {
                paramsBuilder.addString("fieldOrder", fieldOrder);
            }
            
            JobParameters jobParameters = paramsBuilder.toJobParameters();
            jobLauncher.run(importFleetEdiJob, jobParameters);
            batchStatusService.updateStatus(processId, "PROCESSING", 80, "Batch job completed. Comparing records...");
            log.info("Successfully triggered batch job for EDI file: {}", filePath);

            // After loading, compare and generate file
            if (tableName != null) {
                comparisonService.compareAndGenerateFile(processId, tableName);
            } else {
                comparisonService.compareAndGenerateFile(processId, "fleet_edi");
            }
            batchStatusService.updateStatus(processId, "COMPLETED", 100, "Process completed successfully!");
            
        } catch (Exception e) {
            log.error("Error triggering batch job for EDI file: {}", filePath, e);
            batchStatusService.updateStatus(processId, "FAILED", 0, "Error: " + e.getMessage());
            throw new RuntimeException("Failed to load EDI file: " + e.getMessage(), e);
        }
    }

    public java.util.List<String> getLines() {
        ensureTemplateTableExists();
        String sql = String.format("SELECT DISTINCT id FROM [%s].[dbo].[ref_bizunit_scoped] WHERE id <> 'UNK' AND id IS NOT NULL AND role='LINEOP' AND life_cycle_state = 'ACT'", sourceDb);
        return jdbcTemplate.queryForList(sql, String.class);
    }

    private void ensureTemplateTableExists() {
        try {
            String checkTableSql = "IF OBJECT_ID('fleet_edi', 'U') IS NOT NULL SELECT 1 ELSE SELECT 0";
            Integer exists = jdbcTemplate.queryForObject(checkTableSql, Integer.class);

            if (exists == null || exists == 0) {
                log.info("Template table fleet_edi does not exist. Creating it...");
                String createTableSql = "CREATE TABLE fleet_edi (" +
                        "id INT IDENTITY(1,1) PRIMARY KEY, " +
                        "containerNbr VARCHAR(50), " +
                        "typeIso VARCHAR(50), " +
                        "cargoType VARCHAR(50), " +
                        "length INT, " +
                        "variant VARCHAR(50), " +
                        "tarewt INT, " +
                        "safewt INT, " +
                        "code VARCHAR(50), " +
                        "reserve VARCHAR(MAX), " +
                        "year VARCHAR(10)" +
                        ")";
                jdbcTemplate.execute(createTableSql);
                log.info("Successfully created template table fleet_edi.");
            }
        } catch (Exception e) {
            log.error("Error ensuring template table fleet_edi exists", e);
            // We don't want to fail the whole line loading process if this check fails,
            // but it's critical for processing. However, if it's already there, it's fine.
        }
    }
}
