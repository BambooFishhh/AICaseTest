package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.common.BusinessException;
import com.testagent.dto.AnalysisResultDTO;
import com.testagent.dto.StateMachineDTO;
import com.testagent.entity.CodeAnalysis;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.StateMachineRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projects/{projectId}")
@CrossOrigin
public class AnalysisController {

    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @GetMapping("/analysis")
    public ApiResponse<AnalysisResultDTO> getAnalysis(@PathVariable String projectId) {
        CodeAnalysis analysis = codeAnalysisRepository.findByProjectId(projectId)
                .orElseThrow(() -> BusinessException.notFound("分析结果不存在"));
        return ApiResponse.success(AnalysisResultDTO.from(analysis));
    }

    @GetMapping("/state-machines")
    public ApiResponse<List<StateMachineDTO>> getStateMachines(@PathVariable String projectId) {
        List<StateMachineDTO> dtos = stateMachineRepository.findByProjectId(projectId).stream()
                .map(StateMachineDTO::from)
                .collect(Collectors.toList());
        return ApiResponse.success(dtos);
    }
}
