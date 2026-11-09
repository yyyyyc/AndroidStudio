"""Vertical (9:16) architecture explainer reel:
Microsoft Foundry agent -> Azure API Management -> on-prem ASP.NET Core API -> SQL Server.

Usage:
  python reel.py                 # render MP4
  python reel.py --stills 5 20   # save preview PNGs at given seconds
"""
import math, os, subprocess, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import numpy as np
import imageio_ffmpeg

W, H, FPS, DUR = 1080, 1920, 30, 34.0
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "foundry_onprem_reel.mp4")
FONTS = r"C:\Windows\Fonts"


def font(size, bold=False):
    return ImageFont.truetype(os.path.join(FONTS, "segoeuib.ttf" if bold else "segoeui.ttf"), size)


# ---------- palette ----------
WHITE = (255, 255, 255, 255)
MUTED = (165, 180, 208, 255)
CYAN = (34, 211, 238, 255)
BLUE_REQ = (56, 189, 248)
GREEN_RES = (34, 197, 94)
AZURE = (59, 160, 255)
AMBER = (245, 165, 36)
ACCENTS = {
    "foundry": (168, 85, 247),
    "apim": (6, 182, 212),
    "aspnet": (99, 102, 241),
    "sql": (239, 68, 68),
}

# ---------- easing / helpers ----------
def clamp(x, a=0.0, b=1.0):
    return max(a, min(b, x))

def ease_out(x):
    x = clamp(x); return 1 - (1 - x) ** 3

def ease_io(x):
    x = clamp(x); return 3 * x * x - 2 * x * x * x

def fade(t, start, end, fin=0.35, fout=0.35):
    """1 inside [start,end] with fade-in/out edges."""
    if t < start or t > end:
        return 0.0
    a = ease_out((t - start) / fin) if fin else 1.0
    b = ease_out((end - t) / fout) if fout else 1.0
    return min(a, b)

def with_alpha(sprite, a):
    if a >= 0.999:
        return sprite
    s = sprite.copy()
    s.putalpha(sprite.getchannel("A").point(lambda v: int(v * a)))
    return s

def paste(canvas, sprite, x, y, a=1.0):
    if a <= 0.003:
        return
    canvas.alpha_composite(with_alpha(sprite, a), (int(x), int(y)))

def rrect(w, h, r, fill=None, outline=None, width=0, ss=3):
    im = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle([0, 0, w * ss - 1, h * ss - 1], r * ss,
                                         fill=fill, outline=outline, width=width * ss)
    return im.resize((w, h), Image.LANCZOS)

def wrap(draw, text, fnt, maxw):
    words, lines, cur = text.split(), [], ""
    for wd in words:
        test = (cur + " " + wd).strip()
        if draw.textlength(test, font=fnt) <= maxw:
            cur = test
        else:
            lines.append(cur); cur = wd
    if cur:
        lines.append(cur)
    return lines

