package com.testagent.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.EntityInfo;
import com.testagent.analyzer.result.EnumInfo;
import com.testagent.analyzer.result.EnumValue;
import com.testagent.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SpringAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SpringAnalyzer.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern SPRING_BOOT_PARENT_VERSION_PATTERN =
            Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern SPRING_BOOT_VERSION_PROPERTY_PATTERN =
            Pattern.compile("<spring-boot\\.version>([^<]+)</spring-boot\\.version>");

    @Autowired
    private LlmService llmService;

    public BackendResult analyze(String backendDir) {
        File dir = new File(backendDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return BackendResult.skipped();
        }

        Map<String, Object> techStack = detectTechStack(dir);
        List<File> javaFiles = findJavaFiles(dir);

        List<EndpointInfo> endpoints = new ArrayList<>();
        List<EnumInfo> enums = new ArrayList<>();
        List<EntityInfo> entities = new ArrayList<>();
        List<BusinessRule> businessRules = new ArrayList<>();

        for (File javaFile : javaFiles) {
            String relativePath = relativize(dir, javaFile);
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                endpoints.addAll(extractEndpoints(cu, relativePath));
                enums.addAll(extractEnums(cu, relativePath));
                entities.addAll(extractEntities(cu, relativePath));
                businessRules.addAll(extractBusinessRules(cu, relativePath));
            } catch (Exception e) {
                // skip single file parse failure so the whole analysis is not broken
            }
        }

        enhanceWithLlmIfConfigured(dir, javaFiles, endpoints, enums, entities, businessRules);

        return BackendResult.builder()
                .techStack(techStack)
                .endpoints(endpoints)
                .enums(enums)
                .entities(entities)
                .businessRules(businessRules)
                .fileCount(javaFiles.size())
                .status("ok")
                .build();
    }

    private Map<String, Object> detectTechStack(File backendDir) {
        Map<String, Object> tech = new HashMap<>();
        File pom = new File(backendDir, "pom.xml");
        if (!pom.exists()) {
            return tech;
        }
        try {
            String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
            tech.put("framework", "spring-boot");
            tech.put("language", "java");

            if (content.contains("spring-boot-starter-parent") || content.contains("spring-boot-starter")) {
                tech.put("backend", "spring-boot");
                Matcher m = SPRING_BOOT_PARENT_VERSION_PATTERN.matcher(content);
                if (m.find()) {
                    tech.put("springBootVersion", m.group(1));
                } else {
                    Matcher m2 = SPRING_BOOT_VERSION_PROPERTY_PATTERN.matcher(content);
                    if (m2.find()) {
                        tech.put("springBootVersion", m2.group(1));
                    }
                }
            }
            if (content.contains("mybatis")) {
                tech.put("orm", "mybatis");
            }
            if (content.contains("shiro")) {
                tech.put("security", "shiro");
            } else if (content.contains("spring-boot-starter-security")) {
                tech.put("security", "spring-security");
            }
            if (content.contains("spring-boot-starter-data-jpa") || content.contains("hibernate")) {
                tech.put("persistence", "jpa");
            }
            if (content.contains("redis") || content.contains("spring-boot-starter-data-redis")) {
                tech.put("cache", "redis");
            }
            if (content.contains("swagger") || content.contains("springdoc")) {
                tech.put("apiDocs", "swagger");
            }
        } catch (IOException e) {
            // ignore unreadable pom.xml
        }
        return tech;
    }

    private List<EndpointInfo> extractEndpoints(CompilationUnit cu, String filePath) {
        List<EndpointInfo> endpoints = new ArrayList<>();
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface()) {
                continue;
            }
            boolean isController = cls.getAnnotations().stream()
                    .anyMatch(a -> {
                        String name = a.getNameAsString();
                        return name.equals("RestController") || name.equals("Controller");
                    });
            if (!isController) {
                continue;
            }

            String classPath = cls.getAnnotations().stream()
                    .filter(a -> a.getNameAsString().equals("RequestMapping"))
                    .map(this::extractAnnotationPath)
                    .findFirst()
                    .orElse("");

            String className = cls.getNameAsString();
            for (MethodDeclaration method : cls.getMethods()) {
                for (AnnotationExpr ann : method.getAnnotations()) {
                    String httpMethod = mapHttpMethod(ann.getNameAsString());
                    if (httpMethod == null) {
                        continue;
                    }
                    String methodPath = extractAnnotationPath(ann);
                    String fullPath = joinPath(classPath, methodPath);
                    endpoints.add(EndpointInfo.builder()
                            .method(httpMethod)
                            .path(fullPath)
                            .function(className + "." + method.getNameAsString())
                            .file(filePath)
                            .sources(List.of("rules"))
                            .build());
                }
            }
        }
        return endpoints;
    }

    private List<EnumInfo> extractEnums(CompilationUnit cu, String filePath) {
        List<EnumInfo> enums = new ArrayList<>();

        for (EnumDeclaration enumDecl : cu.findAll(EnumDeclaration.class)) {
            List<EnumValue> values = new ArrayList<>();
            for (EnumConstantDeclaration constant : enumDecl.getEntries()) {
                values.add(EnumValue.builder()
                        .name(constant.getNameAsString())
                        .value(constant.getNameAsString())
                        .description("")
                        .build());
            }
            enums.add(EnumInfo.builder()
                    .name(enumDecl.getNameAsString())
                    .type("enum")
                    .values(values)
                    .file(filePath)
                    .sources(List.of("rules"))
                    .build());
        }

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface()) {
                continue;
            }
            List<EnumValue> constValues = new ArrayList<>();
            for (FieldDeclaration field : cls.getFields()) {
                boolean isStatic = field.getModifiers().stream()
                        .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
                boolean isFinal = field.getModifiers().stream()
                        .anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
                if (isStatic && isFinal) {
                    for (VariableDeclarator vd : field.getVariables()) {
                        String value = vd.getInitializer().map(Object::toString).orElse("");
                        constValues.add(EnumValue.builder()
                                .name(vd.getNameAsString())
                                .value(stripQuotes(value))
                                .description("")
                                .build());
                    }
                }
            }
            if (constValues.size() >= 3) {
                enums.add(EnumInfo.builder()
                        .name(cls.getNameAsString())
                        .type("constants")
                        .values(constValues)
                        .file(filePath)
                        .sources(List.of("rules"))
                        .build());
            }
        }
        return enums;
    }

    private List<EntityInfo> extractEntities(CompilationUnit cu, String filePath) {
        List<EntityInfo> entities = new ArrayList<>();
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface()) {
                continue;
            }
            boolean isEntity = cls.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("Entity"));

            List<Map<String, Object>> fields = new ArrayList<>();
            for (FieldDeclaration field : cls.getFields()) {
                String fieldType = field.getElementType().toString();
                for (VariableDeclarator vd : field.getVariables()) {
                    Map<String, Object> f = new HashMap<>();
                    f.put("name", vd.getNameAsString());
                    f.put("type", fieldType);
                    fields.add(f);
                }
            }

            boolean hasGettersSetters = cls.getMethods().stream().anyMatch(m -> {
                String name = m.getNameAsString();
                return name.startsWith("get") || name.startsWith("set") || name.startsWith("is");
            });

            if (isEntity || (fields.size() >= 3 && hasGettersSetters)) {
                entities.add(EntityInfo.builder()
                        .name(cls.getNameAsString())
                        .fields(fields)
                        .file(filePath)
                        .sources(List.of("rules"))
                        .build());
            }
        }
        return entities;
    }

    private List<BusinessRule> extractBusinessRules(CompilationUnit cu, String filePath) {
        List<BusinessRule> rules = new ArrayList<>();
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            String methodName = method.getNameAsString();
            for (IfStmt ifStmt : method.findAll(IfStmt.class)) {
                String ifText = ifStmt.toString();
                if (!ifText.contains("throw") || !ifText.contains("new")) {
                    continue;
                }
                String condition = truncate(ifStmt.getCondition().toString(), 200);
                List<ObjectCreationExpr> creations = ifStmt.findAll(ObjectCreationExpr.class);
                if (creations.isEmpty()) {
                    rules.add(BusinessRule.builder()
                            .file(filePath)
                            .function(methodName)
                            .rule("if (" + condition + ") then throw")
                            .ruleType("throw_exception")
                            .sources(List.of("rules"))
                            .build());
                } else {
                    for (ObjectCreationExpr creation : creations) {
                        String exceptionType = creation.getType().asString();
                        String message = "";
                        if (!creation.getArguments().isEmpty()) {
                            message = truncate(creation.getArguments().get(0).toString(), 200);
                        }
                        String desc = "if (" + condition + ") throw " + exceptionType
                                + (message.isEmpty() ? "" : "(" + message + ")");
                        rules.add(BusinessRule.builder()
                                .file(filePath)
                                .function(methodName)
                                .rule(desc)
                                .ruleType("throw_exception")
                                .sources(List.of("rules"))
                                .build());
                    }
                }
            }
        }
        return rules;
    }

    // v5.13: 规则结果作为 ground truth，LLM 只补充接口语义/参数校验/权限/业务规则
    private void enhanceWithLlmIfConfigured(File dir,
                                            List<File> javaFiles,
                                            List<EndpointInfo> endpoints,
                                            List<EnumInfo> enums,
                                            List<EntityInfo> entities,
                                            List<BusinessRule> businessRules) {
        if (!llmService.isConfigured()) {
            return;
        }
        try {
            enhanceWithLlm(dir, javaFiles, endpoints, enums, entities, businessRules);
        } catch (Exception e) {
            log.warn("LLM backend enhancement failed, using rule-based results: {}", e.getMessage());
        }
    }

    private void enhanceWithLlm(File dir,
                                List<File> javaFiles,
                                List<EndpointInfo> endpoints,
                                List<EnumInfo> enums,
                                List<EntityInfo> entities,
                                List<BusinessRule> businessRules) {
        String sourceSnippets = collectSourceSnippets(dir, javaFiles);
        if (sourceSnippets.isBlank()) {
            return;
        }
        String systemPrompt = """
                你是 Spring Boot 后端代码分析专家。规则解析器已经提取了确定性的接口清单、枚举、实体字段和异常规则。
                请阅读源码，只补充规则遗漏的语义信息，不要修改规则已经确认的事实。

                约束：
                1. endpointEnhancements 只用于补充已存在接口的 description、parameters、requestBody、permissions、validation，
                   不得修改 method/path/function/file。
                2. supplementalEndpoints 只返回规则未提取到、且源码中真实存在的接口。
                3. entityEnhancements 只补充 description、fieldConstraints、relationships，不得修改 name/fields/file。
                4. supplementalBusinessRules 只返回源码中真实存在的业务规则，不得编造。
                5. enumEnhancements 只补充 description，不得新增枚举值。

                只返回纯 JSON，不要 markdown 代码块。JSON 结构：
                {
                  "endpointEnhancements": [{
                    "method": "POST",
                    "path": "/api/order/create",
                    "description": "创建订单",
                    "parameters": [{"name": "orderId", "in": "path", "type": "Long", "required": true, "description": "订单ID"}],
                    "requestBody": "创建订单请求体，包含商品、数量、收货地址",
                    "permissions": ["ROLE_USER"],
                    "validation": ["商品库存必须大于0", "订单金额必须大于0"]
                  }],
                  "supplementalEndpoints": [{
                    "method": "POST",
                    "path": "/api/order/create",
                    "function": "OrderController.create",
                    "file": "src/main/java/com/example/OrderController.java",
                    "description": "创建订单",
                    "parameters": [],
                    "requestBody": "",
                    "permissions": [],
                    "validation": []
                  }],
                  "entityEnhancements": [{
                    "name": "Order",
                    "description": "订单实体",
                    "fieldConstraints": [{"name": "status", "type": "String", "required": true, "maxLength": 32, "description": "订单状态"}],
                    "relationships": [{"name": "items", "type": "OneToMany", "target": "OrderItem", "description": "订单明细"}]
                  }],
                  "supplementalBusinessRules": [{
                    "file": "src/main/java/com/example/OrderService.java",
                    "function": "createOrder",
                    "rule": "库存不足时抛出库存不足异常",
                    "ruleType": "validation"
                  }],
                  "enumEnhancements": [{
                    "name": "OrderStatus",
                    "description": "订单状态枚举",
                    "values": [{"name": "CREATED", "description": "已创建"}]
                  }]
                }
                """;
        String summary = buildRuleSummary(endpoints, enums, entities, businessRules);
        String userPrompt = "规则已提取结果：\n" + summary
                + "\n\n源码摘要：\n" + sourceSnippets
                + "\n\n请分析源码，补充规则遗漏的接口语义、参数校验、权限和业务规则。";
        String response = llmService.chat(systemPrompt, userPrompt, 0.3);
        parseAndMergeSupplements(response, endpoints, enums, entities, businessRules);
    }

    private String buildRuleSummary(List<EndpointInfo> endpoints,
                                    List<EnumInfo> enums,
                                    List<EntityInfo> entities,
                                    List<BusinessRule> businessRules) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("endpoints", endpoints.stream().map(EndpointInfo::toContextMap).toList());
        summary.put("enums", enums);
        summary.put("entities", entities.stream().map(EntityInfo::toContextMap).toList());
        summary.put("businessRules", businessRules.stream().map(BusinessRule::toContextMap).toList());
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            return summary.toString();
        }
    }

    private String collectSourceSnippets(File dir, List<File> javaFiles) {
        List<File> files = new ArrayList<>(javaFiles);
        files.sort(Comparator.comparingInt(this::sourcePriority).thenComparing(File::getName));
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        int maxTotal = 16000;
        int maxPerFile = 1500;
        for (File file : files) {
            if (totalChars >= maxTotal) {
                break;
            }
            String relPath = relativize(dir, file);
            if (relPath.contains("/test/") || relPath.contains("src/test/")) {
                continue;
            }
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                if (content.length() > maxPerFile) {
                    content = content.substring(0, maxPerFile);
                }
                String block = "// ===== " + relPath + " =====\n" + content + "\n\n";
                sb.append(block);
                totalChars += block.length();
            } catch (IOException e) {
                // 跳过读取失败的文件
            }
        }
        return sb.toString();
    }

    private int sourcePriority(File file) {
        String name = file.getName().toLowerCase();
        if (name.contains("controller")) return 0;
        if (name.contains("service") || name.contains("facade")) return 1;
        if (name.contains("entity") || name.contains("domain") || name.contains("model")) return 2;
        if (name.contains("repository") || name.contains("mapper")) return 3;
        return 4;
    }

    private void parseAndMergeSupplements(String response,
                                          List<EndpointInfo> endpoints,
                                          List<EnumInfo> enums,
                                          List<EntityInfo> entities,
                                          List<BusinessRule> businessRules) {
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(response));
        } catch (Exception e) {
            log.warn("Failed to parse backend LLM supplement JSON: {}", e.getMessage());
            return;
        }
        mergeEndpointEnhancements(root.path("endpointEnhancements"), endpoints);
        mergeSupplementalEndpoints(root.path("supplementalEndpoints"), endpoints);
        mergeEntityEnhancements(root.path("entityEnhancements"), entities);
        mergeSupplementalBusinessRules(root.path("supplementalBusinessRules"), businessRules);
        mergeEnumEnhancements(root.path("enumEnhancements"), enums);
    }

    private void mergeEndpointEnhancements(JsonNode nodes, List<EndpointInfo> endpoints) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            EndpointInfo endpoint = findEndpoint(endpoints, node);
            if (endpoint == null) {
                continue;
            }
            boolean changed = false;
            if (node.hasNonNull("description") && isBlank(endpoint.getDescription())) {
                endpoint.setDescription(node.path("description").asText("").trim());
                changed = true;
            }
            if (node.has("parameters") && node.get("parameters").isArray()) {
                changed |= mergeParameters(endpoint, node.get("parameters"));
            }
            if (node.hasNonNull("requestBody") && isBlank(endpoint.getRequestBody())) {
                endpoint.setRequestBody(node.path("requestBody").asText("").trim());
                changed = true;
            }
            changed |= mergeStringList(endpoint.getPermissions(), node.path("permissions"),
                    endpoint::setPermissions);
            changed |= mergeStringList(endpoint.getValidation(), node.path("validation"),
                    endpoint::setValidation);
            if (changed) {
                endpoint.setSources(mergeSource(endpoint.getSources(), "llm"));
                log.info("LLM enriched endpoint: {} {}", endpoint.getMethod(), endpoint.getPath());
            }
        }
    }

    private void mergeSupplementalEndpoints(JsonNode nodes, List<EndpointInfo> endpoints) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String method = node.path("method").asText("").trim();
            String path = normalizePath(node.path("path").asText(""));
            if (method.isEmpty() || path.isEmpty() || findEndpoint(endpoints, node) != null) {
                continue;
            }
            EndpointInfo endpoint = EndpointInfo.builder()
                    .method(method.toUpperCase())
                    .path(path)
                    .function(node.path("function").asText("").trim())
                    .file(node.path("file").asText("").trim())
                    .description(node.path("description").asText("").trim())
                    .parameters(parseMapList(node.path("parameters")))
                    .requestBody(node.path("requestBody").asText("").trim())
                    .permissions(parseStringList(node.path("permissions")))
                    .validation(parseStringList(node.path("validation")))
                    .sources(new ArrayList<>(List.of("llm")))
                    .build();
            endpoints.add(endpoint);
            log.info("LLM supplemented endpoint: {} {}", endpoint.getMethod(), endpoint.getPath());
        }
    }

    private void mergeEntityEnhancements(JsonNode nodes, List<EntityInfo> entities) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            EntityInfo entity = findEntity(entities, node.path("name").asText("").trim());
            if (entity == null) {
                continue;
            }
            boolean changed = false;
            if (node.hasNonNull("description") && isBlank(entity.getDescription())) {
                entity.setDescription(node.path("description").asText("").trim());
                changed = true;
            }
            changed |= mergeNamedList(entity.getFieldConstraints(), node.path("fieldConstraints"),
                    entity::setFieldConstraints);
            changed |= mergeNamedList(entity.getRelationships(), node.path("relationships"),
                    entity::setRelationships);
            if (changed) {
                entity.setSources(mergeSource(entity.getSources(), "llm"));
                log.info("LLM enriched entity: {}", entity.getName());
            }
        }
    }

    private void mergeSupplementalBusinessRules(JsonNode nodes, List<BusinessRule> businessRules) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String rule = node.path("rule").asText("").trim();
            if (rule.isEmpty()) {
                continue;
            }
            String function = node.path("function").asText("").trim();
            String file = node.path("file").asText("").trim();
            boolean exists = businessRules.stream().anyMatch(r ->
                    rule.equals(r.getRule())
                            && (function.isEmpty() || function.equals(r.getFunction()))
                            && (file.isEmpty() || file.equals(r.getFile())));
            if (!exists) {
                String ruleType = node.path("ruleType").asText("").trim();
                businessRules.add(BusinessRule.builder()
                        .file(file)
                        .function(function)
                        .rule(rule)
                        .ruleType(ruleType.isEmpty() ? "llm_supplement" : ruleType)
                        .sources(new ArrayList<>(List.of("llm")))
                        .build());
                log.info("LLM supplemented business rule: {}", rule);
            }
        }
    }

    private void mergeEnumEnhancements(JsonNode nodes, List<EnumInfo> enums) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            EnumInfo enumInfo = findEnum(enums, node.path("name").asText("").trim());
            if (enumInfo == null) {
                continue;
            }
            boolean changed = false;
            if (node.hasNonNull("description") && isBlank(enumInfo.getDescription())) {
                enumInfo.setDescription(node.path("description").asText("").trim());
                changed = true;
            }
            JsonNode values = node.path("values");
            if (values.isArray() && enumInfo.getValues() != null) {
                for (JsonNode valueNode : values) {
                    String valueName = valueNode.path("name").asText("").trim();
                    if (valueName.isEmpty()) {
                        continue;
                    }
                    EnumValue existing = enumInfo.getValues().stream()
                            .filter(v -> valueName.equals(v.getName()))
                            .findFirst()
                            .orElse(null);
                    if (existing != null && isBlank(existing.getDescription())
                            && valueNode.hasNonNull("description")) {
                        existing.setDescription(valueNode.path("description").asText("").trim());
                        changed = true;
                    }
                }
            }
            if (changed) {
                enumInfo.setSources(mergeSource(enumInfo.getSources(), "llm"));
                log.info("LLM enriched enum: {}", enumInfo.getName());
            }
        }
    }

    private boolean mergeParameters(EndpointInfo endpoint, JsonNode nodes) {
        List<Map<String, Object>> merged = new ArrayList<>(
                endpoint.getParameters() == null ? List.of() : endpoint.getParameters());
        boolean changed = false;
        for (JsonNode node : nodes) {
            Map<String, Object> param = toMap(node);
            String name = param.get("name") == null ? "" : String.valueOf(param.get("name"));
            String in = param.get("in") == null ? "query" : String.valueOf(param.get("in"));
            if (name.isBlank()) {
                continue;
            }
            boolean exists = merged.stream()
                    .anyMatch(p -> name.equals(p.get("name")) && in.equals(p.get("in")));
            if (!exists) {
                merged.add(param);
                changed = true;
            }
        }
        if (changed) {
            endpoint.setParameters(merged);
        }
        return changed;
    }

    private boolean mergeNamedList(List<Map<String, Object>> currentList,
                                   JsonNode nodes,
                                   Consumer<List<Map<String, Object>>> setter) {
        if (nodes == null || !nodes.isArray()) {
            return false;
        }
        List<Map<String, Object>> merged = new ArrayList<>(currentList == null ? List.of() : currentList);
        boolean changed = false;
        for (JsonNode node : nodes) {
            Map<String, Object> item = toMap(node);
            String name = item.get("name") == null ? "" : String.valueOf(item.get("name"));
            if (name.isBlank()) {
                continue;
            }
            boolean exists = merged.stream().anyMatch(p -> name.equals(p.get("name")));
            if (!exists) {
                merged.add(item);
                changed = true;
            }
        }
        if (changed) {
            setter.accept(merged);
        }
        return changed;
    }

    private boolean mergeStringList(List<String> currentList,
                                    JsonNode nodes,
                                    Consumer<List<String>> setter) {
        if (nodes == null || !nodes.isArray()) {
            return false;
        }
        List<String> merged = new ArrayList<>(currentList == null ? List.of() : currentList);
        boolean changed = false;
        for (JsonNode node : nodes) {
            String value = node.asText("").trim();
            if (!value.isEmpty() && !merged.contains(value)) {
                merged.add(value);
                changed = true;
            }
        }
        if (changed) {
            setter.accept(merged);
        }
        return changed;
    }

    private List<String> mergeSource(List<String> current, String source) {
        List<String> merged = new ArrayList<>();
        if (current != null) {
            for (String item : current) {
                if (item != null && !item.isBlank() && !merged.contains(item)) {
                    merged.add(item);
                }
            }
        }
        if (source != null && !source.isBlank() && !merged.contains(source)) {
            merged.add(source);
        }
        return merged;
    }

    private List<Map<String, Object>> parseMapList(JsonNode node) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                list.add(toMap(item));
            }
        }
        return list;
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("").trim();
                if (!value.isEmpty()) {
                    list.add(value);
                }
            }
        }
        return list;
    }

    private EndpointInfo findEndpoint(List<EndpointInfo> endpoints, JsonNode node) {
        String method = node.path("method").asText("").trim();
        String path = normalizePath(node.path("path").asText(""));
        if (method.isEmpty() || path.isEmpty()) {
            return null;
        }
        for (EndpointInfo endpoint : endpoints) {
            String currentMethod = endpoint.getMethod() == null ? "" : endpoint.getMethod();
            if (method.equalsIgnoreCase(currentMethod)
                    && normalizePath(endpoint.getPath()).equals(path)) {
                return endpoint;
            }
        }
        return null;
    }

    private EntityInfo findEntity(List<EntityInfo> entities, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (EntityInfo entity : entities) {
            if (name.equals(entity.getName())) {
                return entity;
            }
        }
        return null;
    }

    private EnumInfo findEnum(List<EnumInfo> enums, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (EnumInfo enumInfo : enums) {
            if (name.equals(enumInfo.getName())) {
                return enumInfo;
            }
        }
        return null;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        String json = text.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return json.substring(start, end + 1);
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String extractAnnotationPath(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return stripQuotes(single.getMemberValue().toString());
        } else if (ann instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                String key = pair.getNameAsString();
                if (key.equals("value") || key.equals("path")) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }
        return "";
    }

    private String mapHttpMethod(String annotationName) {
        return switch (annotationName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            case "RequestMapping" -> "ANY";
            default -> null;
        };
    }

    private String joinPath(String base, String sub) {
        if (base == null) {
            base = "";
        }
        if (sub == null) {
            sub = "";
        }
        if (base.isEmpty()) {
            return sub.isEmpty() ? "/" : sub;
        }
        if (sub.isEmpty()) {
            return base;
        }
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String s = sub.startsWith("/") ? sub : "/" + sub;
        return b + s;
    }

    private String stripQuotes(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        if (s.startsWith("{") && s.endsWith("}")) {
            String inner = s.substring(1, s.length() - 1).trim();
            if (inner.startsWith("\"") && inner.endsWith("\"") && inner.length() >= 2) {
                return inner.substring(1, inner.length() - 1);
            }
            return inner;
        }
        return s;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String relativize(File base, File file) {
        try {
            return base.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    private List<File> findJavaFiles(File backendDir) {
        List<File> files = new ArrayList<>();
        collectJavaFiles(backendDir, files);
        return files;
    }

    private void collectJavaFiles(File dir, List<File> result) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String name = child.getName();
                if (name.equals("target") || name.equals("build")
                        || name.equals("node_modules") || name.equals(".git")) {
                    continue;
                }
                collectJavaFiles(child, result);
            } else if (child.isFile() && child.getName().endsWith(".java")) {
                result.add(child);
            }
        }
    }
}
