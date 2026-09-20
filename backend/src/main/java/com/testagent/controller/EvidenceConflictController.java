package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.common.BusinessException;
import com.testagent.entity.EvidenceConflictRecord;
import com.testagent.repository.EvidenceConflictRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v13.17(证据权威判定): 证据链冲突的查询与人工裁决接口。
 *
 * <p>补齐 C2 机制的断链：冲突此前只注入 prompt，人既看不到清单也无从裁决。
 * 裁决结果写入记录后，同一冲突（projectId + conflictKey）在后续生成时会被自动套用，
 * 不再重复提请人工。
 *
 * <p>命中 SecurityConfig 既有的 /api/** 认证规则（登录用户可用）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/evidence-conflicts")
public class EvidenceConflictController {

    private static final List<String> VALID_VERDICTS = List.of(
            EvidenceConflictRecord.VERDICT_PENDING,
            EvidenceConflictRecord.VERDICT_PRD,
            EvidenceConflictRecord.VERDICT_CODE,
            EvidenceConflictRecord.VERDICT_DEPRECATED);

    @Autowired
    private EvidenceConflictRecordRepository recordRepository;

    /**
     * 冲突清单。
     *
     * @param verdict        按裁决状态过滤（{@code pending} = 待裁决），空则返回全部
     * @param manualRequired v13.19：true 时只返回**真正需要人裁决**的项——
     *                       {@code skip}（无 PRD 依据不生成）/ {@code deferred}（需求变更中暂缓）
     *                       虽然同样处于未裁决状态，但不该占用人的注意力
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(@PathVariable String projectId,
                                                 @RequestParam(required = false) String verdict,
                                                 @RequestParam(required = false) Boolean manualRequired) {
        List<EvidenceConflictRecord> all = recordRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        List<EvidenceConflictRecord> records = new ArrayList<>();
        for (EvidenceConflictRecord record : all) {
            if (verdict != null && !verdict.isBlank() && !verdict.equals(record.getVerdict())) {
                continue;
            }
            if (Boolean.TRUE.equals(manualRequired)
                    && !Boolean.TRUE.equals(record.getManualRequired())) {
                continue;
            }
            records.add(record);
        }
        // v13.19: 待裁决数只统计"未裁决且需要人"的——它应反映真实待办量，
        // 而不是把自动跳过/暂缓的项也算进来虚高
        long pending = all.stream()
                .filter(r -> EvidenceConflictRecord.VERDICT_PENDING.equals(r.getVerdict()))
                .filter(r -> Boolean.TRUE.equals(r.getManualRequired()))
                .count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conflicts", records);
        result.put("total", records.size());
        result.put("pending", pending);
        return ApiResponse.success(result);
    }

    /**
     * 人工裁决。裁决值：prd_authoritative / code_authoritative / deprecated / pending（撤回裁决）。
     */
    @PostMapping("/{conflictKey}/verdict")
    public ApiResponse<EvidenceConflictRecord> verdict(@PathVariable String projectId,
                                                       @PathVariable String conflictKey,
                                                       @RequestBody Map<String, Object> body) {
        String verdict = body.get("verdict") == null ? "" : String.valueOf(body.get("verdict")).trim();
        if (!VALID_VERDICTS.contains(verdict)) {
            throw BusinessException.invalidParam("裁决值不合法: " + verdict);
        }
        String id = EvidenceConflictRecord.buildId(projectId, conflictKey);
        EvidenceConflictRecord record = recordRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("冲突不存在: " + conflictKey));

        record.setVerdict(verdict);
        record.setVerdictNote(body.get("note") == null ? null : String.valueOf(body.get("note")));
        record.setVerdictBy(currentOperator());
        LocalDateTime now = LocalDateTime.now();
        record.setVerdictAt(EvidenceConflictRecord.VERDICT_PENDING.equals(verdict) ? null : now);
        record.setUpdatedAt(now);
        return ApiResponse.success(recordRepository.save(record));
    }

    /**
     * 裁决人取自已认证上下文（JWT principal），**不采信请求体里的 {@code by}**——
     * 审计字段若由客户端提供即可被伪造，失去追溯意义。
     *
     * @return 用户名；未认证 / 匿名 / 无 principal 时回落 {@code unknown}
     */
    private String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "unknown";
        }
        String name = auth.getName();
        return (name == null || name.isBlank()) ? "unknown" : name;
    }
}
