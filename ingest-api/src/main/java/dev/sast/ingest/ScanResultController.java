package dev.sast.ingest;

import dev.sast.contracts.ScanResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/scan-results")
public class ScanResultController {

    // TODO step 3:
    //  - validate the body (400 if repoId / buildId / scannedAt missing)
    //  - send to topic "scan-results" with key = repoId
    //  - return 202 {"status":"accepted"}; never touch Postgres here
    //  - decide: wait for the Kafka ack before replying 202, or not? (be ready to defend it)
    @PostMapping
    public ResponseEntity<Map<String, String>> accept(@RequestBody ScanResult scanResult) {
        throw new UnsupportedOperationException("TODO");
    }
}
