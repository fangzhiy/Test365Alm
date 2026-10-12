package com.test365alm.server.platform.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlatformHealthController {
    private final ReadinessChecker readinessProbe;

    public PlatformHealthController(ReadinessChecker readinessProbe) {
        this.readinessProbe = readinessProbe;
    }

    @GetMapping("/health/live")
    public LivenessResponse live() {
        return new LivenessResponse("UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<ReadinessResponse> ready() {
        ReadinessResult result = readinessProbe.check();
        ReadinessResponse response = new ReadinessResponse(
                result.ready() ? "UP" : "DOWN", result.database(), result.migration());
        return ResponseEntity.status(result.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    public record LivenessResponse(String status) {
    }

    public record ReadinessResponse(String status, String database, String migration) {
    }
}
