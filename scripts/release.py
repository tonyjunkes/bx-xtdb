#!/usr/bin/env python3
"""Publish the verified ZIP without moving tags or replacing released assets."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.request import urlopen
import zipfile


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{' '.join(args)} failed:\n{result.stderr}")
    return result.stdout.strip()


def forgebox_published(slug, version):
    try:
        with urlopen(f"https://www.forgebox.io/api/v1/entry/{slug}", timeout=30) as response:
            entry = json.load(response)
    except HTTPError as error:
        if error.code == 404:
            return False  # First publication: the entry does not exist yet.
        raise
    if entry.get("error") is not False or not isinstance(entry.get("data", {}).get("versions"), list):
        raise RuntimeError("Unexpected ForgeBox entry response; refusing to guess publication state")
    return any(item["version"] == version for item in entry["data"]["versions"])


def main():
    if os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise RuntimeError("Releases must run from main")
    head = os.environ["GITHUB_SHA"]
    repo = os.environ["GITHUB_REPOSITORY"]
    if run("git", "rev-parse", "HEAD") != head:
        raise RuntimeError("Checkout does not match the tested commit")
    run("git", "merge-base", "--is-ancestor", head, "origin/main")

    manifest = json.loads(Path("box.json").read_text())
    version, slug = manifest["version"], manifest["slug"]
    if slug != "bx-xtdb" or not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?", version):
        raise RuntimeError("Invalid release identity/version")
    tags = run("git", "tag", "--list", version)
    tagged = run("git", "rev-list", "-n", "1", version) if tags else None
    if tagged and tagged != head:
        run("git", "merge-base", "--is-ancestor", tagged, head)
        print(f"Version {version} was already tagged on an earlier main commit; skipping.")
        return

    archive = Path("build/distributions") / f"{slug}-{version}.zip"
    checksum = archive.with_suffix(".zip.sha256")
    expected = f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}"
    if checksum.read_text().strip() != expected:
        raise RuntimeError("Distribution checksum mismatch")
    published = forgebox_published(slug, version)
    if not published and not os.environ.get("FORGEBOX_API_TOKEN"):
        raise RuntimeError("Add FORGEBOX_API_TOKEN before releasing")
    releases = json.loads(run("gh", "release", "list", "--repo", repo, "--limit", "1000", "--json", "tagName,isDraft"))
    release = next((item for item in releases if item["tagName"] == version), None)

    with tempfile.TemporaryDirectory(prefix="bx-xtdb-release-") as temporary:
        package = Path(temporary) / "package"
        with zipfile.ZipFile(archive) as bundle:
            if json.loads(bundle.read("box.json")) != manifest:
                raise RuntimeError("Packaged metadata differs from the tested checkout")
            for entry in bundle.infolist():
                if not (package / entry.filename).resolve().is_relative_to(package.resolve()):
                    raise RuntimeError("Unsafe path in distribution")
            bundle.extractall(package)
        if not (package / "libs" / f"{slug}-{version}.jar").is_file():
            raise RuntimeError("Compiled module JAR is missing")
        if not tagged:
            run("git", "-c", "user.name=github-actions[bot]", "-c",
                "user.email=41898282+github-actions[bot]@users.noreply.github.com",
                "tag", "--annotate", version, "--message", f"Release {version}", head)
            run("git", "push", "origin", f"refs/tags/{version}")
        if release is None:
            flags = ["--prerelease"] if "-" in version else []
            run("gh", "release", "create", version, "--repo", repo, "--verify-tag", "--draft",
                "--title", f"{slug} {version}", "--generate-notes", *flags)
        assets = json.loads(run("gh", "release", "view", version, "--repo", repo, "--json", "assets"))["assets"]
        for asset in (archive, checksum):
            if any(item["name"] == asset.name for item in assets):
                existing = Path(temporary) / "existing"
                existing.mkdir(exist_ok=True)
                run("gh", "release", "download", version, "--repo", repo, "--pattern", asset.name, "--dir", str(existing))
                if (existing / asset.name).read_bytes() != asset.read_bytes():
                    raise RuntimeError(f"Existing release asset differs: {asset.name}; refusing to replace it")
            else:
                run("gh", "release", "upload", version, str(asset), "--repo", repo)
        # ForgeBox's location must be publicly downloadable before metadata publication.
        if release is None or release["isDraft"]:
            run("gh", "release", "edit", version, "--repo", repo, "--draft=false")
        if not published:
            # Do not include credential-bearing command arguments in failure messages.
            configured = subprocess.run(["box", "config", "set",
                f"endpoints.forgebox.APIToken={os.environ['FORGEBOX_API_TOKEN']}"], capture_output=True)
            if configured.returncode:
                raise RuntimeError("Unable to configure ForgeBox credentials")
            run("box", "forgebox", "publish", f"directory={package}")
        print(f"Release {version} is available on GitHub and ForgeBox.")


if __name__ == "__main__":
    main()
