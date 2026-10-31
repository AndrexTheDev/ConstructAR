#!/usr/bin/env python3
# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
# All rights reserved. Proprietary and confidential - see LICENSE.
# SPDX-License-Identifier: LicenseRef-Proprietary
"""
Builds the ConstructAR landing page into `docs/`, which GitHub Pages serves.

Why a generator and not six hand-written files: the site exists in the same six languages as the
app, and hand-maintained copies drift on the second edit. One dictionary per language, one
template, one command:

    python3 tools/build_site.py

It writes `docs/index.html` (English, the default), `docs/<lang>/index.html` for the other five,
plus `robots.txt`, `sitemap.xml` and `.nojekyll`. No dependencies beyond the standard library and
no build step on the server: the output is plain HTML with inline CSS, one small image and no
external request at all — which is the same promise the app makes.

SEO is part of the template rather than an afterthought: canonical URLs, `hreflang` alternates
including `x-default`, per-language titles and descriptions, Open Graph and Twitter cards, and
JSON-LD for `SoftwareApplication` (with the real prices) and `FAQPage` (with the real answers).
"""

from __future__ import annotations

import html
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

BASE_URL = "https://andrexthedev.github.io/ConstructAR/"
REPO_URL = "https://github.com/AndrexTheDev/ConstructAR"
EMAIL = "hippie.highho@gmail.com"
DEVELOPER = "AndrexTheDev"

LANGS = ["en", "de", "es", "fr", "it", "pl"]
LANG_NAMES = {"en": "English", "de": "Deutsch", "es": "Español", "fr": "Français", "it": "Italiano", "pl": "Polski"}
OG_LOCALES = {"en": "en_GB", "de": "de_DE", "es": "es_ES", "fr": "fr_FR", "it": "it_IT", "pl": "pl_PL"}

COINS = [
    ("SOL", "79KsqtJJdhKFJ9woxnYgtf3nq7HxQveafWBCtC3mxWi8"),
    ("BTC", "bc1qeqzrlfg3edrydk4s0hecakc82gp26n5p7hkc7f"),
    ("ETH", "0xBC3fab34f69bc9f6661608C3FB36dDdC313C42F7"),
]

