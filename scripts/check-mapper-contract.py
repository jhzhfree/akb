#!/usr/bin/env python3
"""Validate Java MyBatis mapper interfaces against all XML mapper namespaces."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
XML_DIR = ROOT / "backend/src/main/resources/mapper"
JAVA_ROOT = ROOT / "backend/src/main/java"
EXECUTABLE = {"select", "insert", "update", "delete"}

def java_methods(text):
    text = re.sub(r"/\*[\s\S]*?\*/|//[^\n]*", "", text)
    names = set()
    for decl in re.findall(r"([^{};]+;)", text):
        if "(" not in decl:
            continue
        before = decl.split("(", 1)[0].strip()
        m = re.search(r"([A-Za-z_$][\w$]*)\s*$", before)
        if m:
            names.add(m.group(1))
    names.update(re.findall(r"\bdefault\s+[\w<>, ?\[\].]+\s+([A-Za-z_$][\w$]*)\s*\(", text))
    return names

def main():
    errors=[]; total=0; namespaces=0
    for xml_path in sorted(XML_DIR.glob("*Mapper.xml")):
        try:
            root=ET.parse(xml_path).getroot()
        except Exception as exc:
            errors.append(f"{xml_path}: XML parse failed: {exc}"); continue
        namespace=root.attrib.get("namespace","")
        if not namespace:
            errors.append(f"{xml_path}: missing namespace"); continue
        rel=Path(*namespace.split('.')).with_suffix('.java')
        java_path=JAVA_ROOT/rel
        if not java_path.exists():
            errors.append(f"{xml_path}: Java mapper missing: {java_path}"); continue
        xml_ids={e.attrib['id'] for e in root if e.tag in EXECUTABLE and e.attrib.get('id')}
        methods=java_methods(java_path.read_text(encoding='utf-8'))
        missing_java=sorted(xml_ids-methods)
        missing_xml=sorted(methods-xml_ids)
        # default orchestration methods intentionally have no XML statement.
        jtext=java_path.read_text(encoding='utf-8')
        defaults=set(re.findall(r"\bdefault\s+[\w<>, ?\[\].]+\s+([A-Za-z_$][\w$]*)\s*\(",jtext))
        missing_xml=[x for x in missing_xml if x not in defaults]
        if missing_java: errors.append(f"{xml_path.name}: XML ids missing Java methods: {missing_java}")
        if missing_xml: errors.append(f"{java_path.name}: Java methods missing XML statements: {missing_xml}")
        namespaces+=1; total+=len(xml_ids)
        print(f"{xml_path.stem}: statements={len(xml_ids)}")
    if errors:
        print("\n".join(errors),file=sys.stderr); return 1
    print(f"mapper contract passed: namespaces={namespaces} statements={total}")
    return 0
if __name__=='__main__': raise SystemExit(main())
