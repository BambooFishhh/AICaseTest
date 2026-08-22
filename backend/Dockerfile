# ========== 阶段1：Maven 构建 ==========
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 配置阿里云 Maven 镜像加速
RUN mkdir -p /root/.m2 && echo '<?xml version="1.0" encoding="UTF-8"?><settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><name>Aliyun</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml

# 先复制 pom.xml，利用 Docker 缓存加速依赖下载
COPY backend/pom.xml .
RUN mvn dependency:go-offline -B

# 复制源码并构建
COPY backend/src ./src
# MCP Server 源码（llm + playwright）
COPY mcp-server ./mcp-server
COPY playwright-mcp-server ./playwright-mcp-server
COPY tools-mcp-server ./tools-mcp-server
RUN mvn package -DskipTests -B

# ========== 阶段2：运行 ==========
FROM eclipse-temurin:17-jre

WORKDIR /app

# 安装 curl（healthcheck）+ Node.js 22（MCP Server 运行时，apt 默认 node 版本过低）
# 使用国内镜像加速：apt 阿里云 / Node npmmirror
RUN sed -i 's|archive.ubuntu.com|mirrors.aliyun.com|g; s|security.ubuntu.com|mirrors.aliyun.com|g' /etc/apt/sources.list \
    && apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils git \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL https://npmmirror.com/mirrors/node/v22.14.0/node-v22.14.0-linux-x64.tar.xz \
        | tar -xJ -C /usr/local --strip-components=1

# 复制构建产物
COPY --from=builder /build/target/*.jar app.jar
# 复制 MCP Server 并安装依赖 + Playwright Chromium 浏览器
COPY --from=builder /build/mcp-server ./mcp-server
COPY --from=builder /build/playwright-mcp-server ./playwright-mcp-server
COPY --from=builder /build/tools-mcp-server ./tools-mcp-server
ENV PLAYWRIGHT_DOWNLOAD_HOST=https://npmmirror.com/mirrors/playwright
RUN cd playwright-mcp-server \
    && npm install --registry=https://registry.npmmirror.com \
    && npx playwright install --with-deps chromium
RUN cd mcp-server && npm install --registry=https://registry.npmmirror.com
RUN cd tools-mcp-server && npm install --registry=https://registry.npmmirror.com

# 创建数据目录
RUN mkdir -p /app/data /app/outputs /app/projects

EXPOSE 8000

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
