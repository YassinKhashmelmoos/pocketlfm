"""Generate PocketLFM brand assets: social banner + square logo."""
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Circle, Rectangle
import numpy as np

BG     = "#0d1117"
PANEL  = "#161b22"
INDIGO = "#6366f1"
VIOLET = "#a855f7"
EMER   = "#10b981"   # offline / private
WHITE  = "#f4f6fb"
MUTED  = "#8b949e"
CHIP   = "#1b2230"


def phone(ax, cx, cy, s, accent=INDIGO):
    """A smartphone with a chat bubble + on-device spark."""
    # device body
    ax.add_patch(FancyBboxPatch((cx - 0.42*s, cy - 0.78*s), 0.84*s, 1.56*s,
                 boxstyle="round,pad=0,rounding_size=" + str(0.16*s),
                 fc=PANEL, ec=accent, lw=3, zorder=4))
    # screen
    ax.add_patch(FancyBboxPatch((cx - 0.33*s, cy - 0.60*s), 0.66*s, 1.30*s,
                 boxstyle="round,pad=0,rounding_size=" + str(0.07*s),
                 fc=BG, ec="none", zorder=5))
    # chat bubble (assistant)
    ax.add_patch(FancyBboxPatch((cx - 0.26*s, cy + 0.06*s), 0.46*s, 0.30*s,
                 boxstyle="round,pad=0,rounding_size=" + str(0.07*s),
                 fc=accent, ec="none", zorder=6))
    # chat bubble (user)
    ax.add_patch(FancyBboxPatch((cx - 0.04*s, cy - 0.34*s), 0.30*s, 0.26*s,
                 boxstyle="round,pad=0,rounding_size=" + str(0.06*s),
                 fc=VIOLET, ec="none", zorder=6))
    # offline dot (top of screen)
    ax.add_patch(Circle((cx + 0.22*s, cy + 0.52*s), 0.05*s, fc=EMER, ec="none", zorder=7))


def chip(ax, x, y, w, h, text, fg=WHITE, ec=INDIGO):
    ax.add_patch(FancyBboxPatch((x, y), w, h,
                 boxstyle="round,pad=0,rounding_size=" + str(h*0.45),
                 fc=CHIP, ec=ec, lw=1.4, zorder=4))
    ax.text(x + w/2, y + h/2, text, color=fg, fontsize=h*32, va="center",
            ha="center", fontweight="bold", zorder=5)


def offline_pill(ax, x, y, s):
    ax.add_patch(Circle((x, y), 0.06*s, fc=EMER, ec="none", zorder=6))
    ax.text(x + 0.16*s, y, "100% ON-DEVICE", color=EMER, fontsize=13*s,
            fontweight="bold", va="center", ha="left", zorder=6)


# ── Banner 1280x640 ──────────────────────────────────────────────────────────
def make_banner():
    fig = plt.figure(figsize=(12.8, 6.4), dpi=100)
    ax = fig.add_axes([0, 0, 1, 1]); ax.axis("off")
    ax.set_xlim(0, 12.8); ax.set_ylim(0, 6.4)
    ax.add_patch(Rectangle((0, 0), 12.8, 6.4, fc=BG, ec="none"))
    ax.add_patch(Rectangle((0, 6.26), 12.8, 0.14, fc=INDIGO, ec="none"))

    offline_pill(ax, 1.25, 5.55, 1.0)
    phone(ax, 2.1, 3.55, 1.7)

    ax.text(3.5, 4.0, "PocketLFM", color=WHITE, fontsize=58,
            fontweight="bold", va="center", ha="left")
    ax.text(3.54, 3.02, "Run Liquid LFM2.5 fully on-device",
            color=INDIGO, fontsize=23, fontweight="bold", va="center", ha="left")
    ax.text(3.54, 2.46, "offline · private · no cloud · powered by llama.cpp",
            color=MUTED, fontsize=16, va="center", ha="left")

    chips = [("LFM2.5", INDIGO), ("100% offline", EMER), ("llama.cpp", VIOLET), ("Android", MUTED)]
    widths = [0.55 + 0.135 * len(t) for t, _ in chips]
    gap = 0.3
    total = sum(widths) + gap * (len(chips) - 1)
    x = (12.8 - total) / 2
    for (t, ec), w in zip(chips, widths):
        chip(ax, x, 0.82, w, 0.6, t, ec=ec)
        x += w + gap

    fig.savefig("banner.png", facecolor=BG)
    plt.close(fig)
    print("saved banner.png (1280x640)")


# ── Square logo 512x512 ──────────────────────────────────────────────────────
def make_logo():
    fig = plt.figure(figsize=(5.12, 5.12), dpi=100)
    ax = fig.add_axes([0, 0, 1, 1]); ax.axis("off")
    ax.set_xlim(0, 5.12); ax.set_ylim(0, 5.12)
    ax.add_patch(Rectangle((0, 0), 5.12, 5.12, fc=BG, ec="none"))
    ax.add_patch(FancyBboxPatch((0.5, 0.5), 4.12, 4.12,
                 boxstyle="round,pad=0,rounding_size=0.7", fc=PANEL, ec=INDIGO, lw=3))
    phone(ax, 2.56, 2.62, 2.0)
    offline_pill(ax, 1.35, 4.05, 1.1)
    fig.savefig("logo.png", facecolor=BG)
    plt.close(fig)
    print("saved logo.png (512x512)")


make_banner()
make_logo()
