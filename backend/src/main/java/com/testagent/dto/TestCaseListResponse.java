package com.testagent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TestCaseListResponse {

    private long total;

    private int page;

    private int pageSize;

    private List<TestCaseDTO> testCases;
}
