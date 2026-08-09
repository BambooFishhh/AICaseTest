package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.MindMapPreviewNode;
import com.testagent.entity.TestCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class XmindService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.output-dir:./outputs}")
    private String outputDir;

    private static final String[] TYPE_ORDER = {"正向", "异常", "边界", "数据"};
    private static final String DEFAULT_TYPE_GROUP = "其他";

    public String generateXmind(List<TestCase> testCases, String projectName) throws IOException {
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String safeName = sanitizeName(projectName);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String fileName = safeName + "_testcases_" + timestamp + ".xmind";
        File file = new File(dir, fileName);

        Map<String, Object> rootTopic = buildRootTopicMap(testCases, projectName);

        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("id", "sheet-1");
        sheet.put("class", "sheet");
        sheet.put("title", "测试用例");
        sheet.put("rootTopic", rootTopic);
        content.add(sheet);

        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> creator = new LinkedHashMap<>();
        creator.put("name", "System-Aware Test Agent");
        creator.put("version", "1.0.0");
        metadata.put("creator", creator);

        Map<String, Object> manifest = new LinkedHashMap<>();
        Map<String, Object> fileEntries = new LinkedHashMap<>();
        fileEntries.put("content.json", new LinkedHashMap<>());
        fileEntries.put("metadata.json", new LinkedHashMap<>());
        manifest.put("file-entries", fileEntries);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            writeZipEntry(zos, "content.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(content));
            writeZipEntry(zos, "metadata.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
            writeZipEntry(zos, "manifest.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        }

        return file.getAbsolutePath();
    }

    public MindMapPreviewNode buildPreviewTree(List<TestCase> testCases, String projectName) {
        Map<String, List<TestCase>> byModule = groupByModule(testCases);

        List<MindMapPreviewNode> moduleChildren = new ArrayList<>();
        for (Map.Entry<String, List<TestCase>> entry : byModule.entrySet()) {
            moduleChildren.add(buildModulePreviewNode(entry.getKey(), entry.getValue()));
        }

        return new MindMapPreviewNode("root", projectName + " 测试用例", moduleChildren);
    }

    private void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            return "project";
        }
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private Map<String, List<TestCase>> groupByModule(List<TestCase> testCases) {
        Map<String, List<TestCase>> byModule = new LinkedHashMap<>();
        for (TestCase tc : testCases) {
            String module = (tc.getModule() != null && !tc.getModule().isBlank())
                    ? tc.getModule() : "未分类";
            byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(tc);
        }
        return byModule;
    }

    private Map<String, List<TestCase>> groupByType(List<TestCase> testCases) {
        Map<String, List<TestCase>> byType = new LinkedHashMap<>();
        for (String t : TYPE_ORDER) {
            byType.put(t, new ArrayList<>());
        }
        byType.put(DEFAULT_TYPE_GROUP, new ArrayList<>());

        for (TestCase tc : testCases) {
            String group = mapTypeToGroup(tc.getType());
            byType.get(group).add(tc);
        }
        return byType;
    }

    private String mapTypeToGroup(String type) {
        if (type == null) {
            return DEFAULT_TYPE_GROUP;
        }
        return switch (type) {
            case "positive", "正向" -> "正向";
            case "negative", "异常" -> "异常";
            case "boundary", "边界" -> "边界";
            case "data", "数据" -> "数据";
            default -> DEFAULT_TYPE_GROUP;
        };
    }

    private Map<String, Object> buildRootTopicMap(List<TestCase> testCases, String projectName) {
        Map<String, Object> rootTopic = new LinkedHashMap<>();
        rootTopic.put("id", "root");
        rootTopic.put("title", projectName + " 测试用例");

        Map<String, List<TestCase>> byModule = groupByModule(testCases);
        List<Map<String, Object>> moduleChildren = new ArrayList<>();
        for (Map.Entry<String, List<TestCase>> entry : byModule.entrySet()) {
            moduleChildren.add(buildModuleTopicMap(entry.getKey(), entry.getValue()));
        }

        Map<String, Object> children = new LinkedHashMap<>();
        children.put("attached", moduleChildren);
        rootTopic.put("children", children);
        return rootTopic;
    }

    private Map<String, Object> buildModuleTopicMap(String moduleName, List<TestCase> testCases) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "module-" + sanitizeName(moduleName));
        node.put("title", moduleName);

        Map<String, List<TestCase>> byType = groupByType(testCases);
        List<Map<String, Object>> typeChildren = new ArrayList<>();
        for (Map.Entry<String, List<TestCase>> entry : byType.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                typeChildren.add(buildTypeTopicMap(entry.getKey(), entry.getValue()));
            }
        }

        Map<String, Object> children = new LinkedHashMap<>();
        children.put("attached", typeChildren);
        node.put("children", children);
        return node;
    }

    private Map<String, Object> buildTypeTopicMap(String typeName, List<TestCase> testCases) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "type-" + typeName);
        node.put("title", typeName);

        List<Map<String, Object>> tcChildren = new ArrayList<>();
        for (TestCase tc : testCases) {
            tcChildren.add(buildTestCaseTopicMap(tc));
        }

        Map<String, Object> children = new LinkedHashMap<>();
        children.put("attached", tcChildren);
        node.put("children", children);
        return node;
    }

    private Map<String, Object> buildTestCaseTopicMap(TestCase tc) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", tc.getId());
        node.put("title", tc.getId() + " " + tc.getTitle());

        List<Map<String, Object>> detailChildren = new ArrayList<>();

        List<String> preconditions = JsonHelper.parseListString(tc.getPreconditions());
        if (!preconditions.isEmpty()) {
            detailChildren.add(buildLeafListNode("preconditions-" + tc.getId(), "前置条件", preconditions));
        }

        List<String> steps = JsonHelper.parseListString(tc.getSteps());
        if (!steps.isEmpty()) {
            detailChildren.add(buildLeafListNode("steps-" + tc.getId(), "测试步骤", steps));
        }

        List<String> expectedResults = JsonHelper.parseListString(tc.getExpectedResults());
        if (!expectedResults.isEmpty()) {
            detailChildren.add(buildLeafListNode("expected-" + tc.getId(), "预期结果", expectedResults));
        }

        if (!detailChildren.isEmpty()) {
            Map<String, Object> children = new LinkedHashMap<>();
            children.put("attached", detailChildren);
            node.put("children", children);
        }

        return node;
    }

    private Map<String, Object> buildLeafListNode(String id, String title, List<String> items) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("title", title);

        List<Map<String, Object>> children = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("id", id + "-" + i);
            child.put("title", items.get(i));
            children.add(child);
        }

        Map<String, Object> childMap = new LinkedHashMap<>();
        childMap.put("attached", children);
        node.put("children", childMap);
        return node;
    }

    private MindMapPreviewNode buildModulePreviewNode(String moduleName, List<TestCase> testCases) {
        Map<String, List<TestCase>> byType = groupByType(testCases);
        List<MindMapPreviewNode> typeChildren = new ArrayList<>();
        for (Map.Entry<String, List<TestCase>> entry : byType.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                typeChildren.add(buildTypePreviewNode(entry.getKey(), entry.getValue()));
            }
        }
        return new MindMapPreviewNode("module-" + sanitizeName(moduleName), moduleName, typeChildren);
    }

    private MindMapPreviewNode buildTypePreviewNode(String typeName, List<TestCase> testCases) {
        List<MindMapPreviewNode> tcChildren = new ArrayList<>();
        for (TestCase tc : testCases) {
            tcChildren.add(buildTestCasePreviewNode(tc));
        }
        return new MindMapPreviewNode("type-" + typeName, typeName, tcChildren);
    }

    private MindMapPreviewNode buildTestCasePreviewNode(TestCase tc) {
        List<MindMapPreviewNode> detailChildren = new ArrayList<>();

        List<String> preconditions = JsonHelper.parseListString(tc.getPreconditions());
        if (!preconditions.isEmpty()) {
            detailChildren.add(buildLeafPreviewNode("preconditions-" + tc.getId(), "前置条件", preconditions));
        }

        List<String> steps = JsonHelper.parseListString(tc.getSteps());
        if (!steps.isEmpty()) {
            detailChildren.add(buildLeafPreviewNode("steps-" + tc.getId(), "测试步骤", steps));
        }

        List<String> expectedResults = JsonHelper.parseListString(tc.getExpectedResults());
        if (!expectedResults.isEmpty()) {
            detailChildren.add(buildLeafPreviewNode("expected-" + tc.getId(), "预期结果", expectedResults));
        }

        return new MindMapPreviewNode(tc.getId(), tc.getId() + " " + tc.getTitle(), detailChildren);
    }

    private MindMapPreviewNode buildLeafPreviewNode(String id, String title, List<String> items) {
        List<MindMapPreviewNode> children = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            children.add(new MindMapPreviewNode(id + "-" + i, items.get(i), new ArrayList<>()));
        }
        return new MindMapPreviewNode(id, title, children);
    }
}
