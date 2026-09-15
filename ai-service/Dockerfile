FROM python:3.10-slim
ARG INSTALL_ML=false
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt requirements-ml.txt ./
RUN if [ "$INSTALL_ML" = "true" ]; then pip install --no-cache-dir -r requirements-ml.txt; else pip install --no-cache-dir -r requirements.txt; fi
COPY app ./app
RUN mkdir -p /app/data/indexes /app/data/uploads /app/model-cache
EXPOSE 12000
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","12000"]
