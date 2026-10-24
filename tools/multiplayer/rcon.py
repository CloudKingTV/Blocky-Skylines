"""Minimal RCON client: python3 rcon.py 'command' ['command' ...]"""
import socket, struct, sys
def pkt(i, t, body):
    b = body.encode() + b"\0\0"
    return struct.pack("<iii", len(b) + 8, i, t) + b
def recv(s):
    n = struct.unpack("<i", s.recv(4))[0]
    data = b""
    while len(data) < n: data += s.recv(n - len(data))
    i, t = struct.unpack("<ii", data[:8])
    return i, data[8:-2].decode(errors="replace")
s = socket.create_connection(("127.0.0.1", 25575), timeout=30)
s.sendall(pkt(1, 3, "townstead")); recv(s)
for c in sys.argv[1:]:
    s.sendall(pkt(2, 2, c)); print(recv(s)[1])
