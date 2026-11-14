"""Reuse passed app checks only when a validation run changes CI/docs/test fixtures, never app code."""
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
test_prefix = "app/src/androidTest/"
assert all((file["filename"] in allowed or file["filename"].startswith(test_prefix)) and "previous_filename" not in file
           for file in comparison["files"]), "Application sources changed: run full tests"
jobs = api(f"repos/{repo}/actions/runs/{run_id}/jobs?filter=latest")
passed = [{step["name"] for step in job["steps"] if step["conclusion"] == "success"} for job in jobs["jobs"]]
assert any("Tests and lint" in steps for steps in passed), "Unit/lint checks did not pass"
tests_changed = any(file["filename"].startswith(test_prefix) for file in comparison["files"])
emulator_reused = not tests_changed and any({"Tests and lint", "Android device smoke test"} <= steps for steps in passed)
with open(os.environ["GITHUB_OUTPUT"], "a") as destination:
    destination.write("unit_lint_reused=true\n")
    destination.write("reused=" + str(emulator_reused).lower() + "\n")
print(f"Reused passing unit/lint checks from run {run_id}; application sources are unchanged")
print("Emulator checks: " + ("reused" if emulator_reused else "must run again"))
