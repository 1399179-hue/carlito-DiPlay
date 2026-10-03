#!/usr/bin/env python3
"""Install the separate profile intake on the existing Synology cloud host."""
import datetime
import os
import pwd
import re
import shutil
import subprocess
from pathlib import Path

APP = Path("/volume1/homes/linecode/codex-cache/diplay-profiles")
NGINX = Path("/usr/local/etc/nginx/sites-enabled/geely-auth-standalone-5214.conf")
UNIT = Path("/etc/systemd/system/diplay-profiles.service")
MARKER = "# DIPLAY_STEERING_PROFILES"
LOCATION = """    # DIPLAY_STEERING_PROFILES
    location /diplay-profiles/ {
        client_max_body_size 1m;
        proxy_pass http://127.0.0.1:18794;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_connect_timeout 5s;
        proxy_read_timeout 20s;
    }
"""
SERVICE = """[Unit]
Description=DiPlay steering profile intake
After=network.target

[Service]
Type=simple
User=linecode
Group=users
WorkingDirectory=/volume1/homes/linecode/codex-cache/diplay-profiles
Environment=DIPLAY_PROFILES_DATA=/volume1/homes/linecode/codex-cache/diplay-profiles/data/profiles
Environment=DIPLAY_REPORTS_DATA=/volume1/homes/linecode/codex-cache/diplay-profiles/data/reports
Environment=DIPLAY_PROFILES_PORT=18794
ExecStart=/usr/bin/python3 /volume1/homes/linecode/codex-cache/diplay-profiles/server.py
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
"""


def main():
    if os.geteuid() != 0 or not (APP / "server.py").is_file():
        raise SystemExit("Upload the server and run this installer as administrator")
    owner = pwd.getpwnam("linecode")
    for directory in (APP, APP / "data", APP / "data" / "profiles", APP / "data" / "reports"):
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chown(directory, owner.pw_uid, owner.pw_gid)
        directory.chmod(0o700)
    UNIT.write_text(SERVICE, encoding="utf-8")
    UNIT.chmod(0o644)
    original = NGINX.read_text(encoding="utf-8")
    backup = None
    if MARKER not in original:
        target = None
        for match in re.finditer(r"\bserver\s*\{", original):
            depth, end = 1, match.end()
            while end < len(original) and depth:
                depth += (original[end] == "{") - (original[end] == "}")
                end += 1
            block = original[match.start():end]
            if re.search(r"\blisten\s+[^;]*\b5214\b[^;]*\bssl\b", block):
                target = end - 1
                break
        if target is None:
            raise SystemExit("Existing HTTPS cloud server on port 5214 was not found")
        backups = APP.parent / "nginx-backups"
        backups.mkdir(exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
        backup = backups / (NGINX.name + ".diplay-profiles." + stamp)
        shutil.copy2(NGINX, backup)
        NGINX.write_text(original[:target] + LOCATION + original[target:], encoding="utf-8")
    else:
        marker = original.index(MARKER)
        suffix = original[marker:]
        updated = re.sub(r"client_max_body_size\s+[^;]+;", "client_max_body_size 1m;", suffix, count=1)
        if updated != suffix:
            backups = APP.parent / "nginx-backups"
            backups.mkdir(exist_ok=True)
            stamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
            backup = backups / (NGINX.name + ".diplay-profiles." + stamp)
            shutil.copy2(NGINX, backup)
            NGINX.write_text(original[:marker] + updated, encoding="utf-8")
    try:
        subprocess.run(["/usr/bin/nginx", "-t"], check=True)
        subprocess.run(["/usr/bin/systemctl", "daemon-reload"], check=True)
        subprocess.run(["/usr/bin/systemctl", "enable", "diplay-profiles.service"], check=True)
        subprocess.run(["/usr/bin/systemctl", "restart", "diplay-profiles.service"], check=True)
        subprocess.run(["/usr/bin/systemctl", "is-active", "--quiet", "diplay-profiles.service"], check=True)
        subprocess.run(["/usr/bin/nginx", "-s", "reload"], check=True)
    except Exception:
        if backup:
            shutil.copy2(backup, NGINX)
            subprocess.run(["/usr/bin/nginx", "-s", "reload"], check=False)
        raise
    print("DiPlay profile intake is active; data: " + str(APP / "data" / "profiles"))


if __name__ == "__main__":
    main()
