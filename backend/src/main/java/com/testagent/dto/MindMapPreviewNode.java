package com.testagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MindMapPreviewNode {

    private String id;

    private String title;

    private List<MindMapPreviewNode> children;
}
