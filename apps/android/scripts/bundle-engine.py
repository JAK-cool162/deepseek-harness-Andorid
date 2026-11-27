#!/usr/bin/env python3
"""Build the full engine bundle shipped inside the APK, so the in-app install needs no network.

Output, under android/app/src/main/assets/engine-bundle/:
  <arch>/Packages            the Termux index the bundled packages come from (NativeInstaller reads it instead of the network)
  <arch>/debs/<name>.deb     every package NativeInstaller.ROOTS resolves to, verified by SHA-256
  dsh.tgz                    @deepseek-ai/dsh installed for Android with npm, pruned of source maps, TypeScript
                             sources, other platforms' node-pty builds, and the LibreOffice WebAssembly module
  VERSION                    the bundled harness version, which LinuxEngine compares to decide when to re-unpack

The resolution mirrors NativeInstaller.resolve; a package it misses is downloaded on the device instead.
Usage: bundle-engine.py [--arch aarch64] [--dsh @deepseek-ai/dsh@alpha]
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tarfile, tempfile, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'android', 'app', 'src', 'main', 'assets', 'engine-bundle')
REPOSITORY = 'https://packages.termux.dev/apt/termux-main'
JAVA = os.path.join(HERE, '..', 'android', 'app', 'src', 'main', 'java', 'ai', 'deepseek', 'harness', 'NativeInstaller.java')


def java_set(name):
    source = open(JAVA, encoding='utf-8').read()
    block = re.search(name + r'[^{(]*[{(]([^})]*)[})]', source).group(1)
    return re.findall(r'"([^"]+)"', block)


def parse_index(text):
    index = {}
    for block in text.split('\n\n'):
        fields = {}
        for line in block.split('\n'):
            colon = line.find(':')
            if colon > 0 and not line.startswith(' '):
                fields[line[:colon]] = line[colon + 1:].strip()
        if 'Package' not in fields or 'Filename' not in fields:
            continue
        depends = []
        for part in fields.get('Depends', '').split(','):
            alternatives = [a.strip().split(' ')[0].split('(')[0].strip() for a in part.split('|')]
            alternatives = [a for a in alternatives if a]
            if alternatives:
                depends.append(alternatives)
        index[fields['Package']] = dict(name=fields['Package'], filename=fields['Filename'], sha256=fields.get('SHA256', ''), depends=depends)
    return index


def resolve(index, roots, skip):
    wanted, seen, order = set(roots), [], []

    def choose(alternatives):
        for a in alternatives:
            if a in seen or a in wanted:
                return a
        for a in alternatives:
            if a in index:
                return a
        return alternatives[0]

    def visit(name):
        if name in skip or name in seen:
            return
        seen.append(name)
        pkg = index.get(name)
        if pkg is None:
            return
        for alternatives in pkg['depends']:
            visit(choose(alternatives))
        order.append(pkg)

    for root in roots:
        if root not in index:
            sys.exit(f'package {root} is missing from the Termux index')
        visit(root)
    return order


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def bundle_packages(arch):
    target = os.path.join(OUT, arch)
    shutil.rmtree(target, ignore_errors=True)
    os.makedirs(os.path.join(target, 'debs'))
    index_text = fetch(f'{REPOSITORY}/dists/stable/main/binary-{arch}/Packages').decode('utf-8')
    open(os.path.join(target, 'Packages'), 'w', encoding='utf-8').write(index_text)
    plan = resolve(parse_index(index_text), java_set('ROOTS'), set(java_set('SKIP')))
    total = 0
    for pkg in plan:
        data = fetch(f"{REPOSITORY}/{pkg['filename']}")
        if hashlib.sha256(data).hexdigest() != pkg['sha256']:
            sys.exit(f"{pkg['name']} does not match its SHA-256")
        open(os.path.join(target, 'debs', pkg['name'] + '.deb'), 'wb').write(data)
        total += len(data)
    print(f'bundled {len(plan)} {arch} packages, {total >> 20} MB')


def prune(root):
    modules = os.path.join(root, 'lib', 'node_modules', '@deepseek-ai', 'dsh', 'node_modules')
    shutil.rmtree(os.path.join(modules, '@deepseek-ai', 'libreoffice-kit-wasm'), ignore_errors=True)
    prebuilds = os.path.join(modules, 'node-pty', 'prebuilds')
    if os.path.isdir(prebuilds):
        for name in os.listdir(prebuilds):
            if name != 'linux-arm64':
                shutil.rmtree(os.path.join(prebuilds, name))
    dropped = re.compile(r'(\.map|\.ts|\.mts|\.cts|\.tsbuildinfo)$|^CHANGELOG', re.IGNORECASE)
    for directory, _, files in os.walk(root):
        for name in files:
            path = os.path.join(directory, name)
            if dropped.search(name) and not os.path.islink(path):
                os.remove(path)


def bundle_harness(spec):
    with tempfile.TemporaryDirectory() as work:
        prefix = os.path.join(work, 'prefix')
        subprocess.run(['npm', 'install', '-g', '--prefix', prefix, '--os=android', '--cpu=arm64', '--ignore-scripts',
                        '--no-fund', '--no-audit', spec], check=True)
        prune(prefix)
        manifest = json.load(open(os.path.join(prefix, 'lib', 'node_modules', '@deepseek-ai', 'dsh', 'package.json')))
        archive = os.path.join(OUT, 'dsh.tgz')
        with tarfile.open(archive, 'w:gz', compresslevel=9, format=tarfile.PAX_FORMAT) as tar:
            for name in ('bin', 'lib'):
                tar.add(os.path.join(prefix, name), arcname=name)
        open(os.path.join(OUT, 'VERSION'), 'w').write(f"@deepseek-ai/dsh@{manifest['version']}\n")
        print(f"bundled @deepseek-ai/dsh@{manifest['version']}, {os.path.getsize(archive) >> 20} MB")


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--arch', default='aarch64')
    parser.add_argument('--dsh', default='@deepseek-ai/dsh@alpha')
    args = parser.parse_args()
    os.makedirs(OUT, exist_ok=True)
    bundle_packages(args.arch)
    bundle_harness(args.dsh)
