#!/usr/bin/env bash
#
# Enforce the formatting rules in .editorconfig that a formatter would apply,
# so review comments stay about code and not about whitespace.
#
#   scripts/check-style.sh          check the working tree
#   scripts/check-style.sh --fix    rewrite the mechanical violations
#
# Checked: UTF-8/LF, a single trailing newline, no tabs, no trailing spaces,
# no line over 120 columns in Kotlin, and import order (java.* last, each group
# sorted). Markdown is exempt from the whitespace rules (.editorconfig says so)
# and from the width rule, because tables and links legitimately run long.
#
# No dependencies beyond python3, which the CI image already has.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

python3 - "$@" <<'PY'
import glob
import sys

FIX = '--fix' in sys.argv
MAX_WIDTH = 120

patterns = ['app/src/**/*.kt', 'app/src/**/*.kts', 'app/src/**/*.xml',
            'gradle/*.toml', '*.gradle', '*.sh', 'scripts/*.sh',
            '.github/workflows/*.yml', 'app/*.pro']
files = sorted({p for pattern in patterns for p in glob.glob(pattern, recursive=True)})


def is_kotlin(path):
    return path.endswith('.kt') or path.endswith('.kts')


def fix_lines(path, lines):
    out = []
    for line in lines:
        if path.endswith('.md'):
            out.append(line)
            continue
        out.append(line.rstrip())
    # exactly one trailing newline
    while len(out) > 1 and out[-1] == '' and out[-2] == '':
        out.pop()
    if out and out[-1] != '':
        out.append('')
    return out


def import_problems(lines, path):
    problems = []
    idx = [i for i, line in enumerate(lines) if line.startswith('import ')]
    if not idx:
        return problems
    block = lines[idx[0]:idx[-1] + 1]
    if any(not line.startswith('import ') for line in block):
        problems.append((idx[0] + 1, 'imports are not contiguous'))
        return problems
    names = [line.split()[1] for line in block]
    non_java = [n for n in names if not n.startswith('java.')]
    java = [n for n in names if n.startswith('java.')]
    if names[:len(non_java)] != non_java:
        problems.append((idx[0] + 1, 'java.* imports must come last'))
    for group in (non_java, java):
        for a, b in zip(group, group[1:]):
            if a > b:
                problems.append((idx[0] + 1, f'sort imports: {a} before {b}'))
                break
    return problems


violations = []
for path in files:
    try:
        raw = open(path, 'rb').read()
    except IsADirectoryError:
        continue
    if b'\r' in raw:
        violations.append((path, 0, 'CRLF line ending'))
    try:
        text = raw.decode('utf-8')
    except UnicodeDecodeError:
        violations.append((path, 0, 'not valid UTF-8'))
        continue

    lines = text.split('\n')
    if text and not text.endswith('\n'):
        violations.append((path, len(lines), 'missing final newline'))
    if text.endswith('\n\n') or text.endswith('\n\n\n'):
        violations.append((path, len(lines), 'more than one trailing newline'))

    for number, line in enumerate(lines, 1):
        if '\t' in line:
            violations.append((path, number, 'tab character'))
        if not path.endswith('.md') and line != line.rstrip():
            violations.append((path, number, 'trailing whitespace'))
        if is_kotlin(path) and len(line) > MAX_WIDTH:
            violations.append((path, number, f'line is {len(line)} columns (max {MAX_WIDTH})'))

    if is_kotlin(path):
        for number, message in import_problems(lines, path):
            violations.append((path, number, message))

    if FIX and violations:
        fixed = fix_lines(path, lines)
        if fixed != lines:
            open(path, 'w').write('\n'.join(fixed))

AUTO_FIXABLE = ('missing final newline', 'more than one trailing newline',
                'trailing whitespace')

if FIX:
    # Only the whitespace rules are mechanical; anything else needs a human.
    violations = [v for v in violations if v[2] not in AUTO_FIXABLE]

if not violations:
    print(f'style ok ({len(files)} files)', file=sys.stderr)
    sys.exit(0)

for path, number, message in sorted(violations):
    print(f'{path}:{number}: {message}')

print(f'\n{len(violations)} style violation(s)' +
      ('' if FIX else '; run scripts/check-style.sh --fix'),
      file=sys.stderr)
sys.exit(1)
PY
