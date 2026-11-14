import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from prepare import AUTO_PROFILE, prepare_config, unpack_verified, validate_url


class PrepareTests(unittest.TestCase):
    def test_only_requested_config_fields_change(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.json"
            destination = Path(directory) / "output.json"
            options = {"+GAE-Proxy": {"profileType": "FixedProfile", "fallbackProxy": {
                "scheme": "http", "host": "old.example", "port": 1}},
                "+" + AUTO_PROFILE: {"profileType": "SwitchProfile", "rules": ["fixture-secret"]},
                "unrelated": {"password": "fixture-secret"}}
            original = json.dumps(options)
            source.write_text(original, encoding="utf-8")
            result = prepare_config(source, destination, "127.0.0.1", 10808)
            options["+GAE-Proxy"]["fallbackProxy"].update(host="127.0.0.1", port=10808)
            options["-startupProfileName"] = AUTO_PROFILE
            self.assertEqual(json.loads(destination.read_text(encoding="utf-8")), options)
            self.assertEqual(source.read_text(encoding="utf-8"), original)
            self.assertNotIn("fixture-secret", json.dumps(result))
            with self.assertRaises(FileExistsError):
                prepare_config(source, source, "127.0.0.1", 10808)

    def test_missing_mode_does_not_write(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.json"
            destination = Path(directory) / "output.json"
            source.write_text("{}", encoding="utf-8")
            with self.assertRaises(KeyError):
                prepare_config(source, destination, "127.0.0.1", 10808)
            self.assertFalse(destination.exists())

    def test_archive_validation_before_extract(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "package.zip"
            destination = Path(directory) / "extension"
            manifest = {"name": "Proxy SwitchyOmega 3 (ZeroOmega)", "version": "3.5.2", "manifest_version": 3}
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("manifest.json", json.dumps(manifest))
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            with self.assertRaises(ValueError):
                unpack_verified(archive, destination, "0" * 64, "3.5.2")
            self.assertFalse(destination.exists())
            self.assertTrue(unpack_verified(archive, destination, digest, "3.5.2")["verified"])
            with self.assertRaises(FileExistsError):
                unpack_verified(archive, destination, digest, "3.5.2")
            with zipfile.ZipFile(archive, "a") as bundle:
                bundle.writestr("../escape", "fixture")
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            with self.assertRaises(ValueError):
                unpack_verified(archive, Path(directory) / "other", digest, "3.5.2")
            self.assertFalse((Path(directory) / "escape").exists())

    def test_urls_do_not_accept_credentials(self):
        for url in ["http://example.com/file.zip", "https://user:secret@example.com/file.zip", "https://example.com/file.zip?token=secret"]:
            with self.assertRaises(ValueError):
                validate_url(url)
        self.assertEqual(validate_url("http://127.0.0.1:10808", proxy=True), "http://127.0.0.1:10808")
        self.assertTrue(validate_url("https://example.com/file.zip?signature=fixture", redirect=True))


if __name__ == "__main__":
    unittest.main()
