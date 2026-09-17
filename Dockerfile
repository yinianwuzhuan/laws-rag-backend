# ---- 构建阶段 ----
FROM maven:3-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 配置 Maven 阿里云镜像，加速国内构建
COPY maven-settings.xml /root/.m2/settings.xml

# 先复制依赖描述文件，利用 Docker 层缓存，依赖未变时跳过下载
COPY pom.xml ./

RUN mvn dependency:go-offline -q

# 复制源码并打包，跳过测试
COPY eval ./eval
COPY src ./src
RUN mvn package -DskipTests -q

# ---- 运行阶段 ----
# ONNX Runtime 与 Hugging Face Tokenizer 的 Linux 原生库依赖 glibc，运行阶段不能使用 Alpine/musl。
FROM eclipse-temurin:21-jre

WORKDIR /app

# 从构建阶段复制 jar
COPY --from=builder /build/target/laws-rag-backend-*.jar app.jar

# 暴露端口
EXPOSE 8081

# SPRING_PROFILES_ACTIVE 可在 docker run 时通过 -e 传入，默认为 local
ENV SPRING_PROFILES_ACTIVE=local
ENV RERANKER_MODEL_PATH=/app/models/reranker

ENTRYPOINT ["java", "-jar", "app.jar"]

