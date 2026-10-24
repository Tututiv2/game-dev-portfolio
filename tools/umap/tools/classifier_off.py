"""Stand-in for the content classifier in a copy of the map that ships without one.

Same interface, every verdict clean. scan.py and build.py import a
classifier when it is present and this when it is not, so the rest of the
pipeline never has to ask which copy it is running in.
"""

ENABLED = False
LEVEL_NAME = {0: 'none', 1: 'partial', 2: 'full'}


def path_verdict(relpath):
    return 0, ''


def text_verdict(text, relpath=''):
    return 0, ''


def verdict(relpath, text=None):
    return 0, ''


def roll_up(file_levels, asset_levels):
    return 0
