"""Reuse passed app checks only when a validation run changes CI/docs, never app code."""
import json
import os
import subprocess


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True))


repo = os.environ["GITHUB_REPOSITORY"]
run_id = os.environ["VERIFIED_RUN"]
assert run_id.isdecimal(), "Expected a numeric verification run ID"
run = api(f"repos/{repo}/actions/runs/{run_id}")
assert run["repository"]["full_name"] == repo
assert run["status"] == "completed"
assert run["head_branch"] == os.environ["GITHUB_REF_NAME"]
assert run["head_sha"] != os.environ["GITHUB_SHA"]
comparison = api(f"repos/{repo}/compare/{run['head_sha']}...{os.environ['GITHUB_SHA']}")
assert comparison["status"] == "ahead", "Verified commit must be an ancestor"
assert 0 < len(comparison["files"]) < 300, "Compare output may be truncated"
allowed = {".github/workflows/android.yml", ".github/scripts/verify-stable-apk.py",
           ".github/scripts/reuse-app-verification.py", "docs/animeify-integration.md"}
assert all(file["filename"] in allowed and "previous_filename" not in file
           for file in comparison["files"]), "Application sources changed: run full tests"
jobs = api(f"repos/{repo}/actions/runs/{run_id}/jobs?filter=latest")
required = {"Tests and lint", "Android device smoke test"}
assert any(required <= {step["name"] for step in job["steps"]
                        if step["conclusion"] == "success"} for job in jobs["jobs"]), "App checks did not pass"
with open(os.environ["GITHUB_OUTPUT"], "a") as destination:
    destination.write("reused=true\n")
print(f"Reused passing unit/lint/emulator checks from run {run_id}; app sources are unchanged")
