#!/usr/bin/env python3
"""
Decrypt an OpenZeekr encrypted log blob (from Settings -> "Copy (encrypted)").

The app encrypts a copied log with the OpenZeekr RSA-4096 PUBLIC key; only the holder of
the matching PRIVATE key (the developers) can read it. This tool does that decryption.

Blob layout (base64, NO_WRAP), matching core/.../util/LogCrypto.kt:
    magic "OZ" (2) | version (1) | wrappedKeyLen (2, big-endian) | wrappedKey | IV (12) | GCM(ct+tag)
  - wrappedKey : AES-256 key, RSA-OAEP(SHA-256, MGF1-SHA-256) wrapped
  - GCM(ct+tag): AES-256-GCM ciphertext with the 16-byte tag appended (JCA layout)

Usage:
    python3 decrypt_log.py --key tools/log-decrypt/private_key.pem --in blob.txt
    pbpaste | python3 decrypt_log.py --key tools/log-decrypt/private_key.pem        # blob on stdin
    python3 decrypt_log.py --key private_key.pem "<base64 blob string>"

Requires the 'cryptography' package (pip install cryptography). RSA-OAEP + AES-GCM are not
available from the Python stdlib alone.
"""
import argparse
import base64
import sys

try:
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit("This tool needs the 'cryptography' package:  pip install cryptography")

MAGIC = b"OZ"
VERSION = 1
IV_LEN = 12


def decrypt(blob_b64: str, private_key_pem: bytes) -> str:
    raw = base64.b64decode(blob_b64.strip())
    if raw[:2] != MAGIC:
        raise ValueError("bad magic - not an OpenZeekr log blob")
    version = raw[2]
    if version != VERSION:
        raise ValueError(f"unsupported blob version {version} (this tool handles v{VERSION})")
    wrapped_len = (raw[3] << 8) | raw[4]
    off = 5
    wrapped = raw[off:off + wrapped_len]; off += wrapped_len
    iv = raw[off:off + IV_LEN]; off += IV_LEN
    ct = raw[off:]

    private_key = serialization.load_pem_private_key(private_key_pem, password=None)
    aes_key = private_key.decrypt(
        wrapped,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    plaintext = AESGCM(aes_key).decrypt(iv, ct, None)
    return plaintext.decode("utf-8", errors="replace")


def main() -> None:
    ap = argparse.ArgumentParser(description="Decrypt an OpenZeekr encrypted log blob.")
    ap.add_argument("--key", required=True, help="path to the RSA private key PEM (dev-held, offline)")
    ap.add_argument("--in", dest="infile", help="file holding the base64 blob (default: stdin)")
    ap.add_argument("blob", nargs="?", help="the base64 blob as an argument (alternative to --in/stdin)")
    args = ap.parse_args()

    if args.blob:
        blob = args.blob
    elif args.infile:
        with open(args.infile, "r", encoding="utf-8") as f:
            blob = f.read()
    else:
        blob = sys.stdin.read()

    with open(args.key, "rb") as f:
        private_key_pem = f.read()

    sys.stdout.write(decrypt(blob, private_key_pem))


if __name__ == "__main__":
    main()
