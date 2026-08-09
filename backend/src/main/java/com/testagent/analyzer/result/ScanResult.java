package com.testagent.analyzer.result;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ScanResult {

    private String frontendDir;

    private String backendDir;

    private Map<String, Object> techStack;

    private int fileCount;
}
