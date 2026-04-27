#!/bin/bash
set -euxo pipefail

LOG_FILE="/var/log/aura-user-data.log"
exec > >(tee -a "${LOG_FILE}") 2>&1

# Install Java runtime for Spring Boot jars.
dnf update -y || true
dnf install -y java-17-amazon-corretto-headless

# Create app directories expected for backend and llm jars.
mkdir -p /opt/aura-backend /opt/aura-llm /var/log/aura
chown -R ec2-user:ec2-user /opt/aura-backend /opt/aura-llm /var/log/aura

# Helper scripts resolve the newest JAR at runtime.
cat >/usr/local/bin/start-aura-backend.sh <<'EOF'
#!/bin/bash
set -euo pipefail
JAR_PATH="$(ls -1t /opt/aura-backend/*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "${JAR_PATH}" ]]; then
  echo "No backend JAR found in /opt/aura-backend" >&2
  exit 1
fi
exec /usr/bin/java -jar "${JAR_PATH}"
EOF

cat >/usr/local/bin/start-aura-llm.sh <<'EOF'
#!/bin/bash
set -euo pipefail
JAR_PATH="$(ls -1t /opt/aura-llm/*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "${JAR_PATH}" ]]; then
  echo "No LLM JAR found in /opt/aura-llm" >&2
  exit 1
fi
exec /usr/bin/java -jar "${JAR_PATH}"
EOF

chmod +x /usr/local/bin/start-aura-backend.sh /usr/local/bin/start-aura-llm.sh

# Systemd unit for backend service.
cat >/etc/systemd/system/aura-backend.service <<'EOF'
[Unit]
Description=Aura Backend Service
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/opt/aura-backend
ExecStart=/usr/local/bin/start-aura-backend.sh
Restart=always
RestartSec=10
StandardOutput=append:/var/log/aura/backend.log
StandardError=append:/var/log/aura/backend-error.log

[Install]
WantedBy=multi-user.target
EOF

# Systemd unit for LLM middleware service.
cat >/etc/systemd/system/aura-llm.service <<'EOF'
[Unit]
Description=Aura LLM Middleware Service
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/opt/aura-llm
ExecStart=/usr/local/bin/start-aura-llm.sh
Restart=always
RestartSec=10
StandardOutput=append:/var/log/aura/llm.log
StandardError=append:/var/log/aura/llm-error.log

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable aura-backend.service aura-llm.service
systemctl restart aura-backend.service || true
systemctl restart aura-llm.service || true
