# 构建上下文必须是仓库根（docker-compose 里 context: . / dockerfile: backend/Dockerfile），
# 因为 contracts/ 要一起进来：contracts/intranet-http.json 是后端与前端共用的判定规则真源
# （见 com.jh.knowledgeos.common.IntranetAddressRules），缺了它后端会退回内置兜底规则。
FROM maven:3.9.9-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY backend/pom.xml .
# 容器内没有宿主机的 ~/.m2/settings.xml，默认直连中央仓库（约 0.46MB/s）；
# 换成阿里云镜像后约 2MB/s，首次构建能省下大半时间。镜像源只对这一层生效。
COPY backend/docker-maven-settings.xml /root/.m2/settings.xml
RUN mvn -q -DskipTests dependency:go-offline
COPY backend/src ./src
# pom 把 ../contracts 挂成了资源目录，相对 /workspace 就是这里
COPY contracts ./contracts
RUN mvn -q -DskipTests package

FROM eclipse-temurin:8-jre
# mysql-client 供内置演示快照恢复使用（DEMO_SEED=auto 时只在业务表为空时执行一次）
RUN apt-get update && apt-get install -y --no-install-recommends curl default-mysql-client && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
# 出厂默认配置。bind/named volume 首次创建时 docker 会把镜像里这个目录的内容带进卷，
# 所以新部署拿到的是一份可编辑的文件；已有卷则靠 DefaultCatalogLoader 启动时播种补齐。
COPY --from=build /workspace/src/main/resources/defaults /app/config
# 演示快照：由 scripts/demo/export-snapshot.py 产出，可在部署后直接演示
COPY backend/demo-seed /opt/demo-seed
COPY backend/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
