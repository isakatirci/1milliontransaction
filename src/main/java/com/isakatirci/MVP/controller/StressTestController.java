package com.isakatirci.MVP.controller;

import com.isakatirci.MVP.service.StressTestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Controller
@RequiredArgsConstructor
@Slf4j
public class StressTestController {

    private final StressTestService stressTestService;
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    @GetMapping("/stress-test")
    public String stressTestPage(Model model) {
        model.addAttribute("status", stressTestService.getStatus());
        return "stress-test";
    }

    @PostMapping("/api/v1/stress-test/run")
    @ResponseBody
    public String runTest(@RequestParam(defaultValue = "10000") int count) {
        stressTestService.runStressTest(count, null);
        return "Started";
    }

    @GetMapping(value = "/api/v1/stress-test/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress() {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout
        
        stressTestService.runStressTest(10000, status -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(status));
                
                if ("COMPLETED".equals(status.getStatus()) || "FAILED".equals(status.getStatus())) {
                    emitter.complete();
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