def text_block(parts, width, align="center", gap=10):
    """parts: list of (text, font, color). Returns RGBA sprite."""
    tmp = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    rows = []
    for text, fnt, col in parts:
        for ln in wrap(tmp, text, fnt, width):
            asc, desc = fnt.getmetrics()
            rows.append((ln, fnt, col, asc + desc))
        rows.append(None)  # paragraph gap
    hgt = sum(r[3] for r in rows if r) + gap * len(rows)
    im = Image.new("RGBA", (width, hgt), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    y = 0
    for r in rows:
        if r is None:
            y += gap * 2; continue
        ln, fnt, col, lh = r
        tw = d.textlength(ln, font=fnt)
        x = (width - tw) / 2 if align == "center" else 0
        d.text((x, y), ln, font=fnt, fill=col)
        y += lh
    return im


# ---------- layout ----------
CARD_W, CARD_H, CARD_X = 800, 170, 140
NODES = [  # key, title, subtitle, center y
    ("foundry", "Microsoft Foundry", "AI agent + OpenAPI tool", 420),
    ("apim", "Azure API Management", "Secure API gateway", 680),
    ("aspnet", "ASP.NET Core Web API", "Business logic", 1020),
    ("sql", "SQL Server", "System of record", 1280),
]
ZONE_AZ = (60, 268, 1020, 790)
ZONE_OP = (60, 870, 1020, 1392)
CHIP_Y = 840
CAPTION_TOP = 1450

# ---------- static sprites ----------
def make_background():
    y = np.linspace(0, 1, H)[:, None]
    top, bot = np.array([10, 16, 34]), np.array([4, 6, 14])
    grad = (top * (1 - y) + bot * y)[:, None, :].repeat(W, axis=1)
    img = Image.fromarray(grad.astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(img)
    for gy in range(40, H, 60):
        for gx in range(30, W, 60):
            d.point((gx, gy), fill=(40, 52, 80, 255))
    return img

def make_zone(box, color, label, right=False):
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    z = rrect(w, h, 36, fill=color + (28,), outline=color + (110,), width=2)
    d = ImageDraw.Draw(z)
    f = font(24, True)
    tw = d.textlength(label, font=f)
    d.text(((w - tw - 28) if right else 28, 14), label, font=f, fill=color + (230,))
    return z

def icon_tile(kind, s=124, ss=3):
    S = s * ss
    acc = ACCENTS[kind]
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([0, 0, S - 1, S - 1], 28 * ss, fill=acc + (255,))
    P = lambda fx, fy: (fx * S, fy * S)
    if kind == "foundry":
        def star(cx, cy, R):
            pts = []
            for k in range(8):
                ang = math.pi / 4 * k - math.pi / 2
                rad = R if k % 2 == 0 else R * 0.3
                pts.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
            d.polygon(pts, fill=WHITE)
        star(S * 0.45, S * 0.55, S * 0.32)
        star(S * 0.74, S * 0.27, S * 0.13)
    elif kind == "apim":
        d.polygon([P(.5, .14), P(.82, .25), P(.79, .56), P(.5, .87), P(.21, .56), P(.18, .25)], fill=WHITE)
        d.line([P(.35, .50), P(.46, .62), P(.66, .38)], fill=acc + (255,), width=int(S * 0.07), joint="curve")
    elif kind == "aspnet":
        f = font(int(S * 0.36), True)
        txt = "</>"
        bb = d.textbbox((0, 0), txt, font=f)
        d.text(((S - (bb[2] - bb[0])) / 2 - bb[0], (S - (bb[3] - bb[1])) / 2 - bb[1]), txt, font=f, fill=WHITE)
    elif kind == "sql":
        x0, x1 = S * .24, S * .76
        eh = S * .14
        d.rectangle([x0, S * .25, x1, S * .74], fill=WHITE)
        d.ellipse([x0, S * .67, x1, S * .67 + eh], fill=WHITE)
        lw = int(S * 0.035)
        for yy in (S * .40, S * .55):
            d.arc([x0, yy, x1, yy + eh], 0, 180, fill=acc + (255,), width=lw)
        d.ellipse([x0, S * .18, x1, S * .18 + eh], fill=WHITE, outline=acc + (255,), width=lw)
    return im.resize((s, s), Image.LANCZOS)

def make_card(kind, title, sub):
    card = rrect(CARD_W, CARD_H, 28, fill=(20, 30, 54, 250), outline=(255, 255, 255, 38), width=2)
    card.alpha_composite(icon_tile(kind), (24, 23))
    d = ImageDraw.Draw(card)
    d.text((172, 34), title, font=font(42, True), fill=WHITE)
    d.text((172, 94), sub, font=font(30), fill=MUTED)
    return card

def make_glow(kind):
    pad = 50
    acc = ACCENTS[kind]
    g = Image.new("RGBA", (CARD_W + pad * 2, CARD_H + pad * 2), (0, 0, 0, 0))
    ImageDraw.Draw(g).rounded_rectangle([pad - 4, pad - 4, pad + CARD_W + 4, pad + CARD_H + 4], 32,
                                        outline=acc + (255,), width=12)
    g = g.filter(ImageFilter.GaussianBlur(16))
    g.alpha_composite(rrect(CARD_W + 8, CARD_H + 8, 32, outline=acc + (255,), width=4), (pad - 4, pad - 4))
    return g, pad

def make_packet(color):
    s = 140
    im = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    c = s / 2
    d.ellipse([c - 36, c - 36, c + 36, c + 36], fill=color + (150,))
    im = im.filter(ImageFilter.GaussianBlur(14))
    core = Image.new("RGBA", (s * 3, s * 3), (0, 0, 0, 0))
    dc = ImageDraw.Draw(core)
    C = s * 1.5
    dc.ellipse([C - 54, C - 54, C + 54, C + 54], fill=color + (255,))
    dc.ellipse([C - 22, C - 22, C + 22, C + 22], fill=(255, 255, 255, 255))
    im.alpha_composite(core.resize((s, s), Image.LANCZOS))
    return im

def make_chip(text, color):
    f = font(26, True)
    tw = int(ImageDraw.Draw(Image.new("RGBA", (1, 1))).textlength(text, font=f))
    w, h = tw + 64, 52
    chip = rrect(w, h, 26, fill=(24, 32, 48, 255), outline=color + (255,), width=2)
    d = ImageDraw.Draw(chip)
    # tiny padlock
    d.rounded_rectangle([20, 22, 36, 38], 3, fill=color + (255,))
    d.arc([22, 12, 34, 30], 180, 360, fill=color + (255,), width=3)
    d.text((46, 9), text, font=f, fill=WHITE)
    return chip

def make_bubble(text, fill, outline, label):
    f = font(32)
    fl = font(20, True)
    d0 = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    tw = int(max(d0.textlength(text, font=f), d0.textlength(label, font=fl)))
    w, h = tw + 56, 96
    b = rrect(w, h, 26, fill=fill + (255,), outline=outline, width=2 if outline else 0)
    d = ImageDraw.Draw(b)
    d.text((28, 10), label, font=fl, fill=(255, 255, 255, 170))
    d.text((28, 38), text, font=f, fill=WHITE)
    return b

def make_check_row(text):
    s, ss = 64, 3
    c = Image.new("RGBA", (s * ss, s * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(c)
    d.ellipse([0, 0, s * ss - 1, s * ss - 1], fill=GREEN_RES + (255,))
    S = s * ss
    d.line([(S * .28, S * .52), (S * .44, S * .68), (S * .74, S * .36)], fill=WHITE, width=int(S * .1), joint="curve")
    c = c.resize((s, s), Image.LANCZOS)
    f = font(40, True)
    tw = int(ImageDraw.Draw(Image.new("RGBA", (1, 1))).textlength(text, font=f))
    row = Image.new("RGBA", (s + 28 + tw, 70), (0, 0, 0, 0))
    row.alpha_composite(c, (0, 3))
    ImageDraw.Draw(row).text((s + 28, 6), text, font=f, fill=WHITE)
    return row


BG = make_background()
ZONE_AZ_IMG = make_zone(ZONE_AZ, AZURE, "AZURE CLOUD")
ZONE_OP_IMG = make_zone(ZONE_OP, AMBER, "ON-PREMISES DATACENTER", right=True)
CARDS = {k: make_card(k, t, s) for k, t, s, _ in NODES}
GLOWS = {k: make_glow(k) for k, *_ in NODES}
CY = {k: cy for k, _, _, cy in NODES}
PKT_REQ = make_packet(BLUE_REQ)
PKT_RES = make_packet(GREEN_RES)
CHIP = make_chip("Private link: VPN / ExpressRoute", AMBER)
BUBBLE_Q = make_bubble("Where is order #1042?", (37, 99, 235), None, "USER")
BUBBLE_A = make_bubble("Shipped yesterday, arrives Friday.", (46, 36, 82), ACCENTS["foundry"] + (255,), "FOUNDRY AGENT")

HEADER = text_block([("ARCHITECTURE IN 30 SECONDS", font(26, True), CYAN),
                     ("AI Agent \u2192 On-Prem Data", font(58, True), WHITE)], 1000, gap=2)
TITLE = text_block([("How does an AI agent safely reach your on-prem database?", font(80, True), WHITE),
                    ("Microsoft Foundry \u00b7 APIM \u00b7 ASP.NET \u00b7 SQL Server", font(34), CYAN)], 900, gap=14)

OUTRO_TITLE = text_block([("Why this pattern?", font(68, True), WHITE)], 1000)
OUTRO_ROWS = [make_check_row(t) for t in ("Database never faces the internet",
                                           "One governed, monitored entry point",
                                           "Same API for apps and AI agents")]
OUTRO_TAG = text_block([("Foundry \u2192 APIM \u2192 ASP.NET Core \u2192 SQL Server", font(32), CYAN)], 1000)

# ---------- timeline ----------
APPEAR = {"foundry": 4.0, "apim": 6.2, "aspnet": 10.6, "sql": 12.8}
ZONE_AZ_T, ZONE_OP_T = 3.6, 8.4
CONNS = [  # from, to, start, dashed
    ("foundry", "apim", 5.9, False),
    ("apim", "aspnet", 8.5, True),
    ("aspnet", "sql", 12.5, False),
]
CHIP_T = 8.8

CAPTIONS = [
    (4.0, 6.2, "Microsoft Foundry", "Hosts the AI agent. It reaches business data through an OpenAPI tool."),
    (6.2, 8.4, "Azure API Management", "The single, secured front door to your internal APIs."),
    (8.4, 10.6, "Private connectivity", "VPN or ExpressRoute into the datacenter, or an APIM self-hosted gateway."),
    (10.6, 12.8, "ASP.NET Core Web API", "Your existing business logic, running on-prem."),
    (12.8, 14.8, "SQL Server", "Never exposed publicly. Only the API talks to it."),
    (15.0, 16.7, "1 \u00b7 User asks a question", "The agent decides it needs live order data."),
    (16.7, 18.4, "2 \u00b7 Agent calls its tool", "GET /orders/1042 on the APIM endpoint"),
    (18.4, 20.2, "3 \u00b7 APIM checks the call", "Validates the Entra ID token, applies rate limits, logs it."),
    (20.2, 22.0, "4 \u00b7 Over the private link", "Forwarded securely to the on-prem API."),
    (22.0, 23.8, "5 \u00b7 API queries SQL Server", "A parameterized query fetches the order."),
    (23.8, 26.0, "6 \u00b7 Data flows back", "JSON returns through APIM to the agent."),
    (26.0, 28.5, "7 \u00b7 Agent answers", "Grounded in real data, in plain language."),
]
CAPTION_IMGS = [text_block([(t, font(48, True), WHITE), (b, font(34), MUTED)], 940, gap=6)
                for _, _, t, b in CAPTIONS]

PACKETS = [  # start, end, y0, y1, sprite
    (16.9, 18.2, CY["foundry"], CY["apim"], PKT_REQ),
    (20.3, 21.8, CY["apim"], CY["aspnet"], PKT_REQ),
    (22.1, 23.3, CY["aspnet"], CY["sql"], PKT_REQ),
    (23.9, 25.9, CY["sql"], CY["foundry"], PKT_RES),
]
HIGHLIGHTS = [  # start, end, node
    (15.0, 17.0, "foundry"), (18.1, 20.4, "apim"), (21.7, 22.2, "aspnet"),
    (23.2, 24.1, "sql"), (25.8, 28.5, "foundry"),
]
OUTRO_T = 28.6


def card_top(k):
    return CY[k] - CARD_H // 2


def render(t):
    fr = BG.copy()
    d = ImageDraw.Draw(fr)

    # zones
    paste(fr, ZONE_AZ_IMG, ZONE_AZ[0], ZONE_AZ[1], ease_out((t - ZONE_AZ_T) / 0.6))
    paste(fr, ZONE_OP_IMG, ZONE_OP[0], ZONE_OP[1], ease_out((t - ZONE_OP_T) / 0.6))

    # connectors (grow downward)
    for a, b, st, dashed in CONNS:
        p = ease_io((t - st) / 0.6)
        if p <= 0:
            continue
        y0 = card_top(a) + CARD_H
        y1 = card_top(b)
        ye = y0 + (y1 - y0) * p
        col = (110, 130, 175, 255)
        if dashed:
            y = y0
            while y < ye:
                d.line([(W // 2, y), (W // 2, min(y + 18, ye))], fill=col, width=6)
                y += 30
        else:
            d.line([(W // 2, y0), (W // 2, ye)], fill=col, width=6)

    # packets (drawn beneath cards so they pass "through" nodes)
    for st, en, y0, y1, spr in PACKETS:
        for k in range(5):
            tt = t - k * 0.035
            if st <= tt <= en:
                y = y0 + (y1 - y0) * ease_io((tt - st) / (en - st))
                paste(fr, spr, W // 2 - spr.width // 2, y - spr.height // 2, 1.0 - k * 0.2)

    # glows
    for st, en, k in HIGHLIGHTS:
        a = fade(t, st, en, 0.25, 0.3)
        if a > 0:
            pulse = 0.7 + 0.3 * math.sin((t - st) * 7)
            g, pad = GLOWS[k]
            paste(fr, g, CARD_X - pad, card_top(k) - pad, a * pulse)

    # cards
    for k, *_ in NODES:
        p = (t - APPEAR[k]) / 0.5
        if p > 0:
            paste(fr, CARDS[k], CARD_X, card_top(k) + 30 * (1 - ease_out(p)), ease_out(p))

    # private-link chip
    p = (t - CHIP_T) / 0.4
    if p > 0:
        paste(fr, CHIP, W // 2 - CHIP.width // 2, CHIP_Y - CHIP.height // 2, ease_out(p))

    # header (build phase) / chat bubbles (flow phase)
    paste(fr, HEADER, (W - HEADER.width) // 2, 80, fade(t, 3.6, 14.9, 0.5, 0.4))
    a = fade(t, 15.0, OUTRO_T + 0.4, 0.35, 0.4)
    if a > 0:
        paste(fr, BUBBLE_Q, W - 70 - BUBBLE_Q.width, 50 + 20 * (1 - ease_out((t - 15.0) / 0.4)), a)
    a = fade(t, 26.0, OUTRO_T + 0.4, 0.35, 0.4)
    if a > 0:
        paste(fr, BUBBLE_A, 70, 156 + 20 * (1 - ease_out((t - 26.0) / 0.4)), a)

    # captions
    for (st, en, *_), img in zip(CAPTIONS, CAPTION_IMGS):
        a = fade(t, st, en, 0.3, 0.25)
        if a > 0:
            paste(fr, img, (W - img.width) // 2, CAPTION_TOP + 16 * (1 - ease_out((t - st) / 0.3)), a)

    # title scene
    a = fade(t, 0.0, 3.4, 0.4, 0.4) if t > 0.05 else 1.0
    if a > 0:
        paste(fr, TITLE, (W - TITLE.width) // 2, 760 - TITLE.height // 2 + 20 * (1 - a), a)

    # outro
    a = ease_out((t - OUTRO_T) / 0.5)
    if a > 0:
        ov = Image.new("RGBA", (W, H), (6, 9, 20, int(245 * a)))
        fr.alpha_composite(ov)
        paste(fr, OUTRO_TITLE, (W - OUTRO_TITLE.width) // 2, 600, a)
        for i, row in enumerate(OUTRO_ROWS):
            p = ease_out((t - OUTRO_T - 0.5 - i * 0.6) / 0.4)
            paste(fr, row, 130 + 40 * (1 - p), 780 + i * 120, p)
        p = ease_out((t - OUTRO_T - 2.6) / 0.5)
        paste(fr, OUTRO_TAG, (W - OUTRO_TAG.width) // 2, 1180, p)

    return fr.convert("RGB")


def main():
    if "--stills" in sys.argv:
        outdir = sys.argv[sys.argv.index("--stills") + 1]
        for ts in sys.argv[sys.argv.index("--stills") + 2:]:
            render(float(ts)).save(os.path.join(outdir, f"still_{ts}.png"))
        return
    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
           "-shortest", "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-movflags", "+faststart", OUT]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    n = int(DUR * FPS)
    for i in range(n):
        proc.stdin.write(render(i / FPS).tobytes())
        if i % 150 == 0:
            print(f"frame {i}/{n}", flush=True)
    proc.stdin.close()
    proc.wait()
    print("wrote", OUT)


if __name__ == "__main__":
    main()
