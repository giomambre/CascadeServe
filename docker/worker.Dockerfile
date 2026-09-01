FROM python:3.12-slim

WORKDIR /app
COPY proto ./proto
COPY worker ./worker
WORKDIR /app/worker
ARG WORKER_EXTRAS=dev
RUN python -m pip install --no-cache-dir ".[${WORKER_EXTRAS}]" && python scripts/generate_proto.py

ENV PYTHONUNBUFFERED=1
EXPOSE 50052
ENTRYPOINT ["python", "-m", "cascadeserve_worker.server"]
