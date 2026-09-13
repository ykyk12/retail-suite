# 后端镜像：builder 里出 jar，runtime 只带 JRE
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
# 先只拉依赖：源码改动时依然能命中依赖层缓存
RUN mvn -B -ntp -q dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/target/retail-suite-*.jar /app/retail-suite.jar

# 生产 profile：连 MySQL/Redis（由 compose 提供），关闭 SQL 打印
ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=Asia/Shanghai

EXPOSE 8080
# 健康检查走 actuator，容器编排据此判断"能接流量"
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/retail-suite.jar"]
