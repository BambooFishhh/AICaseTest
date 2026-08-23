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
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
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
    /** v7.7(A4a): 控制器类级 @RequestMapping 前缀（含 value= 形态），供 supplementalEndpoints 校验 */
    private static final Pattern CLASS_REQUEST_MAPPING_PATTERN =
            Pattern.compile("@RequestMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

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
        // v7.6(A17): 状态字段赋值证据（规则层，零 LLM 成本）——setStateTransitions ground truth
        List<Map<String, Object>> stateTransitions = new ArrayList<>();
        // v7.6(G20层3): 后端异常用户消息字面量——错误→用户文案对照表
        List<Map<String, Object>> errorMessages = new ArrayList<>();

        List<String> parseFailures = new ArrayList<>();
        for (File javaFile : javaFiles) {
            String relativePath = relativize(dir, javaFile);
            try {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                endpoints.addAll(extractEndpoints(cu, relativePath));
                enums.addAll(extractEnums(cu, relativePath));
                entities.addAll(extractEntities(cu, relativePath));
                businessRules.addAll(extractBusinessRules(cu, relativePath, warnings));
                stateTransitions.addAll(extractStateTransitions(cu, relativePath));
                errorMessages.addAll(extractErrorMessages(cu, relativePath));
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

        // v7.6(A17/G20层3): 证据去重 + 上限（100/200 条）
        stateTransitions = dedupeByKeys(stateTransitions, List.of("field", "from", "to", "method"), 200,
                "状态转换证据", warnings);
        errorMessages = dedupeByKeys(errorMessages, List.of("exception", "message"), 100,
                "异常消息", warnings);

        enhanceWithLlmIfConfigured(dir, javaFiles, endpoints, enums, entities, businessRules, warnings);

        return BackendResult.builder()
                .techStack(techStack)
                .endpoints(endpoints)
                .enums(enums)
                .entities(entities)
                .businessRules(businessRules)
                .dependencyGraph(dependencyGraph)
                .stateTransitions(stateTransitions)
                .errorMessages(errorMessages)
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

    // v7.10(A3): 包级可见，供单测直接验证多异常收集语义
    List<EndpointInfo> extractEndpoints(CompilationUnit cu, String filePath) {
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
                    // v7.10(A3): 收集方法体内全部 throw 的异常类型（旧实现 findFirst 只看
                    // 第一个 throw 的第一个 new，多异常端点只暴露一个）；上限 5 防长方法撑爆上下文
                    List<String> exceptionTypes = new ArrayList<>();
                    for (ThrowStmt ts : method.findAll(ThrowStmt.class)) {
                        if (ts.getExpression() instanceof ObjectCreationExpr oce) {
                            String type = oce.getType().asString();
                            if (!exceptionTypes.contains(type) && exceptionTypes.size() < 5) {
                                exceptionTypes.add(type);
                            }
                        }
                    }
                    for (var thrown : method.getThrownExceptions()) {
                        String thrownName = thrown.asString();
                        if (!exceptionTypes.contains(thrownName) && exceptionTypes.size() < 5) {
                            exceptionTypes.add(thrownName);
                        }
                    }
                    // v7.7(A5): 规则层解析参数注解——@RequestParam/@PathVariable/@RequestBody
                    //（此前参数全押在看不全源码的 LLM 增强上，"关联 API + 测试数据"缺数据基础）
                    List<Map<String, Object>> parameters = extractParameters(method);
                    String requestBodyType = parameters.stream()
                            .filter(m -> "body".equals(m.get("in")))
                            .map(m -> String.valueOf(m.get("type")))
                            .findFirst().orElse(null);
                    endpoints.add(EndpointInfo.builder()
                            .method(httpMethod)
                            .path(fullPath)
                            .function(className + "." + method.getNameAsString())
                            .file(filePath)
                            .parameters(parameters)
                            .requestBody(requestBodyType)
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

    /**
     * v7.7(A5): 规则层参数提取——@RequestParam(in=query, required 缺省 true, defaultValue)、
     * @PathVariable(in=path)、@RequestBody(in=body 且类型写入 endpoint.requestBody)。
     * 注解 name/value 属性缺省时用参数名；不认识的注解参数跳过。
     */
    private List<Map<String, Object>> extractParameters(MethodDeclaration method) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            String type = p.getType().asString();
            AnnotationExpr requestParam = p.getAnnotationByName("RequestParam").orElse(null);
            AnnotationExpr pathVariable = p.getAnnotationByName("PathVariable").orElse(null);
            AnnotationExpr requestBody = p.getAnnotationByName("RequestBody").orElse(null);
            if (requestParam != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", annotationAttr(requestParam, null, p.getNameAsString()));
                m.put("in", "query");
                m.put("type", type);
                m.put("required", !"false".equalsIgnoreCase(annotationAttr(requestParam, "required", "true")));
                String defaultValue = annotationAttr(requestParam, "defaultValue", null);
                if (defaultValue != null) {
                    m.put("defaultValue", defaultValue);
                }
                parameters.add(m);
            } else if (pathVariable != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", annotationAttr(pathVariable, null, p.getNameAsString()));
                m.put("in", "path");
                m.put("type", type);
                m.put("required", true);
                parameters.add(m);
            } else if (requestBody != null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getNameAsString());
                m.put("in", "body");
                m.put("type", type);
                m.put("required", true);
                parameters.add(m);
            }
        }
        return parameters;
    }

    /** 注解属性取值：单值注解取 value/name 属性，Normal 注解按 attr 名取；缺省返回 fallback */
    private String annotationAttr(AnnotationExpr ann, String attr, String fallback) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            if (attr == null || "value".equals(attr) || "name".equals(attr)) {
                return stripQuotes(single.getMemberValue().toString());
            }
            return fallback;
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                String key = pair.getNameAsString();
                if (attr == null ? (key.equals("value") || key.equals("name")) : key.equals(attr)) {
                    return stripQuotes(pair.getValue().toString());
                }
            }
        }
        return fallback;  // MarkerAnnotationExpr（无属性）或未命中属性名
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

    // ==================== v7.6(A17): 状态转换证据提取（规则层） ====================

    /**
     * 扫描状态字段赋值点，从赋值方法上下文提取"转换来源→目标"证据：
     * - setStatus(X) / setStatus(EnumClass.X) / setXxxStatus(X) 调用
     * - status = X / this.orderStatus = X 直接赋值
     * from 取自同方法体内条件判断（getXxxStatus() == Y / Y.equals(getStatus()) / status == Y），
     * 无条件判断时标记 "*"（任意状态可达）。证据是状态机 transitions 的 ground truth（A17）。
     */
    List<Map<String, Object>> extractStateTransitions(CompilationUnit cu, String filePath) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        // 赋值点 1：setStatus(X) 形态的方法调用
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!isStateSetterName(name) || call.getArguments().isEmpty()) {
                continue;
            }
            String to = stateConstantOf(call.getArgument(0));
            if (to == null) {
                continue;
            }
            String field = setterToField(name);
            String method = call.findAncestor(MethodDeclaration.class)
                    .map(MethodDeclaration::getNameAsString).orElse("");
            collectEvidence(evidence, field, method, findFromStates(call, field), to, filePath);
        }
        // 赋值点 2：status = X 直接赋值
        for (AssignExpr assign : cu.findAll(AssignExpr.class)) {
            if (assign.getOperator() != AssignExpr.Operator.ASSIGN) {
                continue;
            }
            String field = statusFieldName(assign.getTarget());
            if (field == null) {
                continue;
            }
            String to = stateConstantOf(assign.getValue());
            if (to == null) {
                continue;
            }
            String method = assign.findAncestor(MethodDeclaration.class)
                    .map(MethodDeclaration::getNameAsString).orElse("");
            collectEvidence(evidence, field, method, findFromStates(assign, field), to, filePath);
        }
        return evidence;
    }

    private void collectEvidence(List<Map<String, Object>> evidence, String field, String method,
                                 Set<String> froms, String to, String filePath) {
        List<String> fromList = froms.isEmpty() ? List.of("*") : new ArrayList<>(froms);
        for (String from : fromList) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("field", field);
            ev.put("from", from);
            ev.put("to", to);
            ev.put("method", method);
            ev.put("file", filePath);
            evidence.add(ev);
        }
    }

    /**
     * setStatus / setXxxStatus 判定（setter 名以 set 开头且以 Status 结尾，忽略大小写）
     * v7.10(A18): 扩展到 state/type 结尾——order.type / userType / paymentType 是常见状态承载字段
     */
    private boolean isStateSetterName(String name) {
        if (name == null || name.length() <= 3 || !name.startsWith("set")) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith("status") || lower.endsWith("state") || lower.endsWith("type");
    }

    private String setterToField(String setterName) {
        String rest = setterName.substring(3);
        return rest.isEmpty() ? "status"
                : Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
    }

    /** 赋值目标是否状态字段（status / state / type 类字段名），是则返回字段名 */
    private String statusFieldName(Expression target) {
        if (target instanceof NameExpr ne) {
            return isStateFieldName(ne.getNameAsString()) ? ne.getNameAsString() : null;
        }
        if (target instanceof FieldAccessExpr fae) {
            return isStateFieldName(fae.getNameAsString()) ? fae.getNameAsString() : null;
        }
        return null;
    }

    /** v7.10(A18): 状态承载字段名——status/state/type 任一子串（原仅 status） */
    private boolean isStateFieldName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("status") || lower.contains("state") || lower.contains("type");
    }

    /**
     * 解析枚举常量表达式：PAID / OrderStatus.PAID → "PAID"；变量/方法调用/构造 → null（无法静态确定）。
     * v7.10(A18): 新增 Integer/String 字面量形态——setStatus(2) / setStatus("PAID") 此前提不出
     * （非全大写标识符），Integer/魔法数状态字段的状态赋值证据全丢。
     * 字面量误报由 isStateFieldName 门槛（目标字段含 status/state/type）拦住大半。
     */
    private String stateConstantOf(Expression expr) {
        if (expr instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            return looksLikeEnumConstant(name) ? name : null;
        }
        if (expr instanceof FieldAccessExpr fae) {
            String name = fae.getNameAsString();
            return looksLikeEnumConstant(name) ? name : null;
        }
        if (expr instanceof IntegerLiteralExpr ile) {
            return ile.getValue();
        }
        if (expr instanceof StringLiteralExpr sle) {
            return sle.getValue();
        }
        return null;
    }

    /** 枚举常量命名约定：全大写 + 下划线（如 PAID / STATUS_PAID） */
    private boolean looksLikeEnumConstant(String name) {
        return name != null && !name.isBlank() && name.equals(name.toUpperCase())
                && name.matches("[A-Z][A-Z0-9_]*");
    }

    /**
     * 从赋值点向上找最近的状态来源条件（内层 IfStmt 优先）：
     * if (getStatus() == Y) { ... setStatus(X) } / if (Y.equals(order.getStatus())) { ... }
     * 只取赋值点所在分支链上的条件（整个方法扫描会把兄弟分支的条件误记为来源）；
     * 无命中表示无条件（* 任意可达）。
     */
    private Set<String> findFromStates(com.github.javaparser.ast.Node node, String field) {
        Set<String> froms = new LinkedHashSet<>();
        com.github.javaparser.ast.Node current = node;
        while (current != null) {
            com.github.javaparser.ast.Node parent = current.getParentNode().orElse(null);
            if (parent instanceof IfStmt ifStmt) {
                froms.addAll(statusConstantsIn(ifStmt.getCondition(), field));
                if (!froms.isEmpty()) {
                    return froms;  // 内层条件已能定位来源
                }
            }
            current = parent;
        }
        return froms;
    }

    /** 条件表达式中出现的状态常量（== 比较 / equals 调用两种形态） */
    private Set<String> statusConstantsIn(Expression condition, String field) {
        Set<String> constants = new LinkedHashSet<>();
        for (BinaryExpr be : condition.findAll(BinaryExpr.class)) {
            if (be.getOperator() != BinaryExpr.Operator.EQUALS) {
                continue;
            }
            String left = stateConstantOf(be.getLeft());
            String right = stateConstantOf(be.getRight());
            if (left != null && isStatusRead(be.getRight(), field)) {
                constants.add(left);
            } else if (right != null && isStatusRead(be.getLeft(), field)) {
                constants.add(right);
            }
        }
        for (MethodCallExpr call : condition.findAll(MethodCallExpr.class)) {
            if (!"equals".equals(call.getNameAsString()) || call.getArguments().size() != 1) {
                continue;
            }
            String scopeConst = call.getScope().map(this::stateConstantOf).orElse(null);
            String argConst = stateConstantOf(call.getArgument(0));
            if (scopeConst != null && isStatusRead(call.getArgument(0), field)) {
                constants.add(scopeConst);
            } else if (argConst != null && call.getScope().isPresent()
                    && isStatusRead(call.getScope().get(), field)) {
                constants.add(argConst);
            }
        }
        return constants;
    }

    /** 表达式是否状态读取：getXxxStatus() 等状态 getter 调用 / 状态字段名标识符 */
    private boolean isStatusRead(Expression expr, String field) {
        if (expr instanceof MethodCallExpr mc) {
            String name = mc.getNameAsString().toLowerCase();
            return name.startsWith("get") && (name.endsWith("status") || name.endsWith("state") || name.endsWith("type"));
        }
        if (expr instanceof NameExpr ne) {
            return ne.getNameAsString().equals(field) || isStateFieldName(ne.getNameAsString());
        }
        if (expr instanceof FieldAccessExpr fae) {
            return fae.getNameAsString().equals(field) || isStateFieldName(fae.getNameAsString());
        }
        return false;
    }

    // ==================== v7.6(G20层3): 异常用户消息提取 ====================

    /**
     * 提取 throw new XxxException("用户可读消息") 的 message 字面量——
     * 与前端 ElMessage 文案合成"错误→用户文案"对照表，供生成侧写 UI 现象形 expected。
     * 仅收纯字符串字面量（含字面量拼接），message 长度 2~100。
     */
    List<Map<String, Object>> extractErrorMessages(CompilationUnit cu, String filePath) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ThrowStmt ts : cu.findAll(ThrowStmt.class)) {
            if (!(ts.getExpression() instanceof ObjectCreationExpr oce)) {
                continue;
            }
            String literal = stringLiteralOf(oce.getArguments().isEmpty() ? null : oce.getArgument(0));
            if (literal == null || literal.length() < 2 || literal.length() > 100) {
                continue;
            }
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("exception", oce.getType().asString());
            msg.put("message", literal);
            msg.put("file", filePath);
            messages.add(msg);
        }
        return messages;
    }

    /** 解析字符串字面量（含纯字面量拼接）；含变量则返回 null */
    private String stringLiteralOf(Expression expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof StringLiteralExpr sle) {
            return sle.getValue();
        }
        if (expr instanceof BinaryExpr be && be.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = stringLiteralOf(be.getLeft());
            String right = stringLiteralOf(be.getRight());
            return (left != null && right != null) ? left + right : null;
        }
        return null;
    }

    /** v7.6: 按 key 集合去重并限制条数，超限记 warning */
    private List<Map<String, Object>> dedupeByKeys(List<Map<String, Object>> items, List<String> keys,
                                                   int limit, String label, List<String> warnings) {
        if (items.size() <= 1) {
            return items;
        }
        List<Map<String, Object>> deduped = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            String key = keys.stream().map(k -> String.valueOf(item.get(k))).reduce((a, b) -> a + "|" + b).orElse("");
            if (seen.add(key)) {
                deduped.add(item);
            }
        }
        if (deduped.size() > limit) {
            warnings.add(label + "超上限 " + deduped.size() + " 条，截断为 " + limit + " 条");
            return new ArrayList<>(deduped.subList(0, limit));
        }
        return deduped;
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

    /**
     * v7.10(A6): 业务规则只收录业务语义异常——过滤 JDK/Spring 通用异常（空指针防御、参数断言）。
     * 旧实现所有含 throw 的 if 都算规则：NPE 防御/参数断言是代码卫生不是业务规则，
     * 收录后挤占 rule-N 覆盖清单，LLM 被迫为"传 null 报错"生成无价值用例。
     * 过滤量进 warnings 可观测；LLM 增强仍可补规则（supplementalBusinessRules 不受限）。
     */
    // v7.10(A6): 包级可见，供单测直接验证噪音异常过滤语义
    List<BusinessRule> extractBusinessRules(CompilationUnit cu, String filePath, List<String> warnings) {
        List<BusinessRule> rules = new ArrayList<>();
        int filtered = 0;
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            String methodName = method.getNameAsString();
            for (IfStmt ifStmt : method.findAll(IfStmt.class)) {
                String ifText = ifStmt.toString();
                if (!ifText.contains("throw") || !ifText.contains("new")) {
                    continue;
                }
                String condition = truncate(ifStmt.getCondition().toString(), 200);
                List<ObjectCreationExpr> creations = ifStmt.findAll(ObjectCreationExpr.class);
                List<ObjectCreationExpr> businessCreations = new ArrayList<>();
                for (ObjectCreationExpr creation : creations) {
                    if (NOISE_EXCEPTIONS.contains(creation.getType().asString())) {
                        filtered++;
                    } else {
                        businessCreations.add(creation);
                    }
                }
                if (creations.isEmpty()) {
                    rules.add(BusinessRule.builder()
                            .file(filePath)
                            .function(methodName)
                            .rule("if (" + condition + ") then throw")
                            .ruleType("throw_exception")
                            .sources(List.of("rules"))
                            .build());
                } else {
                    for (ObjectCreationExpr creation : businessCreations) {
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
        if (filtered > 0) {
            warnings.add("已过滤 " + filtered + " 条 JDK/Spring 通用异常规则（空指针防御/参数断言，非业务规则）");
        }
        return rules;
    }

    /** v7.10(A6): 通用异常黑名单——代码卫生型 throw，不构成业务规则 */
    private static final Set<String> NOISE_EXCEPTIONS = Set.of(
            "NullPointerException", "IllegalStateException",
            "IllegalArgumentException", "AssertionError",
            "UnsupportedOperationException", "RuntimeException");

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
            enhanceWithLlm(dir, javaFiles, endpoints, enums, entities, businessRules, warnings);
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
                                List<BusinessRule> businessRules,
                                List<String> warnings) {
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
        // v7.7(A4a): supplementalEndpoints 源码存在性校验证据——LLM 只见约 10 个文件片段，
        // 补充接口无校验直接入库等于鼓励编造；类名集合 + 控制器类级路径前缀作为闸门
        Set<String> knownClasses = new LinkedHashSet<>();
        Set<String> knownPathPrefixes = new LinkedHashSet<>();
        for (File file : javaFiles) {
            String name = file.getName();
            knownClasses.add(name.endsWith(".java") ? name.substring(0, name.length() - 5) : name);
            if (name.toLowerCase().contains("controller")) {
                collectControllerPathPrefix(file, knownPathPrefixes);
            }
        }
        parseAndMergeSupplements(response, endpoints, enums, entities, businessRules,
                knownClasses, knownPathPrefixes, warnings);
    }

    /** 控制器类级 @RequestMapping 前缀提取：@RequestMapping("/api/order") / @RequestMapping(value = "/api/order") */
    private void collectControllerPathPrefix(File file, Set<String> prefixes) {
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Matcher m = CLASS_REQUEST_MAPPING_PATTERN.matcher(content);
            while (m.find()) {
                String prefix = m.group(1).trim();
                // v7.7(A4a): 单段前缀（如 /api、/v1）过于宽泛，几乎任何路径都能命中，
                // 不能作为存在性证据——只收 ≥2 段的前缀
                if (!prefix.isEmpty() && prefix.indexOf('/', 1) > 0) {
                    prefixes.add(prefix);
                }
            }
        } catch (IOException e) {
            // 读取失败忽略——校验退化为仅类名匹配
        }
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
                                          List<BusinessRule> businessRules,
                                          Set<String> knownClasses,
                                          Set<String> knownPathPrefixes,
                                          List<String> warnings) {
        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(response));
        } catch (Exception e) {
            log.warn("Failed to parse backend LLM supplement JSON: {}", e.getMessage());
            return;
        }
        mergeEndpointEnhancements(root.path("endpointEnhancements"), endpoints);
        mergeSupplementalEndpoints(root.path("supplementalEndpoints"), endpoints,
                knownClasses, knownPathPrefixes, warnings);
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

    private void mergeSupplementalEndpoints(JsonNode nodes, List<EndpointInfo> endpoints,
                                            Set<String> knownClasses, Set<String> knownPathPrefixes,
                                            List<String> warnings) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String method = node.path("method").asText("").trim();
            String path = normalizePath(node.path("path").asText(""));
            if (method.isEmpty() || path.isEmpty() || findEndpoint(endpoints, node) != null) {
                continue;
            }
            // v7.7(A4a): 源码存在性校验——function 含已知类名，或 path 以已知控制器前缀开头，
            // 任一通过才收（看不全源码还要求"如实补充"的 LLM 输出不可直接信任）
            String function = node.path("function").asText("").trim();
            boolean classMatch = !function.isBlank()
                    && knownClasses.stream().anyMatch(function::contains);
            boolean prefixMatch = knownPathPrefixes.stream().anyMatch(path::startsWith);
            if (!classMatch && !prefixMatch) {
                if (warnings.size() < 50) {
                    warnings.add("LLM 补充接口未通过源码校验已丢弃: " + method.toUpperCase() + " " + path);
                }
                log.warn("LLM supplemental endpoint dropped (no source evidence): {} {}", method, path);
                continue;
            }
            EndpointInfo endpoint = EndpointInfo.builder()
                    .method(method.toUpperCase())
                    .path(path)
                    .function(function)
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
