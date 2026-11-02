#!/usr/bin/env python3
"""Bridge WSA to the host's HTTP proxy so campus hosts are reachable.

Why this exists: SUSTech's campus network lives in 172.18.0.0/16 and WSA's own
NAT picks 172.18.16.0/20, so `bb.sustech.edu.cn` (172.18.18.x) looks like it is
on the guest's own link — ARP goes nowhere and the app reports
"Failed to connect to bb.sustech.edu.cn/172.18.18.49:443". The host reaches
those hosts only through its local proxy (127.0.0.1:7897), which the guest
cannot see. This listens on the WSA host adapter (172.18.16.1) and relays to
that proxy, so `settings put global http_proxy 172.18.16.1:7897` inside WSA is
enough — no adb tunnel, survives WSA and adb restarts.

Run:  python tools/wsa-campus-bridge.py [--listen 172.18.16.1:7897] [--target 127.0.0.1:7897]
Stop: Ctrl+C (or kill the process)
"""

import argparse
import errno
import socket
import sys
import threading
import time

BUF = 64 * 1024


def parse_hostport(text: str) -> tuple[str, int]:
    host, _, port = text.rpartition(":")
    if not host:
        raise SystemExit(f"bad host:port: {text!r}")
    return host, int(port)


def relay(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            chunk = src.recv(BUF)
            if not chunk:
                break
            dst.sendall(chunk)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def handle(client: socket.socket, target: tuple[str, int]) -> None:
    try:
        upstream = socket.create_connection(target, timeout=10)
    except OSError:
        client.close()
        return
    upstream.settimeout(None)
    client.settimeout(None)
    threading.Thread(target=relay, args=(client, upstream), daemon=True).start()
    relay(upstream, client)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="172.18.16.1:7897")
    ap.add_argument("--target", default="127.0.0.1:7897")
    args = ap.parse_args()
    listen = parse_hostport(args.listen)
    target = parse_hostport(args.target)

    while True:
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            server.bind(listen)
        except OSError as e:
            # The WSA adapter is not up yet (WSA not started, or it is booting).
            print(f"bind {listen[0]}:{listen[1]} failed ({e}); retrying in 5s", flush=True)
            server.close()
            time.sleep(5)
            continue
        server.listen(64)
        print(f"bridging {listen[0]}:{listen[1]} -> {target[0]}:{target[1]}", flush=True)
        while True:
            try:
                client, _ = server.accept()
            except OSError as e:
                if e.errno == errno.EINTR:
                    continue
                print(f"accept failed: {e}; restarting listener", flush=True)
                break
            threading.Thread(target=handle, args=(client, target), daemon=True).start()
        server.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
