"""Exercise publication recovery without contacting registries or changing tags."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
import zipfile

spec = importlib.util.spec_from_file_location("release", Path(__file__).parents[2] / "scripts/release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        previous = Path.cwd()
        os.chdir(self.temporary.name)
        self.addCleanup(os.chdir, previous)
        self.manifest = {"slug": "bx-xtdb", "version": "0.1.0"}
        Path("box.json").write_text(json.dumps(self.manifest))
        Path("build/distributions").mkdir(parents=True)
        self.archive = Path("build/distributions/bx-xtdb-0.1.0.zip")
        with zipfile.ZipFile(self.archive, "w") as bundle:
            bundle.writestr("box.json", json.dumps(self.manifest))
            bundle.writestr("libs/bx-xtdb-0.1.0.jar", b"fixture")
        self.archive.with_suffix(".zip.sha256").write_text(
            f"{hashlib.sha256(self.archive.read_bytes()).hexdigest()}  {self.archive.name}\n")
        self.tag, self.releases, self.assets = "", [], []
        self.commands = []
        self.env = patch.dict(os.environ, {"GITHUB_REF": "refs/heads/main", "GITHUB_SHA": "head",
            "GITHUB_REPOSITORY": "tonyjunkes/bx-xtdb", "FORGEBOX_API_TOKEN": "fixture"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def command(self, *args):
        self.commands.append(args)
        if args[:3] == ("git", "rev-parse", "HEAD"): return "head"
        if args[:3] == ("git", "tag", "--list"): return "0.1.0" if self.tag else ""
        if args[:2] == ("git", "rev-list"): return self.tag
        if args[:3] == ("gh", "release", "list"): return json.dumps(self.releases)
        if args[:3] == ("gh", "release", "view"): return json.dumps({"assets": self.assets})
        if args[:3] == ("gh", "release", "download"):
            name = args[args.index("--pattern") + 1]
            directory = Path(args[args.index("--dir") + 1])
            (directory / name).write_bytes(Path("build/distributions", name).read_bytes())
        return ""

    def publish(self, published=False):
        with patch.object(release, "run", side_effect=self.command), \
                patch.object(release, "forgebox_published", return_value=published), \
                patch.object(release.subprocess, "run") as configure:
            configure.return_value.returncode = 0
            release.main()

    def test_first_publication_uploads_before_publishing_forgebox(self):
        self.publish()
        commands = [command[:3] for command in self.commands]
        self.assertIn(("git", "push", "origin"), commands)
        self.assertEqual(commands.count(("gh", "release", "upload")), 2)
        self.assertLess(commands.index(("gh", "release", "edit")), commands.index(("box", "forgebox", "publish")))

    def test_rerun_keeps_tag_and_assets_and_completes_forgebox(self):
        self.tag = "head"
        self.releases = [{"tagName": "0.1.0", "isDraft": False}]
        self.assets = [{"name": self.archive.name}, {"name": self.archive.name + ".sha256"}]
        self.publish()
        self.assertFalse(any(command[:3] == ("gh", "release", "upload") for command in self.commands))
        self.assertFalse(any(command[:3] == ("git", "push", "origin") for command in self.commands))
        self.assertTrue(any(command[:3] == ("box", "forgebox", "publish") for command in self.commands))

    def test_published_version_needs_no_token_and_is_not_republished(self):
        os.environ.pop("FORGEBOX_API_TOKEN")
        self.tag = "head"
        self.publish(published=True)
        self.assertFalse(any(command[0] == "box" for command in self.commands))

    def test_later_main_commit_with_same_version_is_skipped(self):
        self.tag = "ancestor"
        self.publish()
        self.assertFalse(any(command[0] in ("gh", "box") for command in self.commands))

    def test_conflicting_tag_or_registry_failure_stops_publication(self):
        self.tag = "other"
        def unrelated_tag(*args):
            if args == ("git", "merge-base", "--is-ancestor", "other", "head"):
                raise RuntimeError("not an ancestor")
            return self.command(*args)
        with patch.object(release, "run", side_effect=unrelated_tag):
            with self.assertRaises(RuntimeError): release.main()
        self.tag = ""
        with patch.object(release, "run", side_effect=self.command), \
                patch.object(release, "forgebox_published", side_effect=RuntimeError("registry unavailable")):
            with self.assertRaises(RuntimeError): release.main()
        self.assertFalse(any(command[:3] == ("git", "push", "origin") for command in self.commands))

    def test_bad_checksum_stops_before_tagging(self):
        self.archive.write_bytes(b"changed")
        with self.assertRaisesRegex(RuntimeError, "checksum"): self.publish()
        self.assertFalse(any(command[:3] == ("git", "push", "origin") for command in self.commands))

    def test_changed_existing_asset_is_never_overwritten(self):
        self.tag = "head"
        self.releases = [{"tagName": "0.1.0", "isDraft": True}]
        self.assets = [{"name": self.archive.name}]
        original_command = self.command
        def changed_download(*args):
            output = original_command(*args)
            if args[:3] == ("gh", "release", "download"):
                (Path(args[args.index("--dir") + 1]) / self.archive.name).write_bytes(b"different")
            return output
        with patch.object(release, "run", side_effect=changed_download), \
                patch.object(release, "forgebox_published", return_value=True):
            with self.assertRaisesRegex(RuntimeError, "refusing to replace"): release.main()
        self.assertFalse(any(command[:3] == ("gh", "release", "upload") for command in self.commands))

    def test_forgebox_first_entry_and_failure_responses(self):
        with patch.object(release, "urlopen", side_effect=HTTPError("url", 404, "missing", {}, None)):
            self.assertFalse(release.forgebox_published("bx-xtdb", "0.1.0"))
        with patch.object(release, "urlopen", side_effect=HTTPError("url", 503, "unavailable", {}, None)):
            with self.assertRaises(HTTPError): release.forgebox_published("bx-xtdb", "0.1.0")
        with patch.object(release, "urlopen", return_value=io.BytesIO(b'{"error":true,"data":{}}')):
            with self.assertRaises(RuntimeError): release.forgebox_published("bx-xtdb", "0.1.0")


if __name__ == "__main__":
    unittest.main()
