package com.weserve.fleetex.service;
/**
 * @author <a href="mailto:sramasamy@weservetech.com"> Ramasamy Sathappan</a>
 * @since 26-Dec-2025
 **/
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CopyEquipmentService {
    private static final Logger log = LoggerFactory.getLogger(CopyEquipmentService.class);

    private final JdbcTemplate jdbcTemplate;
    private final BatchStatusService batchStatusService;

    @Value("${fleet.source.schema:dbo}")
    private String sourceSchema;

    @Value("${fleet.target.schema:dbo}")
    private String targetSchema;

    @Value("${fleet.source.db:SparcsN4_FMS}")
    private String sourceDb;

    @Value("${fleet.target.db:TestSTAGING}")
    private String targetDb;

    public CopyEquipmentService(JdbcTemplate jdbcTemplate, BatchStatusService batchStatusService) {
        this.jdbcTemplate = jdbcTemplate;
        this.batchStatusService = batchStatusService;
    }

    public void copyRefEquipmentTable(String processId) {
        log.info("Starting copy of ref_equipment table from {}.{} to {}.{}", sourceDb, sourceSchema, targetDb, targetSchema);
        batchStatusService.updateStatus(processId, "PROCESSING", 10, "Copying ref_equipment table...");

        String targetTable = String.format("[%s].[%s].[stage_ref_equipment]", targetDb, targetSchema);
        String sourceTable = String.format("[%s].[%s].[ref_equipment]", sourceDb, sourceSchema);

        try {
            // Check if target table exists
            String checkTableSql = String.format("IF OBJECT_ID('%s', 'U') IS NOT NULL SELECT 1 ELSE SELECT 0", targetTable);
            Integer exists = jdbcTemplate.queryForObject(checkTableSql, Integer.class);

            if (exists != null && exists == 1) {
                log.debug("Target table {} exists. Deleting records.", targetTable);
                jdbcTemplate.execute(String.format("TRUNCATE TABLE %s", targetTable));

                // Add iso_id column if it doesn't exist
                String addColumnSql = String.format(
                        "IF NOT EXISTS (SELECT 1 FROM [%s].INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'stage_ref_equipment' AND COLUMN_NAME = 'iso_id') " +
                                "ALTER TABLE %s ADD iso_id NVARCHAR(50)", targetDb, targetTable);
                jdbcTemplate.execute(addColumnSql);

                log.debug("Fetching column names for {}", targetTable);
                String getColumnsSql = String.format(
                    "SELECT COLUMN_NAME FROM [%s].INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = 'stage_ref_equipment' ORDER BY ORDINAL_POSITION",
                    targetDb, targetSchema
                );
                List<String> columns = jdbcTemplate.queryForList(getColumnsSql, String.class);
                String columnList = columns.stream().map(c -> "[" + c + "]").collect(Collectors.joining(", "));

                log.debug("Checking for identity column on {}", targetTable);
                String checkIdentitySql = String.format(
                    "SELECT 1 FROM [%s].sys.columns WHERE object_id = OBJECT_ID('%s') AND is_identity = 1",
                    targetDb, targetTable
                );
                List<Integer> identityExists = jdbcTemplate.queryForList(checkIdentitySql, Integer.class);
                boolean hasIdentity = !identityExists.isEmpty();

                log.debug("Copying records from {} to {}", sourceTable, targetTable);
                if (hasIdentity) {
                    jdbcTemplate.execute(String.format("SET IDENTITY_INSERT %s ON", targetTable));
                }

                // Re-build column list for source to match target table structure including iso_id
                String targetColumnList = columnList;
                String sourceColumnList = columns.stream().map(c -> {
                    if ("iso_id".equalsIgnoreCase(c)) {
                        return "ret.id";
                    } else {
                        return "re.[" + c + "]";
                    }
                }).collect(Collectors.joining(", "));

                String insertSql = String.format("INSERT INTO %s (%s) SELECT %s FROM %s re LEFT JOIN [%s].[%s].[ref_equip_type] ret ON re.eqtyp_gkey = ret.gkey",
                        targetTable, targetColumnList, sourceColumnList, sourceTable, sourceDb, sourceSchema);
                jdbcTemplate.execute(insertSql);

                if (hasIdentity) {
                    jdbcTemplate.execute(String.format("SET IDENTITY_INSERT %s OFF", targetTable));
                }
            } else {
                log.debug("Target table {} does not exist. Creating and copying records.", targetTable);
                String createTableSql = String.format(
                        "SELECT re.*, ret.id AS iso_id INTO %s FROM %s re LEFT JOIN [%s].[%s].[ref_equip_type] ret ON re.eqtyp_gkey = ret.gkey",
                        targetTable, sourceTable, sourceDb, sourceSchema);
                jdbcTemplate.execute(createTableSql);
            }
            log.info("Successfully copied ref_equipment table.");
            batchStatusService.updateStatus(processId, "PROCESSING", 30, "Ref_equipment table copied successfully.");

        } catch (Exception e) {
            log.error("Error copying ref_equipment table", e);
            batchStatusService.updateStatus(processId, "FAILED", 0, "Failed to copy ref_equipment: " + e.getMessage());
            throw new RuntimeException("Failed to copy ref_equipment table: " + e.getMessage(), e);
        }
    }
}
