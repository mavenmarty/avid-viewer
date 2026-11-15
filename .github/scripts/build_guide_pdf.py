#!/usr/bin/env python3
"""Build AvidViewer-User-Guide.pdf from docs/USER_GUIDE.md (pandoc -> HTML -> headless Chrome).

Usage: build_guide_pdf.py [output.pdf]   (run from the repository root)
Needs: pandoc, google-chrome (or chromium) on PATH.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

GUIDE = "docs/USER_GUIDE.md"
CSS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "guide-pdf.css")


def main() -> int:
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "AvidViewer-User-Guide.pdf")
    text = open(GUIDE, encoding="utf-8").read()
    m = re.search(r"Avid Viewer (\d+\.\d+\.\d+)\*\*", text)
    version = m.group(1) if m else ""

    text = re.sub(r"<!--.*?-->\n?", "", text, flags=re.S)                     # maintenance comments
    text = re.sub(r"^(\*\*Contents:\*\*.*)$", r"::: contents\n\1\n:::", text, flags=re.M)
    text = text.replace("\n## Release notes\n", "\n## Release notes {.release}\n")  # new page

    chrome = next((c for c in ("google-chrome", "chromium", "chromium-browser") if shutil.which(c)), None)
    if not chrome:
        print("Chrome/Chromium not found", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        md, html = os.path.join(tmp, "guide.md"), os.path.join(tmp, "guide.html")
        open(md, "w", encoding="utf-8").write(text)
        subprocess.run(["pandoc", md, "-f", "markdown+gfm_auto_identifiers", "-t", "html5", "-s",
                        "--metadata", f"pagetitle=Avid Viewer User Guide {version}".strip(),
                        "--css", CSS, "--resource-path", os.path.abspath(os.path.dirname(GUIDE)),
                        "--embed-resources", "-o", html], check=True)
        subprocess.run([chrome, "--headless", "--no-sandbox", "--disable-gpu", "--no-pdf-header-footer",
                        f"--print-to-pdf={out}", html], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f"Wrote {out} (version {version or 'unknown'})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
