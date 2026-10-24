"""Claude Code hook -> the UMAP server.

Claude Code runs this on every tool call and hands it the hook payload on
stdin. It forwards the payload to the map and exits 0.

Three rules, all of them about not hurting the session it is attached to:

  * it never blocks       - 0.35 s connect+read timeout, and the POST happens
                            on a daemon thread that the process does not wait
                            for beyond that.
  * it never fails loudly - any exception exits 0 with no output. A hook that
                            writes to stderr decorates every tool call in the
                            transcript; a hook that exits non-zero can block
                            the call outright.
  * it never blocks on a  - if the server is not running, connect() refuses
    dead server             immediately on Windows loopback, which is why no
                            retry logic is wanted here.
"""
import json
import os
import sys

def _port():
    """UMAP_PORT, else this copy's port.txt (written by install.py), else 5580."""
    if os.environ.get('UMAP_PORT'):
        return os.environ['UMAP_PORT']
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'port.txt')) as fh:
            return fh.read().strip() or '5580'
    except OSError:
        return '5580'


PORT = _port()
URL = 'http://127.0.0.1:%s/api/event' % PORT
TIMEOUT = 0.35


def main():
    try:
        raw = sys.stdin.read()
    except Exception:
        return 0
    if not raw.strip():
        return 0
    try:
        payload = json.loads(raw)
    except Exception:
        payload = {'raw': raw[:400]}

    payload.setdefault('agent_name', os.environ.get('UMAP_AGENT') or 'claude')
    payload.setdefault('cwd', os.getcwd())

    try:
        import urllib.request
        req = urllib.request.Request(
            URL, data=json.dumps(payload).encode('utf-8'),
            headers={'Content-Type': 'application/json'}, method='POST')
        urllib.request.urlopen(req, timeout=TIMEOUT).read()
    except Exception:
        pass
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        sys.exit(0)
