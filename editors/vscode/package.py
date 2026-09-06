#!/usr/bin/env python3
"""Build a deterministic VSIX from tracked extension sources, without npm installs."""
import argparse
import hashlib
import json
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


def package(output: Path) -> Path:
    root = Path(__file__).resolve().parent
    metadata = json.loads((root / 'package.json').read_text())
    version = metadata['version']
    fields = {key: escape(metadata[key], {'"': '&quot;'})
              for key in ('name', 'version', 'publisher', 'displayName', 'description')}
    engine = escape(metadata['engines']['vscode'], {'"': '&quot;'})
    extension_pack = escape(','.join(metadata.get('extensionPack', [])), {'"': '&quot;'})
    manifest = f'''<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">
<Metadata><Identity Language="en-US" Id="{fields['name']}" Version="{fields['version']}" Publisher="{fields['publisher']}"/>
<DisplayName>{fields['displayName']}</DisplayName><Description xml:space="preserve">{fields['description']}</Description>
<Categories>Programming Languages,Formatters</Categories><Properties>
<Property Id="Microsoft.VisualStudio.Code.Engine" Value="{engine}"/>
<Property Id="Microsoft.VisualStudio.Code.ExtensionPack" Value="{extension_pack}"/>
</Properties></Metadata><Installation><InstallationTarget Id="Microsoft.VisualStudio.Code"/></Installation>
<Dependencies/><Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true"/></Assets>
</PackageManifest>'''
    content_types = '''<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="json" ContentType="application/json"/><Default Extension="js" ContentType="application/javascript"/>
<Default Extension="md" ContentType="text/markdown"/><Default Extension="vsixmanifest" ContentType="text/xml"/>
<Default Extension="txt" ContentType="text/plain"/></Types>'''
    entries = {'extension.vsixmanifest': manifest.encode(), '[Content_Types].xml': content_types.encode(),
               'extension/LICENSE.txt': (root.parent.parent / 'LICENSE').read_bytes()}
    for name in ('package.json', 'extension.js', 'language-configuration.json', 'README.md',
                 'syntaxes/pam.tmLanguage.json', 'syntaxes/pam-php.injection.json'):
        entries['extension/' + name] = (root / name).read_bytes()
    output.mkdir(parents=True, exist_ok=True)
    target = output / f'pam-native-vscode-{version}.vsix'
    with ZipFile(target, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compresslevel=9)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.vsix.sha256').write_text(f'{digest}  {target.name}\n')
    return target


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    print(package(parser.parse_args().output))
