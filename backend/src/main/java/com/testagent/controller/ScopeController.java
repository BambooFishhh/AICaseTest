package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.service.ProjectAccessService;
import com.testagent.service.ScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v8.1: 本期范围（Scope）——识别/确认/条目管理。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/scope")
@CrossOrigin
public class ScopeController {

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @GetMapping
    public ApiResponse<Object> list(@PathVariable String projectId) {
        return ApiResponse.success(scopeService.listDefinitions(projectId));
    }

    @PostMapping
    public ApiResponse<Object> create(@PathVariable String projectId,
                                      @RequestBody Map<String, String> body) {
        return ApiResponse.success(scopeService.createDraft(projectId,
                body.get("name"), body.get("baselineRef")));
    }

    @GetMapping("/git-refs")
    public ApiResponse<Object> gitRefs(@PathVariable String projectId) {
        return ApiResponse.success(scopeService.listGitRefs(projectId));
    }

    @GetMapping("/{definitionId}/items")
    public ApiResponse<Object> items(@PathVariable String projectId,
                                     @PathVariable String definitionId) {
        return ApiResponse.success(scopeService.listItems(projectId, definitionId));
    }

    @PostMapping("/{definitionId}/items")
    public ApiResponse<Object> addItem(@PathVariable String projectId,
                                       @PathVariable String definitionId,
                                       @RequestBody Map<String, String> body) {
        return ApiResponse.success(scopeService.addItem(projectId, definitionId,
                body.get("itemType"), body.get("itemRef"), body.get("changeKind"), body.get("note")));
    }

    @DeleteMapping("/{definitionId}/items/{itemId}")
    public ApiResponse<Object> removeItem(@PathVariable String projectId,
                                          @PathVariable String definitionId,
                                          @PathVariable String itemId) {
        scopeService.removeItem(projectId, definitionId, itemId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{definitionId}/recompute")
    public ApiResponse<Object> recompute(@PathVariable String projectId,
                                         @PathVariable String definitionId) {
        return ApiResponse.success(scopeService.recompute(projectId, definitionId));
    }

    @PostMapping("/{definitionId}/confirm")
    public ApiResponse<Object> confirm(@PathVariable String projectId,
                                       @PathVariable String definitionId) {
        scopeService.confirm(projectId, definitionId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{definitionId}")
    public ApiResponse<Object> deleteDefinition(@PathVariable String projectId,
                                                @PathVariable String definitionId) {
        scopeService.deleteDefinition(projectId, definitionId);
        return ApiResponse.success(null);
    }
}