CSS = """
:root{
  --ink:#0A0C07; --ink-elevated:#14180E; --surface-high:#1E2314;
  --lime:#C8F04A; --lime-dim:#8FB22C; --gold:#FFD34D; --green:#00A651; --green-dim:#0A6B38;
  --chalk:#F3F6EA; --ash:#B4BE9F; --wire:#3A4430; --danger:#FF6B6B;
  --max:1080px; --radius:20px;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{
  margin:0; background:var(--ink); color:var(--chalk);
  font-family:"Inter","Segoe UI",Roboto,system-ui,-apple-system,sans-serif;
  font-size:17px; line-height:1.6; -webkit-font-smoothing:antialiased;
}
img{max-width:100%; display:block}
a{color:var(--lime); text-decoration:none}
a:hover{text-decoration:underline}
h1,h2,h3{line-height:1.15; margin:0 0 .5em; letter-spacing:-.02em}
h1{font-size:clamp(2.2rem,5.2vw,3.6rem); font-weight:800}
h2{font-size:clamp(1.6rem,3.2vw,2.4rem); font-weight:750}
h3{font-size:1.15rem; font-weight:700}
p{margin:0 0 1em}
.wrap{max-width:var(--max); margin:0 auto; padding:0 22px}
.skip{position:absolute; left:-999px; top:0; background:var(--lime); color:#10140A; padding:12px 18px; z-index:99}
.skip:focus{left:8px; top:8px}
.site-header{
  position:sticky; top:0; z-index:20; backdrop-filter:blur(14px);
  background:rgba(10,12,7,.86); border-bottom:1px solid var(--wire);
}
.nav{display:flex; align-items:center; gap:18px; padding:12px 0}
.brand{display:flex; align-items:center; gap:10px; font-weight:800; letter-spacing:-.02em; color:var(--chalk)}
.brand img{width:36px; height:36px; border-radius:50%}
.brand span{font-size:1.05rem}
.nav-links{display:flex; gap:18px; margin-left:auto; font-size:.95rem}
.nav-links a{color:var(--ash)}
.nav-links a:hover{color:var(--chalk); text-decoration:none}
.lang{display:flex; gap:6px; flex-wrap:wrap; font-size:.8rem; letter-spacing:.06em; text-transform:uppercase}
.lang a{padding:4px 7px; border-radius:8px; color:var(--ash); border:1px solid transparent}
.lang a[aria-current="true"]{color:var(--lime); border-color:var(--wire); background:var(--surface-high)}
@media (max-width:860px){ .nav-links{display:none} }
.hero{padding:64px 0 48px; display:grid; grid-template-columns:1.05fr .95fr; gap:48px; align-items:center}
@media (max-width:860px){ .hero{grid-template-columns:1fr; padding:40px 0 28px} }
.eyebrow{display:inline-flex; align-items:center; gap:8px; font-size:.78rem; letter-spacing:.14em;
  text-transform:uppercase; color:var(--lime); border:1px solid var(--wire); border-radius:999px;
  padding:6px 12px; background:var(--ink-elevated); margin-bottom:20px}
.lede{font-size:1.2rem; color:var(--ash); max-width:34em}
.cta{display:flex; flex-wrap:wrap; gap:12px; margin:26px 0 16px}
.btn{display:inline-flex; align-items:center; gap:10px; border-radius:16px; padding:15px 24px;
  font-weight:700; font-size:1rem; border:1px solid transparent}
.btn-primary{background:var(--lime); color:#10140A}
.btn-primary:hover{background:#d5f768; text-decoration:none}
.btn-ghost{border-color:var(--wire); color:var(--chalk)}
.btn-ghost:hover{background:var(--surface-high); text-decoration:none}
.note{color:var(--ash); font-size:.9rem}
.trust{display:flex; gap:20px; flex-wrap:wrap; margin-top:24px; font-size:.9rem; color:var(--ash)}
.trust span::before{content:"●"; color:var(--green); margin-right:7px; font-size:.7rem; vertical-align:middle}
.section{padding:72px 0; border-top:1px solid var(--wire)}
.section-head{max-width:44em; margin-bottom:36px}
.section-head p{color:var(--ash)}
.grid{display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:18px}
.card{background:var(--ink-elevated); border:1px solid var(--wire); border-radius:var(--radius); padding:24px}
.card h3{color:var(--chalk)}
.card p{color:var(--ash); margin:0; font-size:.98rem}
.card .mark{display:inline-flex; width:38px; height:38px; border-radius:12px; background:var(--surface-high);
  color:var(--lime); align-items:center; justify-content:center; font-weight:800; margin-bottom:14px}
.steps{counter-reset:step; display:grid; grid-template-columns:repeat(auto-fit,minmax(230px,1fr)); gap:18px}
.step{position:relative; background:var(--ink-elevated); border:1px solid var(--wire);
  border-radius:var(--radius); padding:24px 22px}
.step::before{counter-increment:step; content:counter(step); position:absolute; top:-16px; left:22px;
  width:34px; height:34px; border-radius:50%; background:var(--lime); color:#10140A;
  display:flex; align-items:center; justify-content:center; font-weight:800}
.step h3{margin-top:12px}
.step p{color:var(--ash); margin:0; font-size:.96rem}
table.acc{width:100%; border-collapse:collapse; background:var(--ink-elevated);
  border:1px solid var(--wire); border-radius:var(--radius); overflow:hidden}
table.acc th, table.acc td{padding:15px 18px; text-align:left; border-bottom:1px solid var(--wire)}
table.acc tr:last-child td{border-bottom:0}
table.acc th{font-size:.8rem; letter-spacing:.1em; text-transform:uppercase; color:var(--ash); font-weight:600}
table.acc td:last-child{color:var(--lime); font-weight:700; white-space:nowrap}
.plans{display:grid; grid-template-columns:repeat(auto-fit,minmax(230px,1fr)); gap:18px}
.plan{background:var(--ink-elevated); border:1px solid var(--wire); border-radius:var(--radius); padding:24px}
.plan.best{background:var(--surface-high); border-color:var(--lime-dim)}
.plan .price{font-size:1.7rem; font-weight:800; color:var(--chalk); margin:6px 0 10px}
.plan.best .price{color:var(--lime)}
.plan p{color:var(--ash); margin:0; font-size:.94rem}
.plan .tag{font-size:.72rem; letter-spacing:.12em; text-transform:uppercase; color:var(--gold)}
.crypto{margin-top:22px; background:var(--ink-elevated); border:1px solid var(--wire);
  border-radius:var(--radius); padding:22px}
.crypto h3{color:var(--gold); font-size:.98rem}
.crypto ul{list-style:none; padding:0; margin:0; display:grid; gap:10px}
.crypto li{display:flex; gap:12px; align-items:baseline; flex-wrap:wrap}
.crypto .sym{min-width:44px; color:var(--gold); font-weight:700; font-size:.85rem; letter-spacing:.08em}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:.85rem; color:var(--ash);
  overflow-wrap:anywhere}
.faq details{background:var(--ink-elevated); border:1px solid var(--wire); border-radius:16px;
  padding:16px 20px; margin-bottom:12px}
.faq summary{cursor:pointer; font-weight:650; color:var(--chalk)}
.faq summary::marker{color:var(--lime)}
.faq details p{color:var(--ash); margin:12px 0 0}
.langs{display:flex; flex-wrap:wrap; gap:10px; margin-top:14px}
.langs span{border:1px solid var(--wire); border-radius:999px; padding:7px 14px; font-size:.9rem; color:var(--ash)}
footer{border-top:1px solid var(--wire); padding:38px 0 54px; color:var(--ash); font-size:.9rem}
footer a{color:var(--chalk)}
.foot-grid{display:flex; flex-wrap:wrap; gap:24px; justify-content:space-between; align-items:flex-start}
.foot-brand{display:flex; gap:12px; align-items:center}
.foot-brand img{width:46px; height:46px; border-radius:50%}
.phone{width:100%; max-width:320px; margin:0 auto; background:var(--ink); border:1px solid var(--wire);
  border-radius:32px; padding:16px 14px; box-shadow:0 30px 70px rgba(0,0,0,.55)}
.phone .bar{display:flex; align-items:center; gap:8px; margin-bottom:12px}
.phone .bar img{width:26px; height:26px; border-radius:50%}
.phone .bar b{font-size:1rem}
.p-card{background:var(--ink-elevated); border-radius:16px; padding:14px; margin-bottom:10px}
.p-card.high{background:var(--surface-high)}
.p-label{font-size:.68rem; letter-spacing:.12em; text-transform:uppercase; color:var(--lime)}
.p-total{font-size:2rem; font-weight:800; color:var(--lime); line-height:1.1}
.p-muted{font-size:.78rem; color:var(--ash)}
.p-steps{display:flex; gap:6px; margin-bottom:10px}
.p-step{flex:1; text-align:center; font-size:.66rem; padding:7px 4px; border-radius:10px;
  background:var(--surface-high); color:var(--ash)}
.p-step.done{background:rgba(10,107,56,.45); color:var(--lime)}
.p-btn{background:var(--lime); color:#10140A; border-radius:14px; padding:12px; text-align:center;
  font-weight:750; font-size:.85rem; margin-top:4px}
.p-row{display:flex; justify-content:space-between; font-size:.8rem; padding:5px 0}
.p-row span:last-child{color:var(--gold); font-weight:700}
@media (prefers-reduced-motion:reduce){ html{scroll-behavior:auto} }
:focus-visible{outline:3px solid var(--lime); outline-offset:3px}
.stats{display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:14px}
.stat{background:var(--ink-elevated); border:1px solid var(--wire); border-radius:16px; padding:16px 18px}
.stat b{display:block; font-size:1.55rem; color:var(--lime); line-height:1.15; font-weight:800}
.stat span{font-size:.85rem; color:var(--ash)}
.cmp td.us{color:var(--lime); font-weight:700}
.cmp td.them{color:var(--ash)}
.cmp td:first-child{color:var(--chalk); font-weight:600}
.anchor{background:var(--surface-high); border:1px solid var(--lime-dim); border-radius:16px;
  padding:18px 20px; margin:22px 0 0; color:var(--chalk); font-size:1.02rem}
.anchor strong{color:var(--gold)}
.sticky-cta{position:fixed; left:0; right:0; bottom:0; z-index:40; display:none; gap:10px;
  align-items:center; background:rgba(10,12,7,.95); border-top:1px solid var(--wire);
  padding:10px 16px; backdrop-filter:blur(12px)}
.sticky-cta > div{flex:1}
.sticky-cta .btn{display:flex; justify-content:center; width:100%; padding:14px 16px; font-size:.95rem}
.sticky-cta small{display:block; color:var(--ash); font-size:.72rem; margin-top:2px}
@media (max-width:700px){ .sticky-cta{display:block} body{padding-bottom:86px} }
"""

