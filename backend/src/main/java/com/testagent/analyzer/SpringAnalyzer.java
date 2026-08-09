package com.testagent.analyzer;

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
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SpringAnalyzer {

    private static final Pattern SPRING_BOOT_PARENT_VERSION_PATTERN =
            Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern SPRING_BOOT_VERSION_PROPERTY_PATTERN =
            Pattern.compile("<spring-boot\\.version>([^<]+)</spring-boot\\.version>");

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
                                .build());
                    }
                }
            }
        }
        return rules;
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
