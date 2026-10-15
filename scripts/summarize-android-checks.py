"""Bounded CI diagnostics and native preview transport; these tests contain no account data."""
from pathlib import Path
import base64
import xml.etree.ElementTree as ET

for path in Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    print(f"Android suite: {root.get('name')} · tests={root.get('tests')} failures={root.get('failures')}")
    for failure in root.findall('.//failure'):
        print((failure.text or '')[:6000])
report = Path('app/build/reports/lint-results-debug.xml')
if report.exists():
    issues = ET.parse(report).getroot().findall('issue')
    errors = [i for i in issues if i.get('severity') in ('Error', 'Fatal')]
    print(f'Android lint: {len(errors)} errors, {len(issues) - len(errors)} warnings/information')
    for issue in errors:
        print(issue.get('id'), issue.get('message'))
        print([x.attrib for x in issue.findall('location')])
# Enables review in environments where artifact download networking is unavailable.
# Only synthetic previews of the native shell are exported, never WebView/account data.
for name in ('home-light', 'home-dark', 'settings-light'):
    path = Path(f'app/build/outputs/shell-previews/{name}.png')
    if path.exists() and path.stat().st_size <= 160000:
        print(f'CLEANGRAM_PREVIEW {name} {base64.b64encode(path.read_bytes()).decode()}')
