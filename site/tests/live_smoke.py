"""Read-only, portable HTTPS/HTTP checks against the deployed build."""
import concurrent.futures
import hashlib
from pathlib import Path
import re
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def check(case):
    path, host, tls, expected = case
    url = f'{"https" if tls else "http"}://{host}{path}'
    opener = urllib.request.build_opener(NoRedirect)
    try:
        response = opener.open(url, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status, headers, body = response.code, response.headers, response.read()
    assert status == expected, (url, status, expected)
    if expected == 308:
        assert headers['Location'] == 'https://qiwuli.com' + path
    if expected == 200:
        if path.startswith('/assets/') or path in ['/mark.svg', '/sitemap.xml', '/robots.txt']:
            local = ROOT / 'dist' / path.lstrip('/')
        else:
            local = ROOT / 'dist/index.html'
        assert hashlib.sha256(body).digest() == hashlib.sha256(local.read_bytes()).digest(), f'Build mismatch: {url}'
        assert "script-src 'self'" in headers['Content-Security-Policy']
        assert headers['X-Content-Type-Options'] == 'nosniff'
        if path.startswith('/assets/'):
            assert 'max-age=31536000' in headers.get('Cache-Control', '')
        elif local.name == 'index.html':
            assert 'no-cache' in headers.get('Cache-Control', '')
    return f'{status} {url}'


if __name__ == '__main__':
    sitemap = (ROOT / 'dist/sitemap.xml').read_text('utf-8')
    pages = [re.sub(r'^https://qiwuli.com', '', url) for url in re.findall(r'<loc>([^<]+)</loc>', sitemap)]
    pages += ['/lab?tool=' + tool for tool in ['palette', 'focus', 'garden', 'ideas', 'choice', 'breath']]
    pages += ['/404', '/robots.txt', '/mark.svg', '/sitemap.xml']
    assets = re.findall(r'(?:src|href)="(/assets/[^"]+)"', (ROOT / 'dist/index.html').read_text('utf-8'))
    cases = [(p, 'qiwuli.com', True, 200) for p in pages + assets]
    cases += [(p, 'qiwuli.com', True, 404) for p in ['/v1/releases', '/api/status', '/assets/missing.js', '/missing-page']]
    cases += [('/.env', 'qiwuli.com', True, 403), ('/projects', 'qiwuli.com', False, 308), ('/journal', 'www.qiwuli.com', True, 308)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for result in pool.map(check, cases):
            print(result)
    print(f'PASS: {len(cases)} HTTPS/HTTP checks; TLS, security headers and deployed artifact hashes verified.')
