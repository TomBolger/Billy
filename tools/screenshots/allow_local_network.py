"""Screenshot builds only: let the phone JS reach the local scene server.

Since SDK 4.44 apps get only the network access they declare, and the emulator
enforces it, so the demo can't ask control.py (127.0.0.1) which scene to show.
Usage: allow_local_network.py app/package.json
"""
import json
import sys

path = sys.argv[1]
with open(path) as f:
    data = json.load(f)
perms = data["pebble"].setdefault("usesPermissions", [])
if not any(isinstance(p, dict) and p.get("name") == "LocalNetwork" for p in perms):
    perms.append({"name": "LocalNetwork", "reason": "To get the screenshot scene from the build machine", "required": True})
with open(path, "w") as f:
    json.dump(data, f, indent=2)