CONTENT: dict[str, dict[str, str]] = {
    "en": {
        "meta_title": "ConstructAR — offline AR measuring for electricians and plumbers",
        "meta_desc": "Scan the room with your phone, draw the cable or pipe run, get the metres, the material list and a signed PDF protocol. 100 % offline, six languages, PDF/CSV/GLB.",
        "nav_features": "Features", "nav_how": "How it works", "nav_price": "Pricing",
        "nav_faq": "FAQ", "nav_repo": "GitHub",
        "hero_h1": "Measure the wall with your phone.",
        "hero_sub": "ConstructAR turns a phone into an AR tape measure for cable, conduit, pipe and duct runs: scan the room, tap the run, get the metres, the clips and a protocol the customer can sign.",
        "hero_cta": "Get ConstructAR", "hero_cta2": "See how it works",
        "hero_note": "Android · needs ARCore · free to start",
        "eyebrow": "100 % offline · no account",
        "trust1": "No account", "trust2": "No cloud", "trust3": "No internet permission",
        "feat_title": "Everything the job needs",
        "feat_sub": "Built for gloves, sunlight and a basement without signal.",
        "f1t": "Scan the room", "f1b": "The phone builds a 3D mesh of the walls as you walk. What it saw is what you measure.",
        "f2t": "Draw flush to the surface", "f2b": "Tap point by point; the geometry aligns to the wall's normal, with fillets at the bends.",
        "f3t": "Metres and material list", "f3b": "Every metre is measured on anchor-bound surface points, and clips, couplings and bends come from the same run.",
        "f4t": "A protocol the customer signs", "f4b": "PDF with room scan, runs, order quantities, a site photo and a signature page. Plus CSV and GLB/PLY for BIM.",
        "f5t": "Works without signal", "f5b": "No cloud, no account, no analytics. Everything is computed on the phone — in a cellar it behaves exactly like in the office.",
        "f6t": "Six languages", "f6b": "English, German, Spanish, French, Italian and Polish — both the app and the exported protocol.",
        "f7t": "The site file is inside",
        "f7b": "Hours, a timestamped diary and a defect list live in the same project — and on the same signed PDF. No second app, no sync.",
        "how_title": "Three steps, one screen each",
        "how_sub": "If a foreman cannot work it out in ten seconds, it is the wrong app.",
        "s1t": "Scan", "s1b": "Walk the room once, phone at chest height. The mesh appears on the walls.",
        "s2t": "Measure", "s2b": "Tap the start, the corners and the end. The length updates live, in metres.",
        "s3t": "Check", "s3b": "The project screen shows the runs, the order quantity and anything still missing.",
        "s4t": "Export", "s4b": "Pick a folder once; PDF, CSV and (Pro) GLB/PLY are written there.",
        "acc_title": "How accurate is it?",
        "acc_sub": "Stated plainly, because you will bill from it.",
        "acc1": "Flat wall, 1–4 m, detected plane", "acc1v": "±2 cm",
        "acc2": "Areas with depth only", "acc2v": "±6 cm",
        "acc3": "Angles and diagonals", "acc3v": "±1 %",
        "acc_note": "Runs are measured between anchor-refined surface points, never on the offset centreline. Anything critical should still be checked with a tape measure — the protocol says so too.",
        "price_title": "Free to try, honest to buy",
        "price_sub": "Crypto only — no card, no store fee, no account.",
        "plan_free": "Free", "plan_free_p": "0 €", "plan_free_b": "One room, unlimited runs, CSV and a watermarked PDF.",
        "plan_m": "Pro monthly", "plan_m_p": "10 € / month", "plan_m_b": "Unlimited rooms, clean PDF, GLB + PLY.",
        "plan_y": "Pro yearly", "plan_y_p": "100 € / year", "plan_y_b": "Same as monthly, two months free.", "plan_y_tag": "Best value",
        "plan_l": "Lifetime", "plan_l_p": "999 € once", "plan_l_b": "Pay once, use it forever.",
        "price_note": "Seven days of Pro are free — no card, no questions. Payment is SOL, BTC or ETH; you receive a licence key that the app verifies offline. Nothing is ever taken away when a subscription ends.",
        "crypto_title": "Payment addresses",
        "lang_title": "Six languages, one build",
        "lang_body": "Every screen and the exported protocol are translated — and the build refuses a release in which one language is missing a key.",
        "faq_title": "Questions from the site",
        "q1": "Does it work without internet?",
        "a1": "Yes — the app has no INTERNET permission at all. Nothing is uploaded, nothing is downloaded, and tracking, depth and rendering run on the phone.",
        "q2": "Which phones are supported?",
        "a2": "Android phones with ARCore and Android 7.0 or newer. A depth sensor (ToF) improves the numbers but is not required.",
        "q3": "Is it a laser replacement?",
        "a3": "For runs on flat walls, yes, to within a couple of centimetres. For anything technical or hidden behind a panel, check with a tape measure before cutting — the protocol states the expected error too.",
        "q4": "Where do my measurements live?",
        "a4": "In one JSON file per room, in the app's private storage. You can copy every project and its photo into a folder of your choice and restore them on another phone.",
        "q5": "Do I need an account?",
        "a5": "No. No sign-in, no cloud, no subscription tracking — a licence is a short signed key that the app verifies offline.",
        "q6": "How do I pay?",
        "a6": "In SOL, BTC or ETH to the address shown in the app. Send the transaction id and your device code, and you get a licence key back.",
        "foot_licence": "All rights reserved. Public for CI and review — not an open-source licence.",
        "foot_contact": "Contact", "foot_repo": "Source on GitHub", "foot_langs": "Languages",
        "foot_by": "ConstructAR by",
        "cta_end_title": "Ready for the next room?",
        "cta_end_body": "Install it, measure one wall, and decide after that.",
    },
    "de": {
        "meta_title": "ConstructAR — AR-Aufmaß offline für Elektriker und Installateure",
        "meta_desc": "Raum mit dem Handy scannen, Leitungsweg zeichnen, Meter, Materialliste und unterschriebenes PDF-Protokoll erhalten. 100 % offline, sechs Sprachen, PDF/CSV/GLB.",
        "nav_features": "Funktionen", "nav_how": "So funktioniert es", "nav_price": "Preise",
        "nav_faq": "FAQ", "nav_repo": "GitHub",
        "hero_h1": "Miss die Wand mit dem Handy.",
        "hero_sub": "ConstructAR macht aus dem Telefon ein AR-Maßband für Kabel, Kanal, Rohr und Luftleitung: Raum scannen, Strecke antippen, Meter, Schellen und ein Protokoll bekommen, das der Kunde unterschreiben kann.",
        "hero_cta": "ConstructAR holen", "hero_cta2": "So funktioniert es",
        "hero_note": "Android · benötigt ARCore · kostenlos starten",
        "eyebrow": "100 % offline · kein Konto",
        "trust1": "Kein Konto", "trust2": "Keine Cloud", "trust3": "Keine Internet-Berechtigung",
        "feat_title": "Alles, was die Baustelle braucht",
        "feat_sub": "Gebaut für Handschuhe, Sonnenlicht und einen Keller ohne Netz.",
        "f1t": "Raum scannen", "f1b": "Das Telefon baut beim Gehen ein 3D-Modell der Wände. Was es gesehen hat, wird gemessen.",
        "f2t": "Wandbündig zeichnen", "f2b": "Punkt für Punkt antippen; die Geometrie richtet sich an der Wandnormalen aus, mit Bögen an den Knicken.",
        "f3t": "Meter und Materialliste", "f3b": "Jeder Meter wird auf ankergebundenen Oberflächenpunkten gemessen; Schellen, Kupplungen und Bögen stammen aus derselben Strecke.",
        "f4t": "Ein Protokoll zum Unterschreiben", "f4b": "PDF mit Raumscan, Strecken, Bestellmengen, Baustellenfoto und Unterschriftenseite. Dazu CSV und GLB/PLY für BIM.",
        "f5t": "Funktioniert ohne Netz", "f5b": "Keine Cloud, kein Konto, keine Analytics. Alles rechnet auf dem Telefon — im Keller genau wie im Büro.",
        "f6t": "Sechs Sprachen", "f6b": "Deutsch, Englisch, Spanisch, Französisch, Italienisch und Polnisch — App und exportiertes Protokoll.",
        "f7t": "Die Baustellenmappe ist dabei",
        "f7b": "Zeiten, ein Bautagebuch mit Zeitstempel und eine Mängelliste liegen im selben Projekt — und auf demselben unterschriebenen PDF. Keine zweite App, kein Abgleich.",
        "how_title": "Drei Schritte, je ein Bildschirm",
        "how_sub": "Wenn ein Vorarbeiter es nicht in zehn Sekunden versteht, ist es die falsche App.",
        "s1t": "Scannen", "s1b": "Einmal durch den Raum gehen, Telefon auf Brusthöhe. Das Modell erscheint auf den Wänden.",
        "s2t": "Messen", "s2b": "Start, Ecken und Ende antippen. Die Länge läuft live mit, in Metern.",
        "s3t": "Prüfen", "s3b": "Der Projektbildschirm zeigt Strecken, Bestellmenge und was noch fehlt.",
        "s4t": "Exportieren", "s4b": "Ordner einmal wählen; PDF, CSV und (Pro) GLB/PLY landen dort.",
        "acc_title": "Wie genau ist es?",
        "acc_sub": "Klar gesagt, denn du rechnest damit ab.",
        "acc1": "Ebene Wand, 1–4 m, erkannte Fläche", "acc1v": "±2 cm",
        "acc2": "Bereiche nur mit Tiefe", "acc2v": "±6 cm",
        "acc3": "Winkel und Diagonalen", "acc3v": "±1 %",
        "acc_note": "Strecken werden zwischen anker-verfeinerten Oberflächenpunkten gemessen, nie auf der versetzten Mittellinie. Kritische Maße trotzdem vor dem Zuschnitt mit dem Maßband prüfen — das steht auch im Protokoll.",
        "price_title": "Kostenlos testen, ehrlich kaufen",
        "price_sub": "Nur Krypto — keine Karte, keine Store-Gebühr, kein Konto.",
        "plan_free": "Kostenlos", "plan_free_p": "0 €", "plan_free_b": "Ein Raum, unbegrenzte Strecken, CSV und PDF mit Wasserzeichen.",
        "plan_m": "Pro monatlich", "plan_m_p": "10 € / Monat", "plan_m_b": "Unbegrenzte Räume, sauberes PDF, GLB + PLY.",
        "plan_y": "Pro jährlich", "plan_y_p": "100 € / Jahr", "plan_y_b": "Wie monatlich, zwei Monate gratis.", "plan_y_tag": "Bester Preis",
        "plan_l": "Lifetime", "plan_l_p": "999 € einmalig", "plan_l_b": "Einmal zahlen, dauerhaft nutzen.",
        "price_note": "Sieben Tage Pro sind gratis — ohne Karte, ohne Fragen. Bezahlt wird in SOL, BTC oder ETH; du bekommst einen Lizenzschlüssel, den die App offline prüft. Wenn ein Abo endet, wird nichts entzogen.",
        "crypto_title": "Zahlungsadressen",
        "lang_title": "Sechs Sprachen, ein Build",
        "lang_body": "Jeder Bildschirm und das exportierte Protokoll sind übersetzt — und der Build verweigert einen Release, in dem einer Sprache ein Schlüssel fehlt.",
        "faq_title": "Fragen von der Baustelle",
        "q1": "Funktioniert es ohne Internet?",
        "a1": "Ja — die App hat überhaupt keine Internet-Berechtigung. Nichts wird hoch- oder heruntergeladen; Tracking, Tiefe und Rendering laufen auf dem Telefon.",
        "q2": "Welche Handys werden unterstützt?",
        "a2": "Android-Geräte mit ARCore ab Android 7.0. Ein Tiefensensor (ToF) verbessert die Werte, ist aber nicht nötig.",
        "q3": "Ersetzt es den Laser?",
        "a3": "Für Strecken auf ebenen Wänden ja, auf wenige Zentimeter genau. Bei kritischen Maßen oder hinter einer Verkleidung vor dem Zuschnitt mit dem Maßband prüfen — die erwartete Abweichung steht auch im Protokoll.",
        "q4": "Wo liegen meine Aufmaße?",
        "a4": "In einer JSON-Datei pro Raum im privaten Speicher der App. Du kannst jedes Projekt samt Foto in einen Ordner deiner Wahl kopieren und auf einem anderen Telefon wiederherstellen.",
        "q5": "Brauche ich ein Konto?",
        "a5": "Nein. Keine Anmeldung, keine Cloud, keine Abo-Überwachung — eine Lizenz ist ein kurzer signierter Schlüssel, den die App offline prüft.",
        "q6": "Wie bezahle ich?",
        "a6": "In SOL, BTC oder ETH an die in der App angezeigte Adresse. Transaktions-ID und Gerätecode schicken, Lizenzschlüssel zurückbekommen.",
        "foot_licence": "Alle Rechte vorbehalten. Öffentlich für CI und Review — keine Open-Source-Lizenz.",
        "foot_contact": "Kontakt", "foot_repo": "Quellcode auf GitHub", "foot_langs": "Sprachen",
        "foot_by": "ConstructAR von",
        "cta_end_title": "Bereit für den nächsten Raum?",
        "cta_end_body": "Installieren, eine Wand messen, danach entscheiden.",
    },
}

