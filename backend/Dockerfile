FROM maven:3.9.9-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY pom.xml .
# 容器内没有宿主机的 ~/.m2/settings.xml，默认直连中央仓库（约 0.46MB/s）；
# 换成阿里云镜像后约 2MB/s，首次构建能省下大半时间。镜像源只对这一层生效。
COPY docker-maven-settings.xml /root/.m2/settings.xml
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:8-jre
# mysql-client 供内置演示快照恢复使用（DEMO_SEED=auto 时只在业务表为空时执行一次）
RUN apt-get update && apt-get install -y --no-install-recommends curl default-mysql-client && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
# 演示快照：由 scripts/demo/export-snapshot.py 产出，可在部署后直接演示
COPY demo-seed /opt/demo-seed
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
