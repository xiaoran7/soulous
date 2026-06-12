FROM python:3.13-slim

WORKDIR /srv/agent

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# 三个 sqlite 库（state/dal/rag）挂载卷
VOLUME ["/srv/agent/data"]
ENV AGENT_DATA_DIR=/srv/agent/data \
    AGENT_HOST=0.0.0.0 \
    AGENT_PORT=8100

EXPOSE 8100

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
