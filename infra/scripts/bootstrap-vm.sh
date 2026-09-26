#!/usr/bin/env bash
# Oracle Cloud Ubuntu 24.04 (ARM) VM에서 한 번 실행한다. 사용자: ubuntu
set -euo pipefail

# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"

# 백업 업로드 도구
sudo apt-get update && sudo apt-get install -y rclone

# Oracle Ubuntu 이미지는 iptables가 80/443을 막고 있다. VCN 보안 목록과 별개로 열어야 한다.
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save

# 배포 디렉터리
sudo mkdir -p /opt/ogu && sudo chown "$USER":"$USER" /opt/ogu
git clone https://github.com/hyunolike/5959.git /opt/ogu/repo
cp /opt/ogu/repo/infra/.env.example /opt/ogu/.env
chmod 600 /opt/ogu/.env

echo "다음: /opt/ogu/.env를 채운다. 첫 배포는 Release 워크플로가 한다(specs/001-foundation/quickstart.md 6번)."
