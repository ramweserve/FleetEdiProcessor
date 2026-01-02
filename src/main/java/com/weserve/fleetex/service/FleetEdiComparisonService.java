package com.weserve.fleetex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FleetEdiComparisonService {
    private static final Logger log = LoggerFactory.getLogger(FleetEdiComparisonService.class);

    private final JdbcTemplate jdbcTemplate;
    private final BatchStatusService batchStatusService;

    @Value("${fleet.target.db:TestSTAGING}")
    private String targetDb;

    @Value("${fleet.target.schema:dbo}")
    private String targetSchema;

    public FleetEdiComparisonService(JdbcTemplate jdbcTemplate, BatchStatusService batchStatusService) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchStatusService = batchStatusService;
    }

    public void compareAndGenerateFile(String processId) {
        log.info("Starting comparison between fleet_edi_staging and ref_equipment.");
        batchStatusService.updateStatus(processId, "PROCESSING", 85, "Comparing records and generating file...");

        String sql = String.format(
                "SELECT fes.containerNbr, fes.typeIso, fes.cargoType, fes.length, fes.variant, fes.tarewt, fes.safewt, fes.code, fes.reserve, fes.year " +
                "FROM [%s].[%s].[fleet_edi_staging] fes " +
                "JOIN [%s].[%s].[ref_equipment] re ON fes.containerNbr COLLATE DATABASE_DEFAULT = re.id_full COLLATE DATABASE_DEFAULT " +
                "WHERE fes.tarewt <> re.tare_kg OR fes.safewt <> re.safe_kg",
                targetDb, targetSchema, targetDb, targetSchema);

        List<Map<String, Object>> deviatedRecords = jdbcTemplate.queryForList(sql);

        if (deviatedRecords.isEmpty()) {
            log.info("No deviated records found.");
            return;
        }

        log.info("Found {} deviated records. Generating file.", deviatedRecords.size());
        generateFile(deviatedRecords);
    }

    private void generateFile(List<Map<String, Object>> records) {
        String userHome = System.getProperty("user.home");
        Path downloadsPath = Paths.get(userHome, "Downloads");
        
        if (!Files.exists(downloadsPath)) {
            try {
                Files.createDirectories(downloadsPath);
            } catch (IOException e) {
                log.error("Could not create downloads directory: {}", downloadsPath, e);
                return;
            }
        }

        String fileName = "deviated_fleet_edi_" + System.currentTimeMillis() + ".csv";
        Path filePath = downloadsPath.resolve(fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            for (Map<String, Object> record : records) {
                String line = formatRecord(record);
                writer.write(line);
                writer.newLine();
            }
            log.info("Deviated records file generated at: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Error writing deviated records to file", e);
        }
    }

    private String formatRecord(Map<String, Object> record) {
        // "XINU1323966","22G1","CT",20,"ST",2185,30480,"V",,"2007"
        return String.format("\"%s\",\"%s\",\"%s\",%s,\"%s\",%s,%s,\"%s\",%s,\"%s\"",
                escape(record.get("containerNbr")),
                escape(record.get("typeIso")),
                escape(record.get("cargoType")),
                record.get("length"),
                escape(record.get("variant")),
                record.get("tarewt"),
                record.get("safewt"),
                escape(record.get("code")),
                record.get("reserve") != null && !record.get("reserve").toString().isEmpty() ? "\"" + record.get("reserve") + "\"" : "",
                escape(record.get("year"))
        );
    }

    private String escape(Object value) {
        return value == null ? "" : value.toString();
    }
}
