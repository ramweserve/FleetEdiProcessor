package com.weserve.fleetex.controller;

import com.weserve.fleetex.service.BatchStatusService;
import com.weserve.fleetex.service.CopyEquipmentService;
import com.weserve.fleetex.service.FleetEdiLoaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/batch")
public class BatchJobController {

    private static final Logger log = LoggerFactory.getLogger(BatchJobController.class);
    private final CopyEquipmentService copyEquipmentService;
    private final FleetEdiLoaderService fleetEdiLoaderService;
    private final BatchStatusService batchStatusService;

    public BatchJobController(CopyEquipmentService copyEquipmentService, FleetEdiLoaderService fleetEdiLoaderService, BatchStatusService batchStatusService) {
        this.copyEquipmentService = copyEquipmentService;
        this.fleetEdiLoaderService = fleetEdiLoaderService;
        this.batchStatusService = batchStatusService;
    }

    /*@PostMapping("/load")
    public ResponseEntity<String> loadFleetEdi(@RequestParam String filePath, @RequestParam(required = false) String fieldOrder) {
        String processId = UUID.randomUUID().toString();
        try {
            log.debug("loadFleetEdi - "+filePath);
            batchStatusService.updateStatus(processId, "STARTING", 0, "Starting manual load...");
            fleetEdiLoaderService.loadEdiFile(filePath, processId, fieldOrder);
            return ResponseEntity.ok(processId);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }*/

    @PostMapping("/upload")
    public ResponseEntity<String> uploadAndLoadFleetEdi(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "fieldOrder", required = false) String fieldOrder) {
        String processId = UUID.randomUUID().toString();
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("Please upload a file");
            }
            
            batchStatusService.updateStatus(processId, "STARTING", 0, "Uploading file...");

            // Create a temporary directory if it doesn't exist
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "fleetex_uploads");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }

            // Save the file
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
            Path filePath = tempDir.resolve(fileName);
            Files.copy(file.getInputStream(), filePath);

            log.debug("File uploaded to: " + filePath.toAbsolutePath().toString());
            batchStatusService.updateStatus(processId, "PROCESSING", 5, "File uploaded. Starting processing...");

            // Run copy and batch job asynchronously to avoid blocking the HTTP thread
            new Thread(() -> {
                try {
                    // Copy ref_equipment table
                    copyEquipmentService.copyRefEquipmentTable(processId);

                    // Trigger the batch job via service
                    fleetEdiLoaderService.loadEdiFile(filePath.toAbsolutePath().toString(), processId, fieldOrder);

                } catch (Exception e) {
                    log.error("Error during asynchronous copy or batch processing", e);
                    batchStatusService.updateStatus(processId, "FAILED", 0, "Processing error: " + e.getMessage());
                }
            }).start();

            return ResponseEntity.ok(processId);
        } catch (Exception e) {
            log.error("Error during file upload and batch job invocation", e);
            batchStatusService.updateStatus(processId, "FAILED", 0, "Upload error: " + e.getMessage());
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/status/{processId}")
    public ResponseEntity<BatchStatusService.StatusInfo> getStatus(@PathVariable String processId) {
        BatchStatusService.StatusInfo status = batchStatusService.getStatus(processId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/test")
    public ResponseEntity<String> testEndpoint() {
        log.debug("testEndpoint accessed!");
        return ResponseEntity.ok("Batch service is up and running!");
    }
}
