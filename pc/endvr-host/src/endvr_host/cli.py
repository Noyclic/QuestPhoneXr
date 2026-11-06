import argparse
from aiohttp import web
from .server import EndVRHost

def main():
    parser = argparse.ArgumentParser(description="EndVR PC host EV-0 synthetic preview")
    parser.add_argument("--bind", default="127.0.0.1", help="default is loopback only")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--width", type=int, default=960)
    parser.add_argument("--height", type=int, default=540)
    parser.add_argument("--fps", type=int, default=30)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be 1..65535")
    host = EndVRHost(args.width, args.height, args.fps)
    print(f"EndVR EV-0: http://{args.bind}:{args.port}/")
    if args.bind not in ("127.0.0.1", "::1", "localhost"):
        print("WARNING: unencrypted development HTTP; trusted LAN only; never port-forward.")
    web.run_app(host.app, host=args.bind, port=args.port, print=None)

if __name__ == "__main__":
    main()
