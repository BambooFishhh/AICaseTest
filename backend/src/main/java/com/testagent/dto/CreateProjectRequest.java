package com.testagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateProjectRequest {

    @NotBlank
    private String name;

    private String sourceType = "local_path";

    @NotBlank
    private String sourcePath;
}
