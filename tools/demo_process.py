"""Private process supervisor; launched only by demo_up, with secrets in env, never argv."""
import os
from pathlib import Path
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    role, run_id = sys.argv[1:]
    if run_id != os.environ.get('R03_RUN_ID') or role not in ('server', 'web'):
        return 2
    if role == 'server':
        jars = [p for p in (root / 'apps/server/target').glob('*.jar') if not p.name.endswith('.original')]
        if len(jars) != 1:
            return 2
        command = [str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')),
                   '-jar', str(jars[0])]
        cwd = root / 'apps/server'
    else:
        command = ['node', str(root / 'apps/web/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1',
                   '--port', os.environ['DEMO_WEB_PORT'], '--strictPort']
        cwd = root / 'apps/web'
    return subprocess.call(command, cwd=cwd)


if __name__ == '__main__':
    raise SystemExit(main())
