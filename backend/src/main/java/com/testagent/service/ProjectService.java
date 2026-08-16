package com.testagent.service;

import com.testagent.agent.PrdAgent;
import com.testagent.common.BusinessException;
import com.testagent.common.UploadGuard;
import com.testagent.dto.CreateProjectRequest;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.ProjectDTO;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.Project;
import com.testagent.entity.ProjectGroup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import com.testagent.repository.GroupMemberRepository;
import com.testagent.repository.MindMapRepository;
import com.testagent.repository.ProjectGroupRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseAiReviewRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.TestCaseVersionRepository;
import com.testagent.repository.TaskTelemetryRepository;
import com.testagent.repository.TestSuiteRepository;
import com.testagent.security.SecurityUtils;
import com.testagent.security.AccessLevel;
import com.testagent.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private SettingsService settingsService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private ProjectGroupRepository projectGroupRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

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

    @Autowired
    private ExecutionRecordRepository executionRecordRepository;

    @Autowired
    private ExecutionStepRepository executionStepRepository;

    @Autowired
    private TestSuiteRepository testSuiteRepository;

    @Autowired
    private TestCaseVersionRepository testCaseVersionRepository;

    @Autowired
    private TestCaseAiReviewRepository aiReviewRepository;

    @Autowired
    private TaskTelemetryRepository telemetryRepository;

    // v1.10: PRD 解析 Agent
    @Autowired
    private PrdAgent prdAgent;

    @Autowired
    private UploadGuard uploadGuard;

    @Autowired
    private SemanticService semanticService;

    public List<ProjectDTO> listProjects() {
        List<Project> all = projectRepository.findAllByOrderByCreatedAtDesc();
        return all.stream()
                // v4.3: 仅返回有访问权限的项目（创建者/组成员/管理员）
                .filter(p -> projectAccessService.getAccessLevel(p.getId()) != AccessLevel.NONE)
                .map(p -> {
                    ProjectDTO dto = ProjectDTO.from(p);
                    dto.setAccessLevel(projectAccessService.getAccessLevel(p.getId()).name());
                    return dto;
                })
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
        project.setUserId(projectAccessService.requireCurrentUserId());
        // v4.3: 归属项目组（可选，需属于该组）
        if (req.getGroupId() != null && !req.getGroupId().isBlank()) {
            ProjectGroup group = projectGroupRepository.findById(req.getGroupId())
                    .orElseThrow(() -> BusinessException.notFound("项目组不存在: " + req.getGroupId()));
            String uid = projectAccessService.requireCurrentUserId();
            boolean inGroup = group.getOwnerId().equals(uid)
                    || groupMemberRepository.findByGroupIdAndUserId(group.getId(), uid).isPresent();
            if (!inGroup) {
                throw new BusinessException(40306, "不属于该项目组，无法创建组内项目", HttpStatus.FORBIDDEN);
            }
            project.setGroupId(group.getId());
        }
        project.setTechStack("{}");
        project.setSettings("{}");
        // v3.17: 新建项目初始化系统级默认生成参数
        try {
            GenerationParams defaults = settingsService.getDefaultGenerationParams();
            ObjectNode settings = (ObjectNode) objectMapper.readTree("{}");
            settings.set("generationParams", objectMapper.valueToTree(defaults));
            if (req.getExecutionCookies() != null && !req.getExecutionCookies().isEmpty()) {
                settings.set("executionCookies", objectMapper.valueToTree(req.getExecutionCookies()));
            }
            project.setSettings(objectMapper.writeValueAsString(settings));
        } catch (Exception ignored) {
            // 初始化失败保持空 settings
        }
        projectRepository.save(project);
        return ProjectDTO.from(project);
    }

    public ProjectDTO getProject(String id) {
        projectAccessService.assertViewAccess(id);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + id));
        ProjectDTO dto = ProjectDTO.from(project);
        dto.setAccessLevel(projectAccessService.getAccessLevel(id).name());
        return dto;
    }

    @Transactional
    public void deleteProject(String id) {
        projectAccessService.assertOperateAccess(id);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + id));

        codeAnalysisRepository.findAllByProjectId(id).forEach(codeAnalysisRepository::delete);
        stateMachineRepository.deleteByProjectId(id);
        testCaseRepository.deleteByProjectId(id);
        // v5.6: 级联清理执行记录/步骤/测试集/用例版本
        List<ExecutionRecord> executions = executionRecordRepository.findByProjectIdOrderByStartTimeDesc(id);
        List<String> executionIds = executions.stream().map(ExecutionRecord::getId).collect(Collectors.toList());
        if (!executionIds.isEmpty()) {
            executionStepRepository.deleteAll(executionStepRepository.findByExecutionIdIn(executionIds));
        }
        executionRecordRepository.deleteAll(executions);
        testSuiteRepository.deleteAll(testSuiteRepository.findByProjectIdOrderByCreatedAtDesc(id));
        testCaseVersionRepository.deleteByProjectId(id);
        // v5.12: AI 评审历史随项目级联清理
        aiReviewRepository.deleteByProjectId(id);
        // v5.14: 任务埋点随项目级联清理
        telemetryRepository.deleteByProjectId(id);
        mindMapRepository.findAllByProjectId(id).forEach(mindMapRepository::delete);
        // v5.6: 清理 Milvus 三集合
        semanticService.clearProject(id);
        projectRepository.delete(project);
    }

    @Transactional
    public void triggerAnalysis(String id) {
        projectAccessService.assertOperateAccess(id);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + id));
        String status = project.getStatus();
        if ("analyzing".equals(status)) {
            throw BusinessException.invalidState("正在分析中，请等待当前任务完成");
        }
        if ("generating".equals(status)) {
            throw BusinessException.invalidState("项目正在生成用例，请稍后再启动分析");
        }
        // v5.13: 允许重复分析，created/failed/analyzed/completed 都可重新触发
        if (!"created".equals(status) && !"failed".equals(status)
                && !"analyzed".equals(status) && !"completed".equals(status)) {
            throw BusinessException.invalidState("项目当前状态不允许启动分析: " + status);
        }
        projectRepository.updateStatus(id, "analyzing");
        analysisService.runAnalysis(id, project.getSourcePath());
    }

    @Transactional
    public void triggerGenerate(String id, GenerateRequest req) {
        projectAccessService.assertOperateAccess(id);
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
        projectAccessService.assertViewAccess(projectId);
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
        projectAccessService.assertOperateAccess(projectId);
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        p.setPrdContent(prdContent);
        p.setPrdSourceType("text");
        p.setPrdSourceRef(null);
        projectRepository.save(p);
        // v5.4: PRD 写入语义上下文库
        semanticService.replaceContext(projectId, "prd", p.getPrdContent());
        return ProjectDTO.from(p);
    }

    @Transactional
    public ProjectDTO uploadPrdPdf(String projectId, MultipartFile file) {
        projectAccessService.assertOperateAccess(projectId);
        uploadGuard.assertSize(file, "PRD PDF");
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
        semanticService.replaceContext(projectId, "prd", p.getPrdContent());
        return ProjectDTO.from(p);
    }

    @Transactional
    public ProjectDTO fetchPrdUrl(String projectId, String url) {
        projectAccessService.assertOperateAccess(projectId);
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
        semanticService.replaceContext(projectId, "prd", p.getPrdContent());
        return ProjectDTO.from(p);
    }

    // ==================== v5.9: 执行 Cookie 与项目上下文 ====================

    public List<Map<String, Object>> getExecutionCookies(String projectId) {
        projectAccessService.assertViewAccess(projectId);
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        return parseSettingsArray(p.getSettings(), "executionCookies");
    }

    @Transactional
    public List<Map<String, Object>> updateExecutionCookies(String projectId, List<Map<String, Object>> cookies) {
        projectAccessService.assertOperateAccess(projectId);
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        try {
            ObjectNode settings = (ObjectNode) objectMapper.readTree(
                    p.getSettings() != null ? p.getSettings() : "{}");
            settings.set("executionCookies", objectMapper.valueToTree(cookies == null ? List.of() : cookies));
            p.setSettings(objectMapper.writeValueAsString(settings));
            projectRepository.save(p);
            return cookies == null ? List.of() : cookies;
        } catch (Exception e) {
            throw new BusinessException(50011, "保存执行 Cookie 失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public Map<String, Object> getProjectContext(String projectId) {
        projectAccessService.assertViewAccess(projectId);
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        String otherContextInfo = readSettingsField(p.getSettings(), "otherContextInfo");
        if (otherContextInfo.isBlank()) {
            otherContextInfo = readSettingsField(p.getSettings(), "extraPrompt");
        }
        List<Map<String, Object>> reqDocs = loadReqDocs(p);
        String joinedPrd = joinPrdDocs(reqDocs, p.getPrdContent());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("prdContent", joinedPrd);
        r.put("prdSourceType", p.getPrdSourceType());
        r.put("prdSourceRef", p.getPrdSourceRef());
        r.put("otherContextInfo", otherContextInfo);
        r.put("extraPrompt", otherContextInfo); // 兼容旧客户端
        r.put("reqDocs", reqDocs);
        r.put("contextDocs", reqDocs.stream()
                .filter(d -> !"prd".equals(d.get("docType")))
                .collect(Collectors.toList()));
        return r;
    }

    @Transactional
    public Map<String, Object> updateProjectContext(String projectId, Map<String, Object> payload) {
        projectAccessService.assertOperateAccess(projectId);
        Project p = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        try {
            ObjectNode settings = (ObjectNode) objectMapper.readTree(
                    p.getSettings() != null ? p.getSettings() : "{}");
            Object otherRaw = payload == null ? null : payload.get("otherContextInfo");
            if (!(otherRaw instanceof String) && payload != null) {
                otherRaw = payload.get("supplementaryRequirements");
            }
            if (!(otherRaw instanceof String) && payload != null) {
                otherRaw = payload.get("extraPrompt");
            }
            if (otherRaw instanceof String otherContextInfo) {
                settings.put("otherContextInfo", otherContextInfo);
            }
            Object reqDocsRaw = payload == null ? null : payload.get("reqDocs");
            if (reqDocsRaw instanceof List<?>) {
                List<Map<String, Object>> normalized = normalizeReqDocs((List<?>) reqDocsRaw);
                settings.set("reqDocs", objectMapper.valueToTree(normalized));
                List<Map<String, Object>> ctxDocs = normalized.stream()
                        .filter(d -> !"prd".equals(d.get("docType")))
                        .collect(Collectors.toList());
                settings.set("contextDocs", objectMapper.valueToTree(ctxDocs));
                String joinedPrd = joinPrdDocs(normalized, null);
                p.setPrdContent(joinedPrd == null ? null : joinedPrd);
                p.setPrdSourceType("multi");
                p.setPrdSourceRef("多篇需求文档");
                semanticService.replaceContext(projectId, "prd", joinedPrd == null ? "" : joinedPrd);
            } else {
                Object contextDocs = payload == null ? null : payload.get("contextDocs");
                if (contextDocs != null) {
                    settings.set("contextDocs", objectMapper.valueToTree(contextDocs));
                }
            }
            p.setSettings(objectMapper.writeValueAsString(settings));
            projectRepository.save(p);
            Map<String, Object> r = new LinkedHashMap<>();
            String saved = readSettingsField(p.getSettings(), "otherContextInfo");
            if (saved.isBlank()) {
                saved = readSettingsField(p.getSettings(), "extraPrompt");
            }
            r.put("otherContextInfo", saved);
            r.put("extraPrompt", saved);
            List<Map<String, Object>> savedDocs = loadReqDocs(p);
            r.put("reqDocs", savedDocs);
            r.put("contextDocs", savedDocs.stream()
                    .filter(d -> !"prd".equals(d.get("docType")))
                    .collect(Collectors.toList()));
            return r;
        } catch (Exception e) {
            throw new BusinessException(50012, "保存项目上下文失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<Map<String, Object>> loadReqDocs(Project p) {
        List<Map<String, Object>> reqDocs = parseSettingsArray(p.getSettings(), "reqDocs");
        if (!reqDocs.isEmpty()) {
            return reqDocs;
        }
        if (p.getPrdContent() != null && !p.getPrdContent().isBlank()) {
            Map<String, Object> prdDoc = new LinkedHashMap<>();
            prdDoc.put("id", "prd-legacy");
            prdDoc.put("title", "主 PRD");
            prdDoc.put("content", p.getPrdContent());
            prdDoc.put("sourceType", p.getPrdSourceType() == null ? "text" : p.getPrdSourceType());
            prdDoc.put("sourceRef", p.getPrdSourceRef() == null ? "" : p.getPrdSourceRef());
            prdDoc.put("docType", "prd");
            reqDocs.add(prdDoc);
        }
        for (Map<String, Object> doc : parseSettingsArray(p.getSettings(), "contextDocs")) {
            doc.putIfAbsent("docType", "context");
            reqDocs.add(doc);
        }
        return reqDocs;
    }

    private List<Map<String, Object>> normalizeReqDocs(List<?> raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> doc = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    doc.put(String.valueOf(e.getKey()), e.getValue());
                }
                doc.putIfAbsent("id", "doc-" + UUID.randomUUID());
                doc.putIfAbsent("docType", "context");
                doc.putIfAbsent("sourceType", "text");
                doc.putIfAbsent("sourceRef", "");
                result.add(doc);
            }
        }
        return result;
    }

    private String joinPrdDocs(List<Map<String, Object>> reqDocs, String fallback) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> doc : reqDocs) {
            if ("prd".equals(doc.get("docType")) && doc.get("content") != null) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(doc.get("content"));
            }
        }
        return sb.length() > 0 ? sb.toString() : fallback;
    }

    // v5.10: 上下文文档解析（md/txt/PDF），不直接落库，由前端随整体上下文保存
    public Map<String, Object> parseContextDoc(String projectId, MultipartFile file) {
        projectAccessService.assertOperateAccess(projectId);
        uploadGuard.assertSize(file, "上下文文档");
        String name = file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        String content;
        String sourceType;
        try {
            if (lower.endsWith(".pdf")) {
                content = prdAgent.parsePdf(file);
                sourceType = "pdf";
            } else {
                content = new String(file.getBytes(), StandardCharsets.UTF_8);
                sourceType = "md";
            }
        } catch (Exception e) {
            throw BusinessException.invalidParam("文档解析失败: " + e.getMessage());
        }
        if (content == null || content.isBlank()) {
            throw BusinessException.invalidParam("文档内容为空");
        }
        String title = lower.endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
        int dot = title.lastIndexOf('.');
        if (dot > 0) {
            title = title.substring(0, dot);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "doc-" + UUID.randomUUID());
        doc.put("title", title);
        doc.put("content", content);
        doc.put("sourceType", sourceType);
        doc.put("sourceRef", name);
        return doc;
    }

    // v5.10: 上下文文档在线链接抓取
    public Map<String, Object> fetchContextDoc(String projectId, String url) {
        projectAccessService.assertOperateAccess(projectId);
        if (url == null || url.isBlank()) {
            throw BusinessException.invalidParam("url 不能为空");
        }
        String content;
        try {
            content = prdAgent.fetchUrl(url);
        } catch (Exception e) {
            throw BusinessException.invalidParam("URL 抓取失败: " + e.getMessage());
        }
        if (content == null || content.isBlank()) {
            throw BusinessException.invalidParam("URL 内容为空（可能是 SPA 或需认证）");
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "doc-" + UUID.randomUUID());
        doc.put("title", url);
        doc.put("content", content);
        doc.put("sourceType", "link");
        doc.put("sourceRef", url);
        return doc;
    }

    private String readSettingsField(String settingsJson, String field) {
        try {
            JsonNode settings = objectMapper.readTree(settingsJson != null ? settingsJson : "{}");
            return settings.path(field).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private List<Map<String, Object>> parseSettingsArray(String settingsJson, String field) {
        try {
            JsonNode settings = objectMapper.readTree(settingsJson != null ? settingsJson : "{}");
            JsonNode node = settings.path(field);
            if (node.isArray()) {
                return objectMapper.convertValue(node,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception ignored) {
            // 保持空列表
        }
        return new ArrayList<>();
    }

    // ==================== v3.4: 生成参数 ====================

    // v3.4: 获取生成参数（从 Project.settings JSON 解析，失败降级默认值）
    @Cacheable(value = "projectParams", key = "#projectId")
    public GenerationParams getGenerationParams(String projectId) {
        projectAccessService.assertViewAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        return parseGenerationParams(project.getSettings());
    }

    // v3.4: 更新生成参数（写入 Project.settings JSON 的 generationParams 字段）
    @Transactional
    @CacheEvict(value = "projectParams", key = "#projectId")
    public GenerationParams updateGenerationParams(String projectId, GenerationParams params) {
        projectAccessService.assertOperateAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        try {
            ObjectNode settings = (ObjectNode) objectMapper.readTree(
                    project.getSettings() != null ? project.getSettings() : "{}");
            // v5.13: defaultTargetUrl 属于执行配置，不再由生成参数弹窗提交；
            // 保留历史值，避免保存生成参数时误清掉项目默认执行 URL
            String previousUrl = null;
            JsonNode gpNode = settings.path("generationParams");
            if (gpNode.isObject()) {
                String url = gpNode.path("defaultTargetUrl").asText("");
                if (!url.isBlank()) {
                    previousUrl = url;
                }
            }
            ObjectNode nextParams = (ObjectNode) objectMapper.valueToTree(params);
            if ((params.getDefaultTargetUrl() == null || params.getDefaultTargetUrl().isBlank())
                    && previousUrl != null) {
                nextParams.put("defaultTargetUrl", previousUrl);
            }
            settings.set("generationParams", nextParams);
            project.setSettings(objectMapper.writeValueAsString(settings));
            projectRepository.save(project);
            return params;
        } catch (Exception e) {
            throw new BusinessException(50005, "保存生成参数失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // v3.15: 多执行环境——读取 settings.executionEnvironments
    public Map<String, Object> getExecutionEnvironments(String projectId) {
        projectAccessService.assertViewAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("environments", new ArrayList<>());
        result.put("active", "");
        try {
            JsonNode settings = objectMapper.readTree(
                    project.getSettings() != null ? project.getSettings() : "{}");
            JsonNode envNode = settings.path("executionEnvironments");
            if (envNode.isMissingNode() || envNode.isNull()) {
                return result;
            }
            List<Map<String, Object>> envs = new ArrayList<>();
            JsonNode listNode = envNode.path("environments");
            if (listNode.isArray()) {
                for (JsonNode item : listNode) {
                    Map<String, Object> env = new LinkedHashMap<>();
                    env.put("name", item.path("name").asText(""));
                    env.put("url", item.path("url").asText(""));
                    JsonNode preSteps = item.path("preSteps");
                    env.put("preSteps", preSteps.isArray() ? objectMapper.convertValue(preSteps, List.class) : List.of());
                    envs.add(env);
                }
            }
            result.put("environments", envs);
            result.put("active", envNode.path("active").asText(""));
        } catch (Exception e) {
            log.warn("Failed to parse execution environments, using empty", e);
        }
        return result;
    }

    // v3.15: 更新多执行环境，激活环境 URL 同步到 defaultTargetUrl
    @Transactional
    @CacheEvict(value = "projectParams", key = "#projectId")
    public Map<String, Object> updateExecutionEnvironments(String projectId, Map<String, Object> payload) {
        projectAccessService.assertOperateAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> environments =
                (List<Map<String, Object>>) payload.getOrDefault("environments", List.of());
        String active = (String) payload.getOrDefault("active", "");
        try {
            ObjectNode settings = (ObjectNode) objectMapper.readTree(
                    project.getSettings() != null ? project.getSettings() : "{}");
            ObjectNode envNode = objectMapper.createObjectNode();
            envNode.set("environments", objectMapper.valueToTree(environments));
            envNode.put("active", active == null ? "" : active);
            settings.set("executionEnvironments", envNode);

            // 激活环境 URL 同步到 defaultTargetUrl
            String activeUrl = "";
            for (Map<String, Object> env : environments) {
                if (active != null && active.equals(env.get("name")) && env.get("url") != null) {
                    activeUrl = String.valueOf(env.get("url"));
                }
            }
            JsonNode gpNode = settings.path("generationParams");
            if (gpNode.isObject()) {
                ObjectNode gp = (ObjectNode) gpNode;
                if (!activeUrl.isBlank()) {
                    gp.put("defaultTargetUrl", activeUrl);
                }
            }
            project.setSettings(objectMapper.writeValueAsString(settings));
            projectRepository.save(project);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("environments", environments);
            result.put("active", active == null ? "" : active);
            return result;
        } catch (Exception e) {
            throw new BusinessException(50010, "保存执行环境失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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
            GenerationParams params;
            if (gpNode.isMissingNode() || gpNode.isNull()) {
                params = GenerationParams.defaults();
            } else {
                params = objectMapper.treeToValue(gpNode, GenerationParams.class);
                if (params.getCaseDensity() == null) params.setCaseDensity("medium");
                if (params.getTemperature() == null) params.setTemperature(0.4);
                if (params.getFocusTypes() == null) params.setFocusTypes(List.of());
            }
            // 默认执行 URL 为空时，优先从项目登录 Cookie / 激活环境推导，避免录屏打开错误目标
            if (params.getDefaultTargetUrl() == null || params.getDefaultTargetUrl().isBlank()) {
                params.setDefaultTargetUrl(resolveDefaultTargetUrl(settings));
            }
            return params;
        } catch (Exception e) {
            log.warn("Failed to parse generation params, using defaults", e);
            return GenerationParams.defaults();
        }
    }

    private String resolveDefaultTargetUrl(JsonNode settings) {
        JsonNode cookies = settings.path("executionCookies");
        if (cookies.isArray()) {
            for (JsonNode cookie : cookies) {
                String url = cookie.path("url").asText("");
                if (!url.isBlank()) {
                    return url;
                }
            }
        }
        JsonNode envNode = settings.path("executionEnvironments");
        String active = envNode.path("active").asText("");
        JsonNode envs = envNode.path("environments");
        if (envs.isArray()) {
            for (JsonNode env : envs) {
                if (active.equals(env.path("name").asText("")) && !env.path("url").asText("").isBlank()) {
                    return env.path("url").asText("");
                }
            }
        }
        return null;
    }
}
