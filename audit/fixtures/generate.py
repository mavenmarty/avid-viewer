#!/usr/bin/env python3
"""
Stress fixtures for the Avid Viewer menu audit (deterministic, seed 30000).

Writes into OUT_DIR:
  catalog.m3u     10,000 live channels + 30,000 movies/series in one M3U (the app's M3U path)
                  - live: "ALL CHANNELS" has 8,000 channels (for "item 2,400 of 8,000"), 40 small groups
                  - VOD: "VOD | EN - Movies 4K" has 15,500 titles; 60 other categories, very uneven (1..2,000)
                  - messy: long titles (200+ chars), Unicode (CJK, Arabic, emoji, combining marks),
                    missing logos (~30 %), missing group-title (~2 %), duplicate names
  guide.xml.gz    XMLTV for the first 500 channels of "ALL CHANNELS": 7 days (yesterday + 6),
                  30/60/90-minute programmes, a few missing titles
  live_streams.json  the same 10,000 live channels as an Xtream get_live_streams answer

URLs inside point at http://HOST (default 127.0.0.1:8099). Images: /img/<n>.png, streams: /live/u/p/<id>.ts.

Usage:
  python3 generate.py OUT_DIR [--host 192.168.1.20:8099]
  python3 generate.py OUT_DIR --serve        # also serve OUT_DIR (and fake /img/*) for a real TV
"""
import gzip, json, os, random, sys, time, datetime

def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__); sys.exit(1)
    out = args[0]
    host = "127.0.0.1:8099"
    if "--host" in args:
        host = args[args.index("--host") + 1]
    os.makedirs(out, exist_ok=True)
    rnd = random.Random(30000)
    root = "http://" + host

    words = "Ocean Night River Story Shadow Garden City Fire Winter Summer Lost Golden Silent Iron Star Moon Road Hidden Last First Wild Blue Red Dark Bright".split()
    unicode_bits = ["東京物語", "Café Ünïcode", "عنوان عربي", "🎬 Movie Night", "Ñandú", "Zoë & Chloë", "éclair", "Привет мир", "שלום"]

    def title(i, prefix=""):
        r = rnd.random()
        base = f"{rnd.choice(words)} {rnd.choice(words)} {i}"
        if r < 0.03:
            base = (base + " — ") * 12 + "(Extended Director's Cut, Remastered, 4K HDR, Multi-Audio)"
        elif r < 0.10:
            base = f"{rnd.choice(unicode_bits)} {i}"
        elif r < 0.12:
            base = f"{rnd.choice(words)} {i % 50}"  # duplicate-ish names
        return prefix + base

    def logo(i):
        return "" if rnd.random() < 0.30 else f' tvg-logo="{root}/img/{i}.png"'

    lines = ["#EXTM3U"]
    live = []
    # live channels
    for i in range(1, 8001):
        live.append((i, f"CH {i:04d} " + title(i), "ALL CHANNELS", f"epg{i}" if i <= 500 else None))
    small_groups = [f"GROUP {g:02d}" for g in range(40)]
    for i in range(8001, 10001):
        g = small_groups[rnd.randrange(40) if rnd.random() > 0.3 else 0]
        live.append((i, title(i), g, None))
    for (i, name, g, epg) in live:
        grp = "" if rnd.random() < 0.02 and g != "ALL CHANNELS" else f' group-title="{g}"'
        tid = f' tvg-id="{epg}"' if epg else ""
        lines.append(f'#EXTINF:-1{tid}{logo(i)}{grp},{name}')
        lines.append(f"{root}/live/u/p/{i}.ts")
    # VOD
    cats = ["VOD | EN - Movies 4K"] + [f"VOD | CAT {c:02d}" for c in range(60)]
    sizes = [15500]
    remaining = 30000 - 15500
    weights = [rnd.random() ** 3 for _ in range(60)]
    s = sum(weights)
    for w in weights:
        sizes.append(max(1, int(remaining * w / s)))
    sizes[-1] += 30000 - sum(sizes)
    n = 100000
    for cat, size in zip(cats, sizes):
        for k in range(size):
            n += 1
            is_series = cat != cats[0] and rnd.random() < 0.25
            t = title(n, "4K · " if cat == cats[0] else "")
            path = "series" if is_series else "movie"
            lines.append(f'#EXTINF:-1{logo(n)} group-title="{cat}",{t}')
            lines.append(f"{root}/{path}/u/p/{n}.mkv")
    with open(os.path.join(out, "catalog.m3u"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    # XMLTV: 500 channels x 7 days
    day0 = datetime.datetime.now(datetime.timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0) - datetime.timedelta(days=1)
    end = day0 + datetime.timedelta(days=7)
    fmt = lambda d: d.strftime("%Y%m%d%H%M%S +0000")
    count = 0
    with gzip.open(os.path.join(out, "guide.xml.gz"), "wt", encoding="utf-8") as g:
        g.write('<?xml version="1.0" encoding="UTF-8"?>\n<tv>\n')
        for i in range(1, 501):
            g.write(f'<channel id="epg{i}"><display-name>CH {i:04d}</display-name></channel>\n')
        for i in range(1, 501):
            t = day0
            while t < end:
                dur = rnd.choice([30, 30, 60, 60, 90])
                t2 = t + datetime.timedelta(minutes=dur)
                name = "" if rnd.random() < 0.01 else f"Prog {rnd.choice(words)} {count % 997}"
                g.write(f'<programme channel="epg{i}" start="{fmt(t)}" stop="{fmt(t2)}"><title>{name}</title><desc>Episode {count}</desc></programme>\n')
                t = t2; count += 1
        g.write("</tv>\n")

    with open(os.path.join(out, "live_streams.json"), "w", encoding="utf-8") as f:
        json.dump([{"num": i, "name": name, "stream_type": "live", "stream_id": i, "stream_icon": f"{root}/img/{i}.png",
                    "epg_channel_id": epg or "", "category_id": "1"} for (i, name, _, epg) in live], f)

    print(json.dumps({"live": len(live), "vod": 30000, "largest_vod_category": sizes[0], "programmes": count,
                      "m3u_bytes": os.path.getsize(os.path.join(out, "catalog.m3u")),
                      "guide_gz_bytes": os.path.getsize(os.path.join(out, "guide.xml.gz")),
                      "live_json_bytes": os.path.getsize(os.path.join(out, "live_streams.json"))}))

    if "--serve" in args:
        import http.server, functools
        PNG = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010806000000" "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")
        class H(http.server.SimpleHTTPRequestHandler):
            def do_GET(self):
                if self.path.startswith("/img/"):
                    self.send_response(200); self.send_header("Content-Type", "image/png"); self.send_header("Content-Length", str(len(PNG))); self.end_headers(); self.wfile.write(PNG)
                else:
                    super().do_GET()
        port = int(host.split(":")[1]) if ":" in host else 80
        print(f"Serving {out} on :{port}  (playlist: {root}/catalog.m3u  guide: {root}/guide.xml.gz)")
        http.server.ThreadingHTTPServer(("", port), functools.partial(H, directory=out)).serve_forever()

if __name__ == "__main__":
    main()
