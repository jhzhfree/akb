#!/usr/bin/env python3
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
java = (ROOT / "backend/src/main/java/com/jh/knowledgeos/mapper/KnowledgeMapper.java").read_text(encoding="utf-8")
xml_path = ROOT / "backend/src/main/resources/mapper/KnowledgeMapper.xml"
root = ET.parse(str(xml_path)).getroot()
xml_ids = {node.attrib.get("id") for node in root if node.attrib.get("id")}
java_methods = set(
    re.findall(r"(?:^|\n)\s*(?:[A-Za-z0-9_$.<>?,\s]+)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", java, re.MULTILINE)
)
ignore = set()
missing_xml = sorted(java_methods - xml_ids - ignore)
missing_java = sorted(xml_ids - java_methods)
assert not missing_xml, "Mapper Java methods missing XML statements: %s" % missing_xml
assert not missing_java, "Mapper XML statements missing Java methods: %s" % missing_java
print("mapper contract passed: statements=%d" % len(xml_ids))
