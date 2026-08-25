package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.common.BusinessException;
import com.testagent.dto.AnalysisResultDTO;
import com.testagent.dto.StateMachineDTO;
import com.testagent.entity.CodeAnalysis;
import com.testagent.service.AnalysisService;
import com.testagent.service.ProjectAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @GetMapping("/analysis")
    public ApiResponse<AnalysisResultDTO> getAnalysis(@PathVariable String projectId) {
        projectAccessService.assertViewAccess(projectId);
        CodeAnalysis analysis = analysisService.getAnalysis(projectId);
        if (analysis == null) {
            throw BusinessException.notFound("分析结果不存在");
        }
        return ApiResponse.success(AnalysisResultDTO.from(analysis));
    }

    @GetMapping("/state-machines")
    public ApiResponse<List<StateMachineDTO>> getStateMachines(@PathVariable String projectId) {
        projectAccessService.assertViewAccess(projectId);
        List<StateMachineDTO> dtos = analysisService.getStateMachines(projectId).stream()
                .map(StateMachineDTO::from)
                .collect(Collectors.toList());
        return ApiResponse.success(dtos);
    }
}
