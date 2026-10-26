package dev.sast.consumer;

import dev.sast.contracts.ScanResult;
import org.springframework.stereotype.Component;

@Component
public class ScanResultListener {

    // TODO step 3:
    //  - @KafkaListener(topics = "scan-results", concurrency = "2")
    //  - in ONE transaction: insert scan_runs + findings with ON CONFLICT DO NOTHING
    //  - after commit: delete Redis key "latest:{repoId}"
    //  - offset is committed after this method returns: what happens if you crash just before? (experiment E1)
    public void onScanResult(ScanResult scanResult) {
        throw new UnsupportedOperationException("TODO");
    }
}
