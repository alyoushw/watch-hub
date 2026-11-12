"""Block a main-channel release unless it can update the published Android app."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def output(*args):
    return subprocess.check_output(args, text=True)


def tool(name):
    root = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    candidates = list(root.glob("*/" + name))
    assert candidates, f"Missing Android tool: {name}"
    return str(sorted(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)))[-1])


def identity(apk):
    badging = output(tool("aapt"), "dump", "badging", str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    assert package, "Cannot read APK package/version"
    signing = output(tool("apksigner"), "verify", "--print-certs", str(apk))
    certificates = sorted(re.findall(r"Signer #\d+ certificate SHA-256 digest: (\S+)", signing))
    assert certificates, "APK has no verified signing certificate"
    return package[1], int(package[2]), certificates


def main():
    release = json.loads(output("gh", "api", "repos/alyoushw/watch-hub/releases/latest"))
    assert not release["draft"] and not release["prerelease"]
    assert re.fullmatch(r"build-\d+", release["tag_name"]), "Unexpected main-channel release"
    assert any(a["name"] == "WatchHub.apk" for a in release["assets"])
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run(["gh", "release", "download", release["tag_name"], "--repo",
                        "alyoushw/watch-hub", "--pattern", "WatchHub.apk", "--dir", directory], check=True)
        previous = identity(Path(directory) / "WatchHub.apk")
        current = identity(Path(sys.argv[1]))
    assert previous[0] == current[0] == "com.alyoushw.watchhub", "Main application ID changed"
    assert previous[2] == current[2], "Signing certificate changed: update would lose compatibility"
    assert current[1] > previous[1], "Version code must increase for an in-place update"
    print(f"Verified main app ID, matching signing certificate and versionCode {previous[1]} -> {current[1]}")


if __name__ == "__main__":
    main()
