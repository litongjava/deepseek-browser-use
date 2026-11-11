import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tempfile
import urllib.parse
import urllib.request
import zipfile


VERSION = "3.5.2"
SHA256 = "8e662c1ebc5b4ddb63e65a7c8664709c15cedf27c933c8ba8ff6d99b0c037ab0"
PROFILE = "GAE-Proxy"
AUTO_PROFILE = "GAE-Proxy自动切换"


def prepare_config(source, destination, host, port):
    if not host.strip() or not 1 <= port <= 65535:
        raise ValueError("invalid proxy endpoint")
    options = json.loads(source.read_text(encoding="utf-8-sig"))
    profile = options["+" + PROFILE]
    if profile.get("profileType") != "FixedProfile":
        raise ValueError("expected fixed profile")
    if options["+" + AUTO_PROFILE].get("profileType") != "SwitchProfile":
        raise ValueError("expected switch profile")
    proxy = profile["fallbackProxy"]
    if proxy.get("scheme") not in {"http", "https", "socks4", "socks5"}:
        raise ValueError("unsupported proxy scheme")
    proxy.update(host=host, port=port)
    options["-startupProfileName"] = AUTO_PROFILE
    with destination.open("x", encoding="utf-8") as output:
        json.dump(options, output, ensure_ascii=False, separators=(",", ":"))
    return {"prepared": True, "profile": PROFILE, "scheme": proxy["scheme"],
            "port": port, "loopback": host in {"127.0.0.1", "localhost", "::1"},
            "startupProfile": AUTO_PROFILE}


def validate_url(value, proxy=False, redirect=False):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ({"http", "https"} if proxy else {"https"}):
        raise ValueError("unsupported URL scheme")
    if not parsed.hostname or parsed.username or parsed.password or (parsed.query and not redirect) or parsed.fragment:
        raise ValueError("use a credential-free URL")
    return value


def unpack_verified(archive, destination, expected, version):
    if not re.fullmatch(r"[a-fA-F0-9]{64}", expected):
        raise ValueError("invalid SHA-256")
    with archive.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected.lower():
        raise ValueError("SHA-256 mismatch")
    with zipfile.ZipFile(archive) as bundle:
        if sum(entry.file_size for entry in bundle.infolist()) > 100 * 1024 * 1024:
            raise ValueError("archive too large")
        for entry in bundle.infolist():
            relative = PurePosixPath(entry.filename)
            if relative.is_absolute() or ".." in relative.parts or ":" in entry.filename or "\\" in entry.filename:
                raise ValueError("unsafe archive path")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("archive symlink")
        manifest = json.loads(bundle.read("manifest.json"))
        if manifest.get("manifest_version") != 3 or manifest.get("version") != version:
            raise ValueError("unexpected manifest")
        if manifest.get("name") != "Proxy SwitchyOmega 3 (ZeroOmega)":
            raise ValueError("unexpected extension")
        destination.mkdir(parents=True, exist_ok=False)
        bundle.extractall(destination)
    return {"verified": True, "version": version, "manifestVersion": 3}


def prepare_package(args):
    if args.version != VERSION and not args.sha256:
        raise ValueError("a trusted SHA-256 is required for another version")
    expected = args.sha256 or SHA256
    if args.archive:
        return unpack_verified(args.archive, args.destination, expected, args.version)
    url = validate_url(args.url or f"https://github.com/zero-peak/ZeroOmega/releases/download/v{args.version}/chromium-release.zip")
    proxies = {"http": validate_url(args.proxy, True), "https": args.proxy} if args.proxy else {}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxies))
    with tempfile.TemporaryDirectory(prefix="zeroomega-download-") as directory:
        archive = Path(directory) / "extension.zip"
        with opener.open(url, timeout=30) as response, archive.open("xb") as output:
            validate_url(response.url, redirect=True)
            total = 0
            while chunk := response.read(65536):
                total += len(chunk)
                if total > 25 * 1024 * 1024:
                    raise ValueError("download too large")
                output.write(chunk)
        return unpack_verified(archive, args.destination, expected, args.version)


def main():
    parser = argparse.ArgumentParser(description="Prepare ZeroOmega locally; print safe summaries only.")
    commands = parser.add_subparsers(dest="command", required=True)
    config = commands.add_parser("config")
    config.add_argument("--input", type=Path, required=True)
    config.add_argument("--output", type=Path, required=True)
    config.add_argument("--host", default="127.0.0.1")
    config.add_argument("--port", type=int, default=10808)
    package = commands.add_parser("package")
    package.add_argument("--destination", type=Path, required=True)
    source = package.add_mutually_exclusive_group()
    source.add_argument("--archive", type=Path)
    source.add_argument("--url")
    package.add_argument("--proxy")
    package.add_argument("--version", default=VERSION)
    package.add_argument("--sha256")
    args = parser.parse_args()
    try:
        result = prepare_config(args.input, args.output, args.host, args.port) if args.command == "config" else prepare_package(args)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as error:
        print(json.dumps({"ok": False, "errorType": type(error).__name__,
                          "hint": "Check input profiles, endpoint, trusted checksum, network and unused destination; private details omitted."}))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
