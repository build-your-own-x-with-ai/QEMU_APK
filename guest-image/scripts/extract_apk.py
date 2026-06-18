#!/usr/bin/env python3
"""Extract busybox binary from an Alpine .apk file.

APK files are concatenated gzip streams:
  1. .SIGN... (signature tar)
  2. .PKGINFO... (control tar)
  3. bin/busybox, ... (data tar)

Standard tar/zcat can't handle this because the concatenated tar
archives aren't a single valid tar stream.
"""
import sys
import zlib
import io
import tarfile


def decompress_gzip_stream(data, offset):
    """Decompress a single gzip stream starting at offset.
    Returns (decompressed_data, next_offset).
    """
    # Skip gzip header
    pos = offset + 2  # skip magic
    if data[pos:pos+1] != b'\x08':  # must be deflate
        raise ValueError(f"Not deflate at {offset}")
    pos += 1  # skip method
    flags = data[pos]; pos += 1
    pos += 6  # skip mtime(4) + xfl(1) + os(1)
    if flags & 0x04:  # FEXTRA
        xlen = int.from_bytes(data[pos:pos+2], 'little'); pos += 2 + xlen
    if flags & 0x08:  # FNAME
        while data[pos] != 0: pos += 1
        pos += 1
    if flags & 0x10:  # FCOMMENT
        while data[pos] != 0: pos += 1
        pos += 1
    if flags & 0x02:  # FHCRC
        pos += 2
    # Decompress deflate data (wbits=-15 for raw deflate)
    dec = zlib.decompressobj(-15)
    decompressed = dec.decompress(data[pos:])
    decompressed += dec.flush()
    # After compressed data: 4 bytes CRC32 + 4 bytes ISIZE
    consumed = pos + len(data[pos:]) - len(dec.unused_data)
    next_offset = consumed + 8  # skip CRC32 + ISIZE footer
    return decompressed, next_offset


def extract_busybox(apk_path, output_path):
    with open(apk_path, 'rb') as f:
        data = f.read()

    # Decompress each gzip stream separately
    decompressed_parts = []
    pos = 0
    while pos < len(data) - 1:
        if data[pos:pos+2] != b'\x1f\x8b':
            break
        decompressed, pos = decompress_gzip_stream(data, pos)
        decompressed_parts.append(decompressed)

    # The last part is data.tar containing bin/busybox
    for part in decompressed_parts:
        try:
            tf = tarfile.open(fileobj=io.BytesIO(part))
            for member in tf.getmembers():
                name = member.name.rstrip('/')
                base = name.split('/')[-1]
                if base in ('busybox', 'busybox.static') and member.isfile():
                    content = tf.extractfile(member).read()
                    with open(output_path, 'wb') as out:
                        out.write(content)
                    print(f"Extracted busybox ({len(content)} bytes) to {output_path}")
                    return True
            tf.close()
        except (tarfile.TarError, Exception):
            continue

    print("ERROR: Could not find busybox in APK data", file=sys.stderr)
    return False


if __name__ == '__main__':
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <apk_file> <output_path>")
        sys.exit(1)
    sys.exit(0 if extract_busybox(sys.argv[1], sys.argv[2]) else 1)
