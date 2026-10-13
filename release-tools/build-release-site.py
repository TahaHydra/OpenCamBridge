"""Build self-contained release policy/guide pages; no scripts or analytics."""
from pathlib import Path
from html import escape
import base64
import re

ROOT = Path(__file__).resolve().parents[1]
logo = base64.b64encode((ROOT / "branding/generated/play-icon-512.png").read_bytes()).decode()

def inline(value):
    value = escape(value)
    value = re.sub(r"`([^`]+)`", r"<code>\1</code>", value)
    value = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", value)
    return value

def render(source):
    blocks = []
    for paragraph in source.split("\n\n"):
        lines = paragraph.strip().splitlines()
        if not lines:
            continue
        if lines[0].startswith("## "):
            blocks.append("<h2>" + inline(lines[0][3:]) + "</h2>")
        elif lines[0].startswith("# "):
            blocks.append("<h1>" + inline(lines[0][2:]) + "</h1>")
        elif re.match(r"(?:- |\d+\. )", lines[0]):
            tag = "ul" if lines[0].startswith("- ") else "ol"
            items = []
            for line in lines:
                match = re.match(r"(?:- |\d+\. )(.*)", line)
                if match:
                    items.append(match[1])
                elif items:
                    items[-1] += " " + line.strip()
            blocks.append(f"<{tag}>" + "".join("<li>" + inline(item) + "</li>" for item in items) + f"</{tag}>")
        else:
            blocks.append("<p>" + inline(" ".join(line.strip() for line in lines)) + "</p>")
    return "\n".join(blocks)

for source_name, route, title in [
    ("privacy-policy.md", "products/opencambridge/privacy", "Privacy policy"),
    ("user-guide.md", "docs/opencambridge/getting-started", "Getting started"),
]:
    source = (ROOT / "docs/release" / source_name).read_text(encoding="utf-8")
    page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OpenCamBridge '''+escape(title)+''' — xHydra</title><style>body{margin:0;background:#08090b;color:#e9edf3;font:17px/1.65 system-ui,sans-serif}main{max-width:820px;margin:auto;padding:40px 24px 80px}img{width:80px;height:80px}h1,h2{line-height:1.25}h2{margin-top:36px}p,li{color:#cbd2dd}li{margin:10px 0}a{color:#83b8ff}code{overflow-wrap:anywhere}footer{margin-top:40px;border-top:1px solid #343944;padding-top:20px}</style><main><a href="https://xhydra.fr/products/opencambridge" aria-label="OpenCamBridge product page"><img alt="OpenCamBridge hydra camera mark" src="data:image/png;base64,'''+logo+'''"></a>'''+render(source)+'''<footer><a href="mailto:opencambridge@xhydra.fr">Contact support</a> · <a href="https://xhydra.fr/products/opencambridge/privacy">Privacy policy</a> · <a href="https://xhydra.fr/products/opencambridge">OpenCamBridge</a></footer></main></html>'''
    output = ROOT / "docs/release/website" / route / "index.html"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(page, encoding="utf-8")
    print("Prepared /" + route + ":", output)