# The remaining four languages are complete translations of the same key set; they live in their
# own module to keep this file readable.
from site_translations import ADDENDUM, EXTRA, MARKETING  # noqa: E402  (tools/ is on the path when run from there)

CONTENT.update(EXTRA)
for _lang, _extra in ADDENDUM.items():
    CONTENT[_lang].update(_extra)
for _lang, _extra in MARKETING.items():
    CONTENT[_lang].update(_extra)


def esc(value: str) -> str:
    return html.escape(value, quote=True)


def url_for(lang: str) -> str:
    return BASE_URL if lang == "en" else f"{BASE_URL}{lang}/"


def root_for(lang: str) -> str:
    return "" if lang == "en" else "../"


def price_row(lang: str, c: dict[str, str]) -> str:
    return "".join(
        f'<li><span class="sym">{esc(symbol)}</span><code>{esc(address)}</code></li>'
        for symbol, address in COINS
    )


def json_ld(lang: str, c: dict[str, str]) -> str:
    data = {
        "@context": "https://schema.org",
        "@graph": [
            {
                "@type": "SoftwareApplication",
                "name": "ConstructAR",
                "applicationCategory": "UtilitiesApplication",
                "operatingSystem": "Android",
                "softwareVersion": "1.0",
                "inLanguage": LANGS,
                "url": url_for(lang),
                "image": f"{BASE_URL}assets/logo.jpg",
                "description": c["meta_desc"],
                "featureList": [c[f"f{i}t"] for i in range(1, 8)],
                "author": {"@type": "Person", "name": DEVELOPER, "email": EMAIL},
                "offers": [
                    {"@type": "Offer", "name": c["plan_free"], "price": "0", "priceCurrency": "EUR"},
                    {"@type": "Offer", "name": c["plan_m"], "price": "10", "priceCurrency": "EUR"},
                    {"@type": "Offer", "name": c["plan_y"], "price": "100", "priceCurrency": "EUR"},
                    {"@type": "Offer", "name": c["plan_l"], "price": "999", "priceCurrency": "EUR"},
                ],
            },
            {
                "@type": "FAQPage",
                "inLanguage": lang,
                "mainEntity": [
                    {
                        "@type": "Question",
                        "name": c[f"q{i}"],
                        "acceptedAnswer": {"@type": "Answer", "text": c[f"a{i}"]},
                    }
                    for i in range(1, 9)
                ],
            },
        ],
    }
    return json.dumps(data, ensure_ascii=False, indent=None)


