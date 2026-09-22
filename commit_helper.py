import os, sys, subprocess

pat = os.environ.get("GITHUB_PAT", "")
workdir = r"H:\GitHub\NyaaVoiceBridge"

p1 = subprocess.run("git add .", cwd=workdir, capture_output=True, text=True, shell=True)
p2 = subprocess.run('git commit -m "feat: init NyaaVoiceBridge P1 project scaffold, documentation and blueprints"', cwd=workdir, capture_output=True, text=True, shell=True)
push_cmd = f'git -c credential.helper= -c "url.https://x-access-token:{pat}@github.com/.insteadOf=https://github.com/" push origin main'
p3 = subprocess.run(push_cmd, cwd=workdir, capture_output=True, text=True, shell=True)
with open(r"H:\GitHub\NyaaVoiceBridge\commit_log.txt", "w", encoding="utf-8") as f:
    f.write(f"Add:\n{p1.stdout}\n{p1.stderr}\nCommit:\n{p2.stdout}\n{p2.stderr}\nPush:\n{p3.stdout}\n{p3.stderr}\nCode: {p3.returncode}")
