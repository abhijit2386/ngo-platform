#!/usr/bin/env python3
"""Adds/updates keys in the en/hi/mr ARB files from a JSON object {key: [en, hi, mr]}.
Hindi/Marathi strings are drafts and must be reviewed by native speakers (docs/business/LOCALIZATION.md)."""
import json, re, sys, os
here = os.path.join(os.path.dirname(__file__), '..', 'lib', 'localization')
INT = {"min", "days", "count", "n", "hours", "percent"}
data = json.load(sys.stdin)
files = {lang: os.path.join(here, f"app_{lang}.arb") for lang in ("en", "hi", "mr")}
arbs = {lang: json.load(open(p, encoding="utf-8")) for lang, p in files.items()}
for key, (en, hi, mr) in data.items():
    arbs["en"][key], arbs["hi"][key], arbs["mr"][key] = en, hi, mr
    ph = re.findall(r"\{(\w+)\}", en)
    if ph:
        arbs["en"]["@" + key] = {"placeholders": {p: {"type": "int" if p in INT else "String"} for p in ph}}
for lang, p in files.items():
    json.dump(arbs[lang], open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(f"{len(data)} keys; total {len([k for k in arbs['en'] if not k.startswith('@')])}")