def page(lang: str) -> str:
    c = CONTENT[lang]
    root = root_for(lang)
    alternates = "".join(
        f'<link rel="alternate" hreflang="{other}" href="{url_for(other)}">\n'
        for other in LANGS
    ) + f'<link rel="alternate" hreflang="x-default" href="{BASE_URL}">\n'
    switcher = "".join(
        '<a href="{href}" hreflang="{code}"{current}>{code}</a>'.format(
            href="" if other == "en" else "../" + other + "/",
            code=other,
            current=' aria-current="true"' if other == lang else "",
        )
        for other in LANGS
    )
    features = "".join(
        f'<article class="card"><span class="mark">{i}</span><h3>{esc(c[f"f{i}t"])}</h3>'
        f'<p>{esc(c[f"f{i}b"])}</p></article>'
        for i in range(1, 8)
    )
    steps = "".join(
        f'<div class="step"><h3>{esc(c[f"s{i}t"])}</h3><p>{esc(c[f"s{i}b"])}</p></div>'
        for i in range(1, 5)
    )
    faq = "".join(
        f'<details><summary>{esc(c[f"q{i}"])}</summary><p>{esc(c[f"a{i}"])}</p></details>'
        for i in range(1, 9)
    )
    lang_links = "".join(
        f'<a href="{"" if other == "en" else "../" + other + "/"}" hreflang="{other}">'
        f'{LANG_NAMES[other]}</a>'
        for other in LANGS
    )
    og_alternates = "".join(
        f'<meta property="og:locale:alternate" content="{OG_LOCALES[other]}">\n'
        for other in LANGS if other != lang
    )

    return f"""<!DOCTYPE html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(c["meta_title"])}</title>
<meta name="description" content="{esc(c["meta_desc"])}">
<meta name="robots" content="index,follow,max-image-preview:large">
<meta name="author" content="{DEVELOPER}">
<meta name="theme-color" content="#0A0C07">
<link rel="canonical" href="{url_for(lang)}">
{alternates}<link rel="icon" href="{root}assets/favicon.png" sizes="64x64" type="image/png">
<link rel="apple-touch-icon" href="{root}assets/apple-touch-icon.png">
<meta property="og:type" content="website">
<meta property="og:site_name" content="ConstructAR">
<meta property="og:title" content="{esc(c["meta_title"])}">
<meta property="og:description" content="{esc(c["meta_desc"])}">
<meta property="og:url" content="{url_for(lang)}">
<meta property="og:image" content="{BASE_URL}assets/og-image.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:locale" content="{OG_LOCALES[lang]}">
{og_alternates}<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="{esc(c["meta_title"])}">
<meta name="twitter:description" content="{esc(c["meta_desc"])}">
<meta name="twitter:image" content="{BASE_URL}assets/og-image.png">
<script type="application/ld+json">{json_ld(lang, c)}</script>
<style>{CSS}</style>
</head>
<body>
<a class="skip" href="#main">{esc(c["skip"])}</a>

<header class="site-header">
  <div class="wrap nav">
    <a class="brand" href="{url_for(lang)}"><img src="{root}assets/logo.jpg" alt="ConstructAR" width="36" height="36"><span>ConstructAR</span></a>
    <nav class="nav-links" aria-label="ConstructAR">
      <a href="#features">{esc(c["nav_features"])}</a>
      <a href="#how">{esc(c["nav_how"])}</a>
      <a href="#pricing">{esc(c["nav_price"])}</a>
      <a href="#faq">{esc(c["nav_faq"])}</a>
      <a href="{REPO_URL}" rel="noopener">{esc(c["nav_repo"])}</a>
    </nav>
    <nav class="lang" aria-label="{esc(c["foot_langs"])}">{switcher}</nav>
  </div>
</header>

<main id="main">
  <section class="wrap hero">
    <div>
      <p class="eyebrow">{esc(c["eyebrow"])}</p>
      <h1>{esc(c["hero_h1"])}</h1>
      <p class="lede">{esc(c["hero_sub"])}</p>
      <div class="cta">
        <a class="btn btn-primary" href="{REPO_URL}" rel="noopener">{esc(c["hero_cta"])}</a>
        <a class="btn btn-ghost" href="#how">{esc(c["hero_cta2"])}</a>
      </div>
      <p class="note">{esc(c["hero_note"])}</p>
      <p class="trust"><span>{esc(c["trust1"])}</span><span>{esc(c["trust2"])}</span><span>{esc(c["trust3"])}</span></p>
      <p class="trust"><span>{esc(c["trust4"])}</span><span>{esc(c["trust5"])}</span><span>{esc(c["trust6"])}</span></p>
    </div>
    <div class="phone" aria-hidden="true">
      <div class="bar"><img src="{root}assets/logo.jpg" alt="" width="26" height="26"><b>{esc(c["mock_room"])}</b></div>
      <div class="p-steps"><div class="p-step done">✓ 1</div><div class="p-step done">✓ 2</div><div class="p-step">3</div></div>
      <div class="p-card high">
        <div class="p-label">{esc(c["s2t"])}</div>
        <div class="p-total">{esc(c["mock_total"])}</div>
        <div class="p-muted">3 × LSZH 3×2,5 · 16 mm</div>
      </div>
      <div class="p-card">
        <div class="p-row"><span>6,40 m</span><span>{esc(c["mock_cable"])}</span></div>
        <div class="p-row"><span>5,12 m</span><span>{esc(c["mock_cable"])}</span></div>
        <div class="p-row"><span>3,30 m</span><span>{esc(c["mock_duct"])}</span></div>
        <div class="p-btn">{esc(c["s4t"])}</div>
      </div>
      <div class="p-card"><div class="p-muted">16,30 m · 12 × Schelle · 4 × Kupplung</div></div>
    </div>
  </section>

  <section class="section" id="numbers" style="border-top:0; padding-top:8px">
    <div class="wrap">
      <div class="stats">
        <div class="stat"><b>{esc(c["stat1v"])}</b><span>{esc(c["stat1l"])}</span></div>
        <div class="stat"><b>{esc(c["stat2v"])}</b><span>{esc(c["stat2l"])}</span></div>
        <div class="stat"><b>{esc(c["stat3v"])}</b><span>{esc(c["stat3l"])}</span></div>
        <div class="stat"><b>{esc(c["stat4v"])}</b><span>{esc(c["stat4l"])}</span></div>
      </div>
    </div>
  </section>

  <section class="section" id="today">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["pain_title"])}</h2><p>{esc(c["pain_sub"])}</p></div>
      <div class="grid">
        <article class="card"><h3>{esc(c["pain1t"])}</h3><p>{esc(c["pain1b"])}</p></article>
        <article class="card"><h3>{esc(c["pain2t"])}</h3><p>{esc(c["pain2b"])}</p></article>
        <article class="card"><h3>{esc(c["pain3t"])}</h3><p>{esc(c["pain3b"])}</p></article>
      </div>
    </div>
  </section>

  <section class="section" id="features">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["feat_title"])}</h2><p>{esc(c["feat_sub"])}</p></div>
      <div class="grid">{features}</div>
    </div>
  </section>

  <section class="section" id="how">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["how_title"])}</h2><p>{esc(c["how_sub"])}</p></div>
      <div class="steps">{steps}</div>
    </div>
  </section>

  <section class="section" id="compare">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["cmp_title"])}</h2><p>{esc(c["cmp_sub"])}</p></div>
      <table class="acc cmp">
        <thead><tr><th>{esc(c["cmp_criterion"])}</th><th>{esc(c["cmp_col_us"])}</th><th>{esc(c["cmp_col_them"])}</th></tr></thead>
        <tbody>
          <tr><td>{esc(c["cmp1"])}</td><td class="us">{esc(c["cmp1us"])}</td><td class="them">{esc(c["cmp1them"])}</td></tr>
          <tr><td>{esc(c["cmp2"])}</td><td class="us">{esc(c["cmp2us"])}</td><td class="them">{esc(c["cmp2them"])}</td></tr>
          <tr><td>{esc(c["cmp3"])}</td><td class="us">{esc(c["cmp3us"])}</td><td class="them">{esc(c["cmp3them"])}</td></tr>
          <tr><td>{esc(c["cmp4"])}</td><td class="us">{esc(c["cmp4us"])}</td><td class="them">{esc(c["cmp4them"])}</td></tr>
          <tr><td>{esc(c["cmp5"])}</td><td class="us">{esc(c["cmp5us"])}</td><td class="them">{esc(c["cmp5them"])}</td></tr>
        </tbody>
      </table>
      <h3 style="margin-top:34px">{esc(c["trades_title"])}</h3>
      <div class="langs"><span>{esc(c["trades_list"])}</span></div>
    </div>
  </section>

  <section class="section" id="accuracy">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["acc_title"])}</h2><p>{esc(c["acc_sub"])}</p></div>
      <table class="acc">
        <thead><tr><th>{esc(c["acc_situation"])}</th><th>{esc(c["acc_tolerance"])}</th></tr></thead>
        <tbody>
          <tr><td>{esc(c["acc1"])}</td><td>{esc(c["acc1v"])}</td></tr>
          <tr><td>{esc(c["acc2"])}</td><td>{esc(c["acc2v"])}</td></tr>
          <tr><td>{esc(c["acc3"])}</td><td>{esc(c["acc3v"])}</td></tr>
        </tbody>
      </table>
      <p class="note" style="margin-top:18px; max-width:52em">{esc(c["acc_note"])}</p>
    </div>
  </section>

  <section class="section" id="pricing">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["price_title"])}</h2><p>{esc(c["price_sub"])}</p></div>
      <div class="plans">
        <div class="plan"><h3>{esc(c["plan_free"])}</h3><div class="price">{esc(c["plan_free_p"])}</div><p>{esc(c["plan_free_b"])}</p></div>
        <div class="plan"><h3>{esc(c["plan_m"])}</h3><div class="price">{esc(c["plan_m_p"])}</div><p>{esc(c["plan_m_b"])}</p></div>
        <div class="plan best"><div class="tag">{esc(c["plan_y_tag"])}</div><h3>{esc(c["plan_y"])}</h3><div class="price">{esc(c["plan_y_p"])}</div><p>{esc(c["plan_y_b"])}</p></div>
        <div class="plan"><h3>{esc(c["plan_l"])}</h3><div class="price">{esc(c["plan_l_p"])}</div><p>{esc(c["plan_l_b"])}</p></div>
      </div>
      <p class="note" style="margin-top:20px; max-width:52em">{esc(c["price_note"])}</p>
      <p class="anchor">{esc(c["anchor_line"])}</p>
      <div class="crypto">
        <h3>{esc(c["crypto_title"])}</h3>
        <ul>{price_row(lang, c)}</ul>
      </div>

      <h2 style="margin-top:44px">{esc(c["buy_title"])}</h2>
      <div class="steps" style="margin-top:22px">
        <div class="step"><h3>{esc(c["buy1t"])}</h3><p>{esc(c["buy1b"])}</p></div>
        <div class="step"><h3>{esc(c["buy2t"])}</h3><p>{esc(c["buy2b"])}</p></div>
        <div class="step"><h3>{esc(c["buy3t"])}</h3><p>{esc(c["buy3b"])}</p></div>
      </div>
    </div>
  </section>

  <section class="section" id="languages">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["lang_title"])}</h2><p>{esc(c["lang_body"])}</p></div>
      <div class="langs">{lang_links}</div>
    </div>
  </section>

  <section class="section faq" id="faq">
    <div class="wrap">
      <div class="section-head"><h2>{esc(c["faq_title"])}</h2></div>
      {faq}
    </div>
  </section>

  <section class="section">
    <div class="wrap" style="text-align:center">
      <h2>{esc(c["cta_end_title"])}</h2>
      <p class="lede" style="margin:0 auto 22px">{esc(c["cta_end_body"])}</p>
      <a class="btn btn-primary" href="{REPO_URL}" rel="noopener">{esc(c["hero_cta"])}</a>
    </div>
  </section>
</main>

<footer>
  <div class="wrap foot-grid">
    <div class="foot-brand">
      <img src="{root}assets/logo.jpg" alt="ConstructAR" width="46" height="46">
      <div>
        <strong>{esc(c["foot_by"])} {DEVELOPER}</strong><br>
        <a href="mailto:{EMAIL}">{EMAIL}</a>
      </div>
    </div>
    <div>
      <a href="{REPO_URL}" rel="noopener">{esc(c["foot_repo"])}</a><br>
      <span>{esc(c["foot_licence"])}</span>
    </div>
    <div style="max-width:22em">
      <strong>{esc(c["foot_langs"])}</strong><br>
      {esc("English, Deutsch, Español, Français, Italiano, Polski")}
    </div>
  </div>
</footer>

<div class="sticky-cta" role="complementary">
  <div>
    <a class="btn btn-primary" href="{REPO_URL}" rel="noopener">{esc(c["sticky_cta"])}</a>
    <small>{esc(c["sticky_price"])}</small>
  </div>
</div>
</body>
</html>
"""


