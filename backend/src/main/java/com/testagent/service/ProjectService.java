package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.dto.CreateProjectRequest;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.ProjectDTO;
import com.testagent.entity.Project;
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

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

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

    public List<ProjectDTO> listProjects() {
        return projectRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(ProjectDTO::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProjectDTO createProject(CreateProjectRequest req) {
        File path = new File(req.getSourcePath());
        if (!path.exists()) {
            throw BusinessException.pathNotFound("源码路径不存在: " + req.getSourcePath());
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

        codeAnalysisRepository.findByProjectId(id).ifPresent(codeAnalysisRepository::delete);
        stateMachineRepository.deleteByProjectId(id);
        testCaseRepository.deleteByProjectId(id);
        mindMapRepository.findByProjectId(id).ifPresent(mindMapRepository::delete);
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
        if (!"analyzed".equals(status) && !"completed".equals(status)) {
            throw BusinessException.invalidState("项目当前状态不允许生成测试用例: " + status);
        }
        projectRepository.updateStatus(id, "generating");
        testCaseService.runGenerate(id, req);
    }
}
