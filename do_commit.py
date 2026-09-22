import os
import sys
import subprocess

pat = os.environ.get("GITHUB_PAT", "")
workdir = r"H:\GitHub\NyaaVoiceBridge"

def run(cmd):
    p = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True, shell=True)
    print(f">> {cmd}\ncode: {p.returncode}\nstdout: {p.stdout.strip()}\nstderr: {p.stderr.strip()}\n")
    return p

run("git status")
run("git add .")
run('git commit -m "feat: init NyaaVoiceBridge P1 project scaffold, documentation and blueprints"')

push_cmd = f'git -c credential.helper= -c "url.https://x-access-token:{pat}@github.com/.insteadOf=https://github.com/" push origin main'
p_push = subprocess.run(push_cmd, cwd=workdir, capture_output=True, text=True, shell=True)
print("Push stdout:", p_push.stdout)
print("Push stderr:", p_push.stderr)
print("Push exit code:", p_push.returncode)

# 无论如何删除脚本自身
try:
    os.remove(__file__)
except Exception:
    pass
