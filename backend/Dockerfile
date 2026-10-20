FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd -u 10001 -m fuguang
COPY app.py .
COPY static ./static
RUN mkdir /data && chown 10001:10001 /data
USER 10001:10001
ENV FUGUANG_DB=/data/fuguang.sqlite3
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log", "--proxy-headers", "--forwarded-allow-ips", "*"]
