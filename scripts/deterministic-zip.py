#!/usr/bin/env python3
"""Create a byte-reproducible ZIP from an immutable Git tree."""

from __future__ import annotations

import argparse
import datetime
import os
import stat
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path


def zip_timestamp(epoch: int) -> tuple[int, int, int, int, int, int]:
    value = datetime.datetime.fromtimestamp(max(epoch, 315532800), datetime.timezone.utc)
    return (value.year, value.month, value.day, value.hour, value.minute, value.second // 2 * 2)


def archive(tree: str, prefix: str, epoch: int, output: Path) -> None:
    if not prefix or prefix.startswith("/") or ".." in Path(prefix).parts:
        raise ValueError("archive prefix must be a confined relative path")
    if output.is_symlink() or (output.exists() and not output.is_file()):
        raise ValueError("archive output must be a regular path")
    output.parent.mkdir(parents=True, exist_ok=True)

    descriptor, tar_name = tempfile.mkstemp(prefix="pam-native-ios-", suffix=".tar")
    os.close(descriptor)
    temporary_tar = Path(tar_name)
    descriptor, zip_name = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
    os.close(descriptor)
    temporary_zip = Path(zip_name)
    try:
        with temporary_tar.open("wb") as handle:
            subprocess.run(
                ["git", "archive", "--format=tar", f"--prefix={prefix.rstrip('/')}/", tree],
                stdout=handle,
                check=True,
            )
        timestamp = zip_timestamp(epoch)
        with tarfile.open(temporary_tar, "r:") as source, zipfile.ZipFile(
            temporary_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as destination:
            for member in sorted(source.getmembers(), key=lambda item: item.name):
                name = member.name.rstrip("/") + ("/" if member.isdir() else "")
                info = zipfile.ZipInfo(name, timestamp)
                info.create_system = 3
                info.compress_type = zipfile.ZIP_DEFLATED
                info.extra = b""
                info.comment = b""
                if member.isdir():
                    mode = stat.S_IFDIR | member.mode
                    payload = b""
                elif member.issym():
                    mode = stat.S_IFLNK | member.mode
                    payload = member.linkname.encode("utf-8")
                elif member.isfile():
                    mode = stat.S_IFREG | member.mode
                    extracted = source.extractfile(member)
                    if extracted is None:
                        raise ValueError(f"cannot read archived file {member.name}")
                    payload = extracted.read()
                else:
                    raise ValueError(f"unsupported Git archive entry {member.name}")
                info.external_attr = (mode & 0xFFFF) << 16
                destination.writestr(info, payload, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        os.replace(temporary_zip, output)
    finally:
        temporary_tar.unlink(missing_ok=True)
        temporary_zip.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tree", required=True)
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--epoch", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    options = parser.parse_args()
    archive(options.tree, options.prefix, options.epoch, options.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
