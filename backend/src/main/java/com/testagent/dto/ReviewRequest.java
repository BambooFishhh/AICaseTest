package com.testagent.dto;

import lombok.Data;

import java.util.List;

// v1.8: 批量改评审状态请求体
@Data
public class ReviewRequest {
    private List<String> ids;
    private String status;      // draft/reviewed/approved/rejected
    private String reviewer;    // 可选
}