def sitemap() -> str:
    entries = []
    for lang in LANGS:
        alternates = "".join(
            f'    <xhtml:link rel="alternate" hreflang="{other}" href="{url_for(other)}"/>\n'
            for other in LANGS
        )
        entries.append(
            "  <url>\n"
            f"    <loc>{url_for(lang)}</loc>\n"
            + alternates
            + "    <changefreq>monthly</changefreq>\n"
            "    <priority>1.0</priority>\n"
            "  </url>\n"
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        "<!-- Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com> -->\n"
        "<!-- All rights reserved. Proprietary and confidential - see LICENSE. -->\n"
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"\n'
        '        xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'
        + "".join(entries)
        + "</urlset>\n"
    )


def not_found() -> str:
    """One page for every 404 GitHub Pages can hand out, in all six languages at once.

    The visitor is lost by definition, so this page does not make them choose: the four words
    that matter are shown in every language the site speaks, and the way back is a real link.
    """
    headings = [
        ("en", "Page not found", "The link is dead, or the page has moved."),
        ("de", "Seite nicht gefunden", "Der Link ist tot oder die Seite ist umgezogen."),
        ("es", "Página no encontrada", "El enlace está roto o la página se ha movido."),
        ("fr", "Page introuvable", "Le lien est mort ou la page a déménagé."),
        ("it", "Pagina non trovata", "Il link non funziona o la pagina si è spostata."),
        ("pl", "Nie znaleziono strony", "Link nie działa albo strona została przeniesiona."),
    ]
    rows = "".join(
        f'<p lang="{code}"><strong>{esc(title)}</strong> — {esc(line)} '
        f'<a href="{"" if code == "en" else code + "/"}" hreflang="{code}">{LANG_NAMES[code]}</a></p>'
        for code, title, line in headings
    )
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>404 — ConstructAR</title>
<meta name="robots" content="noindex,follow">
<meta name="theme-color" content="#0A0C07">
<link rel="icon" href="assets/favicon.png" sizes="64x64" type="image/png">
<link rel="apple-touch-icon" href="assets/apple-touch-icon.png">
<style>{CSS}</style>
</head>
<body>
<main class="wrap" style="padding:64px 22px; max-width:760px">
  <a class="brand" href="{BASE_URL}"><img src="assets/logo.jpg" alt="ConstructAR" width="36" height="36"><span>ConstructAR</span></a>
  <h1 style="margin-top:32px">404</h1>
  <div class="langs" style="display:block">{rows}</div>
  <p style="margin-top:28px"><a class="btn btn-primary" href="{BASE_URL}">ConstructAR</a></p>
</main>
</body>
</html>
"""


def main() -> None:
    DOCS.mkdir(parents=True, exist_ok=True)
    (DOCS / ".nojekyll").write_text("", encoding="utf-8")
    (DOCS / "robots.txt").write_text(
        "# Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>\n"
        "User-agent: *\nAllow: /\n\n"
        f"Sitemap: {BASE_URL}sitemap.xml\n",
        encoding="utf-8",
    )
    (DOCS / "sitemap.xml").write_text(sitemap(), encoding="utf-8")
    (DOCS / "404.html").write_text(not_found(), encoding="utf-8")
    for lang in LANGS:
        target = DOCS / "index.html" if lang == "en" else DOCS / lang / "index.html"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(page(lang), encoding="utf-8")
        print(f"{target.relative_to(ROOT)}: {target.stat().st_size // 1024} KiB")


if __name__ == "__main__":
    main()
