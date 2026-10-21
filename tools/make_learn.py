#!/usr/bin/env python3
"""Writes app/assets/learn.json: recent videos from kids' learning channels, for the Kids tab's "Learn today" card.

    python3 tools/make_learn.py

Each channel's public feed (youtube.com/feeds/videos.xml, no key) lists its 15 newest uploads. The feed often answers
404 or 500 for a while, so it's tried a few times, and a channel that never answers keeps its videos from the last
run. Shorts and videos without a full-size picture are left out. The channels take turns, so two days in a row never
come from the same one. Run it again now and then for new videos, then build.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from html import unescape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'assets', 'learn.json')

# Name on the card, channel id. Younger kids (about 3 to 6) first, then older (about 6 to 10). Edit freely.
CHANNELS = [
    ('Numberblocks', 'UCPlwvN0w4qFSP1FllALB92w'),
    ('Sesame Street', 'UCoookXUzPciGrEZEXmh4Jjg'),
    ('Ms Rachel', 'UCG2CL6EUjG8TVT1Tpl9nJdg'),
    ('Jack Hartmann', 'UCVcQH8A634mauPrGbWs7QlQ'),
    ('Super Simple Songs', 'UCLsooMJoIpl_7ux2jvdPB-Q'),
    ('SciShow Kids', 'UCRFIPG2u1DxKLNuE3y2SjHA'),
    ('Crash Course Kids', 'UCONtPx56PSebXJOxbFv-2jQ'),
    ('Nat Geo Kids', 'UCXVCgDuD_QCkI7gTKU7-tpg'),
    ('Art for Kids Hub', 'UC5XMF3Inoi8R9nSI8ChOsdQ'),
    ('TVOkids', 'UCGPPdIn67ZCLhhHABoQpNUw'),
]
PER_CHANNEL = 12
TRIES = 8


def fetch(url, method='GET'):
    req = urllib.request.Request(url, method=method, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode('utf-8') if method == 'GET' else ''


def feed(channel_id):
    for attempt in range(TRIES):
        try:
            return fetch('https://www.youtube.com/feeds/videos.xml?channel_id=' + channel_id)
        except (urllib.error.URLError, TimeoutError):
            time.sleep(2 + attempt)
    return None


def has_picture(video):
    try:
        fetch('https://i.ytimg.com/vi/%s/maxresdefault.jpg' % video, 'HEAD')
        return True
    except urllib.error.HTTPError:
        return False


EMOJI = re.compile('[\U0001F000-\U0001FFFF☀-➿⬀-⯿️‍⃣]')


def clean(title):
    """The card's font has no emoji; and only the first part of "Counting to 10 | Compilation | Numberblocks"."""
    text = re.sub(r'\s+', ' ', EMOJI.sub('', title)).strip()
    first = re.split(r'\s+[|–]\s+|\s+-\s+', text)[0].strip()
    return first if len(first) >= 8 else text


def videos(name, xml):
    out = []
    for entry in re.findall(r'<entry>(.*?)</entry>', xml, re.S):
        video = re.search(r'<yt:videoId>([\w-]+)</yt:videoId>', entry)
        title = re.search(r'<title>(.*?)</title>', entry, re.S)
        link = re.search(r'<link rel="alternate" href="([^"]+)"', entry)
        if not video or not title or (link and '/shorts/' in link.group(1)):
            continue
        text = unescape(title.group(1)).strip()
        # Live streams (🔴, "LIVE!") may be over or last for hours.
        if '\U0001F534' in text or re.search(r'\bLIVE\b', text):
            continue
        text = clean(text)
        if not text or not has_picture(video.group(1)):
            continue
        out.append({'v': video.group(1), 't': text, 'c': name})
        if len(out) >= PER_CHANNEL:
            break
    return out


def main():
    old = {}
    if os.path.exists(OUT):
        with open(OUT, encoding='utf-8') as f:
            for item in json.load(f):
                old.setdefault(item['c'], []).append(item)
    lists = []
    for name, channel_id in CHANNELS:
        xml = feed(channel_id)
        found = videos(name, xml) if xml else []
        if not found and old.get(name):
            print('%-20s feed unavailable, kept %d from the last run' % (name, len(old[name])))
            found = old[name]
        else:
            print('%-20s %d videos' % (name, len(found)))
        if found:
            lists.append(found)
    # Round robin: one from each channel in turn.
    mixed = []
    for i in range(max((len(l) for l in lists), default=0)):
        mixed.extend(l[i] for l in lists if i < len(l))
    if not mixed:
        sys.exit('No videos: every feed failed. Try again in a few minutes.')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(mixed, f, ensure_ascii=False, indent=0)
        f.write('\n')
    print('Wrote %s: %d videos (%d days)' % (os.path.relpath(OUT, ROOT), len(mixed), len(mixed)))


if __name__ == '__main__':
    main()
