#!/usr/bin/env bash
# Downloads the pinned bazel-diff release binary into tools/bin/ (gitignored) and verifies its checksum.
set -euo pipefail
VERSION="v60.0.0"
SHA256="4c1e70c955a7a950fc0ce15d921e976078a5fd1571e423c05016cb6e7c433dac"
URL="https://github.com/Tinder/bazel-diff/releases/download/$VERSION/bazel-diff-rust-linux-amd64"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/tools/bin/bazel-diff"

OS="$(uname -s)"
ARCH="$(uname -m)"
if [[ "$OS" != "Linux" || "$ARCH" != "x86_64" ]]; then
  echo "error: only the linux-amd64 bazel-diff release is pinned, but this host is $OS/$ARCH;" \
       "set BAZEL_DIFF=/path/to/binary to point at one you built or downloaded yourself." >&2
  exit 1
fi

if [[ -x "$DEST" ]] && echo "$SHA256  $DEST" | sha256sum -c --quiet - 2>/dev/null; then
  echo "bazel-diff $VERSION already present at $DEST"
  exit 0
fi
mkdir -p "$ROOT/tools/bin"
TMP="$(mktemp "$ROOT/tools/bin/.bazel-diff.XXXXXX")"
trap 'rm -f "$TMP"' EXIT
curl -fsSL --connect-timeout 20 --max-time 300 --retry 3 -o "$TMP" "$URL"
echo "$SHA256  $TMP" | sha256sum -c --quiet -
chmod 0755 "$TMP"
mv "$TMP" "$DEST"
trap - EXIT
"$DEST" --version
