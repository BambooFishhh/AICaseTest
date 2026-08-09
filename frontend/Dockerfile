# ========== 阶段1：Node 构建 ==========
FROM node:20-alpine AS builder

WORKDIR /build

# 利用 Docker 缓存，先安装依赖
COPY package.json ./
RUN npm config set registry https://registry.npmmirror.com && npm install

# 复制源码并构建
COPY . .
RUN npm run build

# ========== 阶段2：Nginx 运行 ==========
FROM nginx:1.25-alpine

# 复制构建产物
COPY --from=builder /build/dist /usr/share/nginx/html

# 复制 Nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
