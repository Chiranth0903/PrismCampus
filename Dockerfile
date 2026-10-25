FROM python:3.12-slim
WORKDIR /home/node/prism
COPY server/ server/
COPY app/src/main/assets/msrit_pack.json app/src/main/assets/msrit_pack.json
ENV HOST=0.0.0.0 PORT=8000 PRISM_DB_PATH=/home/node/prism/data/prism_campus.db
VOLUME ["/home/node/prism/data"]
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/health',timeout=2)"
CMD ["python", "server/uno_q_server.py"]
