package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.dto.ProjectDTO;
import com.testagent.dto.TestCaseDTO;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.Project;
import com.testagent.entity.TestCase;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.TestCaseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v3.16: 项目导出备份——打包 PRD、用例、覆盖率、执行记录为 ZIP。
 */
@Service
public class BackupService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ExecutionRecordRepository executionRecordRepository;

    @Autowired
    private CoverageService coverageService;

    @Autowired
    private ProjectAccessService projectAccessService;

    public byte[] buildProjectBackup(String projectId) throws IOException {
        projectAccessService.assertProjectAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        List<TestCase> cases = testCaseRepository.findByProjectId(projectId);
        List<ExecutionRecord> executions =
                executionRecordRepository.findByProjectIdOrderByStartTimeDesc(projectId);
        Map<String, Object> coverage;
        try {
            coverage = coverageService.getCoverageMatrix(projectId);
        } catch (Exception e) {
            coverage = Map.of();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putEntry(zos, "project.json",
                    objectMapper.writeValueAsString(ProjectDTO.from(project)));
            putEntry(zos, "prd.md",
                    project.getPrdContent() == null ? "" : project.getPrdContent());
            putEntry(zos, "testcases.json",
                    objectMapper.writeValueAsString(cases.stream().map(TestCaseDTO::from).toList()));
            putEntry(zos, "coverage.json", objectMapper.writeValueAsString(coverage));
            putEntry(zos, "executions.json", objectMapper.writeValueAsString(executions));
        }
        return baos.toByteArray();
    }

    private void putEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
