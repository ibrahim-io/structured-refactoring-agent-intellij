"""Generate listen-along versions of the report from main.txt (pdftotext output).

Fixes the #1 TTS problem: PDF text has a hard line-break at the END OF EVERY LINE,
so every engine pauses line-by-line. We re-flow wrapped lines back into whole
paragraphs (blank line = paragraph boundary), drop bare page-number lines, and emit:
  - main_read.txt   : one paragraph per line (smooth in Balabolka / Word / Narrator)
  - main_read.html  : each paragraph in <p> (smooth + best with Edge "Read aloud")

Regenerate after each rebuild:  pdftotext -nopgbrk main.pdf main.txt ; python make_listen.py
"""
import re
import html

SRC = "main.txt"

def load_blocks(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        raw = f.read()
    # Normalise newlines, split into blocks separated by one-or-more blank lines.
    raw = raw.replace("\r\n", "\n").replace("\r", "\n")
    chunks = re.split(r"\n[ \t]*\n", raw)
    blocks = []
    for ch in chunks:
        lines = [ln.strip() for ln in ch.split("\n")]
        # Drop bare page-number lines (e.g. a line that is just "12").
        lines = [ln for ln in lines if ln and not re.fullmatch(r"\d{1,3}", ln)]
        if not lines:
            continue
        # Re-flow: join wrapped lines of the same paragraph with a single space.
        para = " ".join(lines)
        para = re.sub(r"[ \t]{2,}", " ", para).strip()
        if para:
            blocks.append(para)
    return blocks

def main():
    blocks = load_blocks(SRC)

    with open("main_read.txt", "w", encoding="utf-8") as f:
        f.write("\n\n".join(blocks) + "\n")

    body = "\n".join(f"  <p>{html.escape(b)}</p>" for b in blocks)
    page = (
        "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
        "<title>Report (listen-along)</title>\n"
        "<style>body{max-width:48rem;margin:2rem auto;padding:0 1rem;"
        "font:1.15rem/1.7 Georgia,serif}p{margin:0 0 1rem}</style>\n"
        "</head>\n<body>\n" + body + "\n</body></html>\n"
    )
    with open("main_read.html", "w", encoding="utf-8") as f:
        f.write(page)

    print(f"{len(blocks)} paragraphs -> main_read.txt + main_read.html")

if __name__ == "__main__":
    main()
