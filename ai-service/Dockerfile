FROM python:3.10-slim
ARG INSTALL_ML=false
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt requirements-ml.txt ./
RUN if [ "$INSTALL_ML" = "true" ]; then pip install --no-cache-dir -r requirements-ml.txt; else pip install --no-cache-dir -r requirements.txt; fi
COPY app ./app
# 出厂默认配置随镜像发布（进版本库）。运行时的那份在 /app/data（持久卷），首次启动由
# query_policy 从这里播种过去——卷一旦存在就不会被新镜像覆盖，只放镜像里是到不了的。
COPY config ./config
RUN mkdir -p /app/data/indexes /app/data/uploads /app/model-cache
EXPOSE 12000
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","12000"]
