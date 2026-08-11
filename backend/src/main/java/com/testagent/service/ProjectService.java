package com.testagent.service;

import com.testagent.agent.PrdAgent;
import com.testagent.common.BusinessException;
import com.testagent.dto.CreateProjectRequest;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.ProjectDTO;
import com.testagent.entity.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.MindMapRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private MindMapRepository mindMapRepository;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private TestCaseService testCaseService;

    // v1.10: PRD 解析 Agent
    @Autowired
    private PrdAgent prdAgent;

    public List<ProjectDTO> listProjects() {
        return projectRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(ProjectDTO::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProjectDTO createProject(CreateProjectRequest req) {
        // v3.0: sourcePath 为空时跳过路径校验（纯 PRD 驱动项目）
        if (req.getSourcePath() != null && !req.getSourcePath().isBlank()) {
            File path = new File(req.getSourcePath());
            if (!path.exists()) {
                throw BusinessException.pathNotFound("源码路径不存在: " + req.getSourcePath());
            }
        }

        Project project = new Project();
        project.setId(UUID.randomUUID().toString().substring(0, 8));
        project.setName(req.getName());
        project.setSourceType(req.getSourceType() != null ? req.getSourceType() : "local_path");
        project.setSourcePath(req.getSourcePath());
        project.setStatus("created");
        project.setTechStack("{}");
        project.setSettings("{}");
        projectRepository.save(project);
        return ProjectDTO.from(project);
    }

    public ProjectDTO getProject(String id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + id));
        return ProjectDTO.from(project);
    }

    @Transactional
    public void deleteProject(String id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + id));

        codeAnalysisRepository.findAllByProjectId(id).forEach(codeAnalysisRepository::delete);
        stateMachineRepository.deleteByProjectId(id);
        testCaseRepository.deleteByProjectId(id);
        mindMapRepository.findAllByProjectId(id).forEach(mindMapRepository::delete);
        projectRepository.delete(project);
    }

    @Transactional
    public void triggerAnalysis(String id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + id));
        String status = project.getStatus();
        if (!"created".equals(status) && !"failed".equals(status)) {
            throw BusinessException.invalidState("项目当前状态不允许启动分析: " + status);
        }
        projectRepository.updateStatus(id, "analyzing");
        analysisService.runAnalysis(id, project.getSourcePath());
    }

    @Transactional
    public void triggerGenerate(String id, GenerateRequest req) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + id));
        String status = project.getStatus();
        // v1.6: 针对 generating 给出明确的并发提示，避免用户重复触发
        if (!"analyzed".equals(status) && !"completed".equals(status)) {
            if ("generating".equals(status)) {
                throw BusinessException.invalidState("正在生成中，请等待当前任务完成");
            }
            throw BusinessException.invalidState("项目当前状态不允许生成测试用例: " + status);
        }
        projectRepository.updateStatus(id, "generating");
        testCaseService.runGenerate(id, req);
    }

    // ==================== v1.10: PRD 驱动相关 ====================

    public Map<String, Object> getPrd(String projectId) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("prdContent", p.getPrdContent());
        r.put("prdSourceType", p.getPrdSourceType());
        r.put("prdSourceRef", p.getPrdSourceRef());
        return r;
    }

    @Transactional
    public ProjectDTO updatePrd(String projectId, String prdContent) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        p.setPrdContent(prdContent);
        p.setPrdSourceType("text");
        p.setPrdSourceRef(null);
        projectRepository.save(p);
        return ProjectDTO.from(p);
    }

    @Transactional
    public ProjectDTO uploadPrdPdf(String projectId, MultipartFile file) {
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        String text;
        try {
            text = prdAgent.parsePdf(file);
        } catch (Exception e) {
            throw BusinessException.invalidParam("PDF 解析失败: " + e.getMessage());
        }
        p.setPrdContent(text);
        p.setPrdSourceType("pdf");
        p.setPrdSourceRef(file.getOriginalFilename());
        projectRepository.save(p);
        return ProjectDTO.from(p);
    }

    @Transactional
    public ProjectDTO fetchPrdUrl(String projectId, String url) {
        if (url == null || url.isBlank()) {
            throw BusinessException.invalidParam("url 不能为空");
        }
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        String text;
        try {
            text = prdAgent.fetchUrl(url);
        } catch (Exception e) {
            throw BusinessException.invalidParam("URL 抓取失败: " + e.getMessage());
        }
        if (text == null || text.isBlank()) {
            throw BusinessException.invalidParam("URL 内容为空（可能是 SPA 或需认证）");
        }
        p.setPrdContent(text);
        p.setPrdSourceType("link");
        p.setPrdSourceRef(url);
        projectRepository.save(p);
        return ProjectDTO.from(p);
    }

    // ==================== v3.4: 生成参数 ====================

    // v3.4: 获取生成参数（从 Project.settings JSON 解析，失败降级默认值）
    public GenerationParams getGenerationParams(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        return parseGenerationParams(project.getSettings());
    }

    // v3.4: 更新生成参数（写入 Project.settings JSON 的 generationParams 字段）
    @Transactional
    public GenerationParams updateGenerationParams(String projectId, GenerationParams params) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        try {
            ObjectNode settings = (ObjectNode) objectMapper.readTree(
                    project.getSettings() != null ? project.getSettings() : "{}");
            settings.set("generationParams", objectMapper.valueToTree(params));
            project.setSettings(objectMapper.writeValueAsString(settings));
            projectRepository.save(project);
            return params;
        } catch (Exception e) {
            throw new BusinessException(50005, "保存生成参数失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // v3.4: 从 settings JSON 解析生成参数，失败/空降级默认值
    GenerationParams parseGenerationParams(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank() || "{}".equals(settingsJson)) {
            return GenerationParams.defaults();
        }
        try {
            JsonNode settings = objectMapper.readTree(settingsJson);
            JsonNode gpNode = settings.path("generationParams");
            if (gpNode.isMissingNode() || gpNode.isNull()) {
                return GenerationParams.defaults();
            }
            GenerationParams params = objectMapper.treeToValue(gpNode, GenerationParams.class);
            if (params.getCaseDensity() == null) params.setCaseDensity("medium");
            if (params.getTemperature() == null) params.setTemperature(0.4);
            if (params.getFocusTypes() == null) params.setFocusTypes(List.of());
            return params;
        } catch (Exception e) {
            log.warn("Failed to parse generation params, using defaults", e);
            return GenerationParams.defaults();
        }
    }
}
