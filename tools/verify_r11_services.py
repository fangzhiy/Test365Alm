"""Bounded local/CI real S3-private and clamd benign-control startup check."""
import ipaddress
import os
import socket
import struct
import time
import urllib.error
import urllib.parse
import urllib.request


def validate_targets(endpoint, host, port):
    url = urllib.parse.urlsplit(endpoint)
    if url.scheme != 'http' or url.username or url.password or url.path not in ('', '/') or url.query or url.fragment:
        raise ValueError('Invalid local asset service endpoint')
    if not ipaddress.ip_address(url.hostname).is_loopback or not ipaddress.ip_address(host).is_loopback:
        raise ValueError('Asset probe only operates on explicit loopback addresses')
    if not 1 <= port <= 65535 or not url.port:
        raise ValueError('Invalid asset service port')


def private_store(endpoint):
    try:
        # An unsigned root request must not enumerate this private S3 service.
        with urllib.request.urlopen(endpoint, timeout=2):
            return False
    except urllib.error.HTTPError as error:
        return error.code == 403


def clean_scan(host, port):
    data = b'benign R11 owned CI scanner control'
    with socket.create_connection((host, port), timeout=2) as stream:
        stream.settimeout(3)
        stream.sendall(b'zINSTREAM\0' + struct.pack('!I', len(data)) + data + struct.pack('!I', 0))
        response = b''
        while b'\0' not in response and len(response) < 512:
            part = stream.recv(128)
            if not part:
                break
            response += part
        return response == b'stream: OK\0'


def check(endpoint, host, port):
    try:
        return private_store(endpoint) and clean_scan(host, port)
    except (OSError, ValueError):
        return False


def main():
    try:
        endpoint = os.environ['TEST365ALM_ASSETS_S3_ENDPOINT']
        host = os.environ['TEST365ALM_ASSETS_CLAMD_HOST']
        port = int(os.environ['TEST365ALM_ASSETS_CLAMD_PORT'])
        validate_targets(endpoint, host, port)
    except (KeyError, ValueError):
        print('FAIL: invalid or non-loopback asset service configuration')
        return 2
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if check(endpoint, host, port):
            print('PASS: private S3 anonymous root denied; real clamd benign INSTREAM=CLEAN')
            return 0
        time.sleep(2)
    print('NOT_READY: private S3 or clamd control failed within the bounded startup budget')
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
