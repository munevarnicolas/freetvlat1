#!/usr/bin/env python3
"""Writes app/assets/verses.json: the verse of the day (config.properties title=verse), King James Version.

    python3 tools/make_verses.py

Takes each reference's text from bible-api.com (free, no key; the KJV is in the public domain), keeps the ones short
enough for two lines at the top of the home screen, and keeps the order below: day N shows verse N (it starts over
after the last). Edit the list freely and run it again, then build.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'assets', 'verses.json')
MAX = 170       # characters, with the reference: two lines at the top of the home screen

REFERENCES = [
    'John 3:16', 'Proverbs 3:5', 'Philippians 4:13', 'Psalms 23:1', 'Isaiah 41:10', 'Joshua 1:9', 'Romans 8:28',
    'Jeremiah 29:11', 'Matthew 11:28', 'Psalms 46:1', 'Isaiah 40:31', 'Proverbs 3:6', 'Psalms 118:24',
    'Lamentations 3:22', 'Lamentations 3:23', 'Psalms 37:4', 'Matthew 6:33', '2 Timothy 1:7', 'Psalms 119:105',
    'Hebrews 13:8', 'Psalms 34:8', 'Romans 12:12', '1 Peter 5:7', 'Psalms 27:1', 'Nahum 1:7', 'Psalms 55:22',
    'Proverbs 18:10', 'John 14:27', 'Psalms 91:1', 'Isaiah 26:3', 'Psalms 121:1', 'Psalms 121:2', 'Galatians 6:9',
    'Romans 15:13', 'John 16:33', 'Colossians 3:23', 'Psalms 16:8', 'Micah 6:8', 'Proverbs 16:3', 'Psalms 145:18',
    'Deuteronomy 31:6', 'Zephaniah 3:17', 'Psalms 9:10', 'John 8:12', 'Matthew 5:14', 'Matthew 5:16', 'James 1:5',
    'Proverbs 17:22', 'Psalms 103:2', 'Ephesians 2:8', 'Psalms 139:14', 'Isaiah 43:2', 'John 15:13', '1 John 4:19',
    '1 Corinthians 16:14', 'Psalms 56:3', 'Psalms 30:5', 'Romans 5:8', 'Psalms 62:1', 'Exodus 14:14',
    'Psalms 107:1', 'John 10:10', 'Matthew 19:26', 'Mark 9:23', 'Hebrews 11:1', 'Psalms 19:14', 'Proverbs 4:23',
    'Psalms 51:10', 'Isaiah 12:2', 'Psalms 28:7', 'Psalms 73:26', '2 Corinthians 5:7', 'Romans 10:17',
    'Philippians 4:6', 'Philippians 4:19', 'Psalms 4:8', 'Psalms 3:3', 'Proverbs 15:1', 'Ecclesiastes 3:1',
    'John 13:34', '1 Thessalonians 5:16', '1 Thessalonians 5:17', '1 Thessalonians 5:18', 'Psalms 100:4',
    'Psalms 136:1', 'James 1:17', 'Psalms 46:10', 'Isaiah 9:6', 'John 11:25', 'John 14:6', 'Revelation 21:4',
    'Matthew 28:20', 'Psalms 32:8', 'Proverbs 22:6', 'Psalms 127:3', 'Ephesians 6:10', 'Psalms 20:4',
    'Numbers 6:24', 'Numbers 6:25', 'Numbers 6:26', 'Psalms 90:12', 'Proverbs 27:17', 'Romans 8:31',
    'Romans 8:37', '1 Corinthians 13:4', '1 Corinthians 13:13', 'Galatians 5:22', 'Ephesians 4:32',
    'Colossians 3:14', 'Hebrews 12:1', 'James 4:8', '1 Peter 3:15', '1 John 1:9', '1 John 4:18', 'Psalms 5:3',
    'Psalms 18:2', 'Psalms 25:4', 'Psalms 31:24', 'Psalms 37:5', 'Psalms 40:1', 'Psalms 42:11', 'Psalms 63:1',
    'Psalms 84:11', 'Psalms 86:5', 'Psalms 94:19', 'Psalms 112:7', 'Psalms 118:6', 'Psalms 133:1', 'Psalms 143:8',
    'Psalms 147:3', 'Proverbs 11:25', 'Proverbs 12:25', 'Proverbs 16:9', 'Proverbs 19:21', 'Proverbs 31:25',
    'Isaiah 40:8', 'Isaiah 54:10', 'Isaiah 55:8', 'Isaiah 58:11', 'Jeremiah 17:7', 'Jeremiah 33:3',
    'Habakkuk 3:19', 'Zechariah 4:6', 'Malachi 3:10', 'Matthew 5:9', 'Matthew 7:7', 'Matthew 22:37',
    'Mark 11:24', 'Luke 1:37', 'Luke 6:31', 'John 1:5', 'John 15:5', 'Acts 1:8', 'Romans 12:2', 'Romans 12:21',
    '2 Corinthians 12:9', 'Galatians 2:20', 'Ephesians 3:20', 'Philippians 1:6', 'Philippians 4:4',
    'Colossians 3:2', '2 Thessalonians 3:3', 'Hebrews 4:16', 'Hebrews 10:23', 'James 1:2', '1 Peter 2:9',
    '1 John 3:18', '3 John 1:2', 'Psalms 150:6',
]


def fetch(ref):
    url = 'https://bible-api.com/' + urllib.parse.quote(ref) + '?translation=kjv'
    for attempt in range(6):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'free-my-tv make_verses.py'})
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(5 * (attempt + 1))      # 429: the API allows about 15 requests in 30 seconds
        except urllib.error.URLError:
            time.sleep(5)
    return None


def main():
    out, skipped = [], []
    for ref in REFERENCES:
        data = fetch(ref)
        time.sleep(2.2)
        if not data or not data.get('text'):
            skipped.append(ref + ' (not found)')
            continue
        text = re.sub(r'\s+', ' ', data['text']).strip()
        name = data.get('reference', ref)
        if len(text) + len(name) + 3 > MAX:
            skipped.append(ref + ' (too long)')
            continue
        out.append({'r': name, 't': text})
        print('%-22s %s' % (name, text[:70]))
    if not out:
        sys.exit('No verses: bible-api.com did not answer. Try again in a few minutes.')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=0)
        f.write('\n')
    print('Wrote %s: %d verses. Skipped: %s' % (os.path.relpath(OUT, ROOT), len(out), ', '.join(skipped) or 'none'))


if __name__ == '__main__':
    main()
