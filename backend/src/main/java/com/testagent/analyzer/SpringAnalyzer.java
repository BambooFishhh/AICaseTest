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
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.EntityInfo;
import com.testagent.analyzer.result.EnumInfo;
import com.testagent.analyzer.result.EnumValue;
import com.testagent.analyzer.result.OperationDep;
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
import java.util.LinkedHashSet;
import java.util.Set;
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
        List<String> warnings = new ArrayList<>();
        List<File> javaFiles = findJavaFiles(dir, warnings);

        List<EndpointInfo> endpoints = new ArrayList<>();
        List<EnumInfo> enums = new ArrayList<>();
        List<EntityInfo> entities = new ArrayList<>();
        List<BusinessRule> businessRules = new ArrayList<>();
        List<OperationDep> dependencyGraph = new ArrayList<>();

        List<String> parseFailures = new ArrayList<>();
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
                parseFailures.add(relativePath);
                log.debug("Java parse failed for {}: {}", relativePath, e.getMessage());
            }
        }
        // v7.4(C1): 单文件解析失败不再静默——计数 + 前 5 个路径写入 warnings
        if (!parseFailures.isEmpty()) {
            warnings.add("Java 文件解析失败 " + parseFailures.size() + " 个（"
                    + String.join(", ", parseFailures.subList(0, Math.min(5, parseFailures.size())))
                    + (parseFailures.size() > 5 ? " 等" : "") + "），相关接口/实体/规则可能缺失");
        }
        dependencyGraph.addAll(extractDependencyGraph(dir, javaFiles));

        enhanceWithLlmIfConfigured(dir, javaFiles, endpoints, enums, entities, businessRules, warnings);

        return BackendResult.builder()
                .techStack(techStack)
                .endpoints(endpoints)
                .enums(enums)
                .entities(entities)
                .businessRules(businessRules)
                .dependencyGraph(dependencyGraph)
                .fileCount(javaFiles.size())
                .status("ok")
                .warnings(warnings)
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
                    // v7.4(A2): 传注解对象以解析 @RequestMapping 的 method 属性
                    String httpMethod = mapHttpMethod(ann);
                    if (httpMethod == null) {
                        continue;
                    }
                    String methodPath = extractAnnotationPath(ann);
                    String fullPath = joinPath(classPath, methodPath);
                    // v6.1 (SAINT): 确定性采集响应结构/业务逻辑/异常类型
                    List<String> exceptionTypes = new ArrayList<>();
                    method.findFirst(ThrowStmt.class)
                            .flatMap(t -> t.getExpression().findFirst(ObjectCreationExpr.class))
                            .ifPresent(c -> exceptionTypes.add(c.getType().asString()));
                    for (var thrown : method.getThrownExceptions()) {
                        String thrownName = thrown.asString();
                        if (!exceptionTypes.contains(thrownName)) {
                            exceptionTypes.add(thrownName);
                        }
                    }
                    endpoints.add(EndpointInfo.builder()
                            .method(httpMethod)
                            .path(fullPath)
                            .function(className + "." + method.getNameAsString())
                            .file(filePath)
                            .responseBody(method.getType().asString())
                            .businessLogic(compactMethodBody(method))
                            .exceptions(exceptionTypes)
                            .sources(List.of("rules"))
                            .build());
                }
            }
        }
        return endpoints;
    }

    // v6.1 (SAINT): 确定性生成操作依赖图。以 Service/Facade 为节点，依据方法体内的
    // MethodCallExpr（含注入字段作用域）解析 "ClassA.method -> ClassB.method" 边。
    private List<OperationDep> extractDependencyGraph(File dir, List<File> javaFiles) {
        List<ServiceClass> services = new ArrayList<>();
        for (File file : javaFiles) {
            String rel = relativize(dir, file);
            if (rel.contains("/test/") || rel.contains("src/test/")) {
                continue;
            }
            try {
                CompilationUnit cu = StaticJavaParser.parse(file);
                for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    String clsName = cls.getNameAsString();
                    if (!isServiceClass(clsName)) {
                        continue;
                    }
                    Set<String> methods = new LinkedHashSet<>();
                    Map<String, String> fieldTypes = new HashMap<>();
                    for (MethodDeclaration m : cls.getMethods()) {
                        methods.add(m.getNameAsString());
                    }
                    for (FieldDeclaration field : cls.getFields()) {
                        String type = field.getElementType().asString();
                        if (isServiceClass(type)) {
                            for (VariableDeclarator vd : field.getVariables()) {
                                fieldTypes.putIfAbsent(vd.getNameAsString(), type);
                            }
                        }
                    }
                    services.add(new ServiceClass(clsName, rel, methods, fieldTypes));
                }
            } catch (Exception e) {
                log.debug("Dependency graph parse failed for {}: {}", file.getName(), e.getMessage());
            }
        }

        // 简单名 -> 定义该方法的 Service 类集合，用于解析无显式作用域 / 简短作用域的调用。
        Map<String, Set<String>> methodOwners = new HashMap<>();
        for (ServiceClass svc : services) {
            for (String m : svc.methods()) {
                methodOwners.computeIfAbsent(m, k -> new LinkedHashSet<>()).add(svc.name());
            }
        }

        List<OperationDep> result = new ArrayList<>();
        for (ServiceClass svc : services) {
            File f = new File(dir, svc.file());
            try {
                CompilationUnit cu = StaticJavaParser.parse(f);
                ClassOrInterfaceDeclaration cls = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(c -> c.getNameAsString().equals(svc.name())).findFirst().orElse(null);
                if (cls == null) {
                    continue;
                }
                for (MethodDeclaration method : cls.getMethods()) {
                    String operation = svc.name() + "." + method.getNameAsString();
                    Set<String> deps = new LinkedHashSet<>();
                    for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                        String calledName = call.getNameAsString();
                        String scopeType = svc.fieldTypes().get(
                                call.getScope().map(Object::toString).orElse(""));
                        if (scopeType != null && svc.methods().contains(calledName)
                                && methodOwners.getOrDefault(calledName, Set.of()).contains(scopeType)) {
                            deps.add(scopeType + "." + calledName);
                        } else if (scopeType != null
                                && methodOwners.getOrDefault(calledName, Set.of()).contains(scopeType)) {
                            deps.add(scopeType + "." + calledName);
                        } else if (call.getScope().isEmpty() && svc.methods().contains(calledName)) {
                            if (!calledName.equals(method.getNameAsString())) {
                                deps.add(svc.name() + "." + calledName);
                            }
                        } else if (methodOwners.getOrDefault(calledName, Set.of()).size() == 1) {
                            String owner = methodOwners.get(calledName).iterator().next();
                            if (!owner.equals(svc.name())) {
                                deps.add(owner + "." + calledName);
                            }
                        }
                    }
                    if (!deps.isEmpty()) {
                        result.add(OperationDep.builder()
                                .operation(operation)
                                .kind("service")
                                .file(svc.file())
                                .description("静态方法调用依赖")
                                .dependsOn(new ArrayList<>(deps))
                                .build());
                    }
                }
            } catch (Exception e) {
                log.debug("Dependency graph build failed for {}: {}", svc.name(), e.getMessage());
            }
        }
        result.sort(Comparator.comparing(OperationDep::getOperation));
        return result;
    }

    private boolean isServiceClass(String clsName) {
        if (clsName == null) {
            return false;
        }
        return clsName.endsWith("Service") || clsName.endsWith("ServiceImpl")
                || clsName.endsWith("Facade") || clsName.endsWith("FacadeImpl")
                || clsName.endsWith("Manager") || clsName.endsWith("ManagerImpl");
    }

    private String compactMethodBody(MethodDeclaration method) {
        var body = method.getBody();
        if (body.isEmpty()) {
            return "";
        }
        String text = body.get().toString().replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private record ServiceClass(String name, String file, Set<String> methods,
                                Map<String, String> fieldTypes) {
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
                                            List<BusinessRule> businessRules,
                                            List<String> warnings) {
        if (!llmService.isConfigured()) {
            return;
        }
        try {
            enhanceWithLlm(dir, javaFiles, endpoints, enums, entities, businessRules);
        } catch (Exception e) {
            log.warn("LLM backend enhancement failed, using rule-based results: {}", e.getMessage());
            // v7.4(C1): LLM 增强失败不再只在日志中静默降级
            warnings.add("LLM 后端增强失败（" + e.getMessage() + "），结果仅含规则提取部分");
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
        // v6.1 (B 方案): 降采样——只保留最有价值的子集并控制单条长度，避免全量 JSON 超限。
        summary.put("endpointCount", endpoints.size());
        summary.put("endpoints", endpoints.stream().limit(200)
                .map(ep -> {
                    Map<String, Object> m = new LinkedHashMap<>(ep.toContextMap());
                    m.put("businessLogic", truncate((String) m.get("businessLogic"), 200));
                    return m;
                }).toList());
        summary.put("enums", enums.size() > 120 ? enums.subList(0, 120) : enums);
        summary.put("entities", entities.stream().limit(200).map(EntityInfo::toContextMap).toList());
        summary.put("businessRules", businessRules.stream().limit(300).map(BusinessRule::toContextMap).toList());
        try {
            String json = objectMapper.writeValueAsString(summary);
            return json.length() > 30000 ? json.substring(0, 30000) : json;
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

    // v7.4(A2): 方法级 @RequestMapping 解析 method 属性（此前恒返回 ANY，
    // 老项目 method = RequestMethod.POST 写法路径对但方法错，接口覆盖率分母被污染）
    private String mapHttpMethod(AnnotationExpr ann) {
        if (!"RequestMapping".equals(ann.getNameAsString())) {
            return mapHttpMethod(ann.getNameAsString());
        }
        List<String> methods = new ArrayList<>();
        if (ann instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if ("method".equals(pair.getNameAsString())) {
                    collectRequestMethodNames(pair.getValue(), methods);
                    break;
                }
            }
        } else if (ann instanceof SingleMemberAnnotationExpr single) {
            // @RequestMapping(POST) 静态导入简写（Spring 4.3+）；路径字符串形态不会命中
            collectRequestMethodNames(single.getMemberValue(), methods);
        }
        // 多值取第一个（风险清单 A2 约定）
        return methods.isEmpty() ? "ANY" : methods.get(0);
    }

    private void collectRequestMethodNames(com.github.javaparser.ast.expr.Expression expr, List<String> out) {
        if (expr instanceof com.github.javaparser.ast.expr.ArrayInitializerExpr arr) {
            for (var e : arr.getValues()) {
                collectRequestMethodNames(e, out);
            }
            return;
        }
        if (expr instanceof com.github.javaparser.ast.expr.FieldAccessExpr fae
                && "RequestMethod".equals(fae.getScope().toString())) {
            out.add(fae.getNameAsString());
        } else if (expr instanceof com.github.javaparser.ast.expr.NameExpr ne) {
            // 静态导入的 RequestMethod.POST 常以裸名出现
            String n = ne.getNameAsString();
            if (List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(n)) {
                out.add(n);
            }
        }
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

    private List<File> findJavaFiles(File backendDir, List<String> warnings) {
        List<File> all = new ArrayList<>();
        collectJavaFiles(backendDir, all);
        // v7.4(A1): 主循环统一排除 src/test 测试代码（此前仅依赖图与 LLM 源码收集排除，三处口径不一致，
        // 测试 fixture 的 Controller/实体/断言规则会污染 endpoints/enums/entities/businessRules）
        List<File> files = new ArrayList<>();
        int testExcluded = 0;
        for (File f : all) {
            String rel = relativize(backendDir, f);
            if (rel.startsWith("src/test/") || rel.contains("/src/test/")) {
                testExcluded++;
                continue;
            }
            files.add(f);
        }
        if (testExcluded > 0) {
            warnings.add("已排除 src/test 测试代码 " + testExcluded + " 个文件（不计入分析结果）");
        }
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
        // v7.4(A9): File.listFiles() 顺序依赖操作系统，统一按绝对路径字典序排序保证分析可复现
        // （子目录递归返回时排一次，顶层调用返回时全列表有序）
        result.sort(Comparator.comparing(File::getAbsolutePath));
    }
}
