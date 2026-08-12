package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.MindMapDTO;
import com.testagent.dto.MindMapPreviewNode;
import com.testagent.entity.MindMap;
import com.testagent.entity.Project;
import com.testagent.entity.TestCase;
import com.testagent.repository.MindMapRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

@Service
public class MindMapService {

    private static final Logger log = LoggerFactory.getLogger(MindMapService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MindMapRepository mindMapRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private XmindService xmindService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectAccessService projectAccessService;

    /**
     * v3.9fix: 启动时清理重复的 MindMap 记录（每个项目只保留最新一条）。
     */
    @PostConstruct
    public void cleanupDuplicateMindMaps() {
        List<String> projectIds = projectRepository.findAll().stream()
                .map(Project::getId)
                .collect(Collectors.toList());
        int cleaned = 0;
        for (String pid : projectIds) {
            List<MindMap> all = mindMapRepository.findAllByProjectId(pid);
            if (all.size() > 1) {
                all.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
                for (int i = 1; i < all.size(); i++) {
                    mindMapRepository.delete(all.get(i));
                    cleaned++;
                }
            }
        }
        if (cleaned > 0) {
            log.info("Cleanup: removed {} duplicate MindMap records", cleaned);
        }
    }

    public MindMapDTO generateMindMap(String projectId, List<String> testcaseIds) {
        projectAccessService.assertProjectAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));

        List<TestCase> allTestCases = testCaseRepository.findByProjectId(projectId);
        if (allTestCases.isEmpty()) {
            throw BusinessException.invalidParam("项目中没有测试用例，请先生成测试用例");
        }

        // v1.4: 按 testcaseIds 过滤
        List<TestCase> testCases = allTestCases;
        if (testcaseIds != null && !testcaseIds.isEmpty()) {
            testCases = allTestCases.stream()
                    .filter(tc -> testcaseIds.contains(tc.getId()))
                    .collect(Collectors.toList());
            if (testCases.isEmpty()) {
                throw BusinessException.invalidParam("选中的用例不存在");
            }
        }

        String filePath;
        try {
            filePath = xmindService.generateXmind(testCases, project.getName());
        } catch (IOException e) {
            log.error("Failed to generate xmind file for project {}", projectId, e);
            throw new BusinessException(50003, "脑图生成失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalTestCases", testCases.size());
        Set<String> modules = testCases.stream()
                .map(TestCase::getModule)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.toSet());
        statistics.put("moduleCount", modules.size());

        MindMap mindMap = new MindMap();
        mindMap.setId(UUID.randomUUID().toString().substring(0, 8));
        mindMap.setProjectId(projectId);
        mindMap.setTitle(project.getName() + " 测试用例脑图");
        mindMap.setFilePath(filePath);
        mindMap.setStatistics(toJson(statistics));
        mindMap.setStatus("completed");
        mindMap.setCreatedAt(LocalDateTime.now());
        mindMapRepository.save(mindMap);

        return toDTO(mindMap);
    }

    public ResponseEntity<Resource> downloadMindMap(String projectId) throws IOException {
        projectAccessService.assertProjectAccess(projectId);
        MindMap mindMap = mindMapRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElseThrow(() -> BusinessException.notFound("脑图不存在，请先生成"));

        File file = new File(mindMap.getFilePath());
        if (!file.exists()) {
            throw BusinessException.notFound("脑图文件不存在: " + mindMap.getFilePath());
        }

        InputStreamResource resource = new InputStreamResource(new FileInputStream(file));
        String fileName = file.getName();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.length())
                .body(resource);
    }

    public MindMapPreviewNode previewMindMap(String projectId) {
        projectAccessService.assertProjectAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));

        List<TestCase> testCases = testCaseRepository.findByProjectId(projectId);
        return xmindService.buildPreviewTree(testCases, project.getName());
    }

    private MindMapDTO toDTO(MindMap mindMap) {
        return MindMapDTO.builder()
                .title(mindMap.getTitle())
                .filePath(mindMap.getFilePath())
                .statistics(JsonHelper.parseMap(mindMap.getStatistics()))
                .status(mindMap.getStatus())
                .createdAt(mindMap.getCreatedAt())
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
