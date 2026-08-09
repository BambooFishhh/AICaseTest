package com.testagent.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerateRequest {

    private List<String> modules;

    private List<String> types;
}
