"""Visual language for the app — one place, so nothing drifts.

Dark, quiet, and out of the way: the video is the bright thing on screen, the
chrome is not. Accent is used sparingly — selection, primary action, progress —
so that when it appears it means something.
"""

from __future__ import annotations

# --- palette ---------------------------------------------------------------

BG          = "#0E0E13"     # window
PANEL       = "#15151C"     # cards, sidebars
PANEL_HI    = "#1C1C25"     # hover
BORDER      = "#26262F"
BORDER_HI   = "#33333F"

TEXT        = "#E9E9F0"
TEXT_DIM    = "#9A9AAB"
TEXT_FAINT  = "#63637A"

ACCENT      = "#7C5CFF"
ACCENT_HI   = "#8F73FF"
ACCENT_DIM  = "#463596"

OK          = "#3DD68C"
WARN        = "#F0B429"
ERR         = "#FF5C5C"

RADIUS      = 10

FONT_STACK = '"Poppins", "Segoe UI Variable Display", "Segoe UI", sans-serif'


def qss() -> str:
    return f"""
* {{
    font-family: {FONT_STACK};
    font-size: 13px;
    color: {TEXT};
}}

QWidget#Root, QMainWindow {{ background: {BG}; }}

QLabel[role="h1"]     {{ font-size: 19px; font-weight: 600; letter-spacing: 0.2px; }}
QLabel[role="h2"]     {{ font-size: 14px; font-weight: 600; }}
QLabel[role="section"]{{ font-size: 11px; font-weight: 600; color: {TEXT_FAINT};
                         letter-spacing: 1.2px; }}
QLabel[role="dim"]    {{ color: {TEXT_DIM}; }}
QLabel[role="faint"]  {{ color: {TEXT_FAINT}; font-size: 12px; }}
QLabel[role="mono"]   {{ font-family: "Consolas", monospace; color: {TEXT_DIM}; }}

/* ---------- panels ---------- */
QFrame[role="panel"] {{
    background: {PANEL};
    border: 1px solid {BORDER};
    border-radius: {RADIUS}px;
}}
QFrame[role="divider"] {{ background: {BORDER}; border: none; max-height: 1px; }}

/* ---------- buttons ---------- */
QPushButton {{
    background: {PANEL_HI};
    border: 1px solid {BORDER_HI};
    border-radius: 8px;
    padding: 8px 16px;
    font-weight: 500;
}}
QPushButton:hover  {{ background: #24242F; border-color: #414150; }}
QPushButton:pressed{{ background: #1A1A22; }}
QPushButton:disabled {{ color: {TEXT_FAINT}; background: #131319;
                        border-color: {BORDER}; }}

QPushButton[kind="primary"] {{
    background: {ACCENT}; border: 1px solid {ACCENT};
    color: #FFFFFF; font-weight: 600; padding: 9px 20px;
}}
QPushButton[kind="primary"]:hover   {{ background: {ACCENT_HI}; border-color: {ACCENT_HI}; }}
QPushButton[kind="primary"]:pressed {{ background: {ACCENT_DIM}; }}
QPushButton[kind="primary"]:disabled{{ background: #2A2440; border-color: #2A2440;
                                       color: #6D6A85; }}

QPushButton[kind="ghost"] {{ background: transparent; border: 1px solid transparent;
                             color: {TEXT_DIM}; padding: 6px 10px; }}
QPushButton[kind="ghost"]:hover {{ background: {PANEL_HI}; color: {TEXT}; }}

QPushButton[kind="danger"] {{ background: transparent; border: 1px solid #4A2630;
                              color: {ERR}; }}
QPushButton[kind="danger"]:hover {{ background: #2A1A20; }}

/* segmented control */
QPushButton[kind="segment"] {{
    background: transparent; border: none; border-radius: 6px;
    padding: 7px 10px; color: {TEXT_DIM}; font-weight: 500; text-align: center;
}}
QPushButton[kind="segment"]:hover {{ color: {TEXT}; background: {PANEL_HI}; }}
QPushButton[kind="segment"]:checked {{ background: {ACCENT}; color: #FFFFFF;
                                       font-weight: 600; }}

/* ---------- inputs ---------- */
QComboBox, QLineEdit, QSpinBox {{
    background: {PANEL_HI}; border: 1px solid {BORDER_HI};
    border-radius: 8px; padding: 7px 10px; selection-background-color: {ACCENT};
}}
QComboBox:hover, QLineEdit:hover {{ border-color: #414150; }}
QLineEdit:focus, QComboBox:focus {{ border-color: {ACCENT}; }}
QComboBox::drop-down {{ border: none; width: 22px; }}
QComboBox QAbstractItemView {{
    background: {PANEL}; border: 1px solid {BORDER_HI}; border-radius: 8px;
    selection-background-color: {ACCENT}; padding: 4px; outline: none;
}}

/* ---------- sliders ---------- */
QSlider::groove:horizontal {{ height: 4px; background: #2A2A35; border-radius: 2px; }}
QSlider::sub-page:horizontal {{ background: {ACCENT}; border-radius: 2px; }}
QSlider::handle:horizontal {{
    background: #FFFFFF; width: 14px; height: 14px;
    margin: -6px 0; border-radius: 7px;
}}
QSlider::handle:horizontal:hover {{ background: {ACCENT_HI}; }}

/* ---------- progress ---------- */
QProgressBar {{
    background: #1E1E27; border: none; border-radius: 4px;
    height: 6px; text-align: center; color: transparent;
}}
QProgressBar::chunk {{ background: {ACCENT}; border-radius: 4px; }}

/* ---------- lists ---------- */
QListWidget, QScrollArea {{ background: transparent; border: none; outline: none; }}
QListWidget::item {{
    background: {PANEL}; border: 1px solid {BORDER};
    border-radius: 8px; padding: 9px 11px; margin: 0px 2px 6px 2px;
    color: {TEXT_DIM};
}}
QListWidget::item:hover {{ background: {PANEL_HI}; border-color: {BORDER_HI};
                           color: {TEXT}; }}
QListWidget::item:selected {{ background: #221C40; border-color: {ACCENT};
                              color: {TEXT}; }}

/* ---------- scrollbars ---------- */
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: #2E2E3A; border-radius: 5px;
                               min-height: 30px; }}
QScrollBar::handle:vertical:hover {{ background: #3D3D4C; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; width: 0; }}
QScrollBar::add-page, QScrollBar::sub-page {{ background: transparent; }}
QScrollBar:horizontal {{ background: transparent; height: 10px; margin: 2px; }}
QScrollBar::handle:horizontal {{ background: #2E2E3A; border-radius: 5px;
                                 min-width: 30px; }}

/* ---------- misc ---------- */
QToolTip {{
    background: #22222C; color: {TEXT};
    border: 1px solid {BORDER_HI}; border-radius: 6px; padding: 6px 9px;
}}
QDialog {{ background: {BG}; }}
QCheckBox::indicator, QRadioButton::indicator {{
    width: 16px; height: 16px; border-radius: 5px;
    border: 1px solid {BORDER_HI}; background: {PANEL_HI};
}}
QCheckBox::indicator:checked, QRadioButton::indicator:checked {{
    background: {ACCENT}; border-color: {ACCENT};
}}
QRadioButton::indicator {{ border-radius: 8px; }}
QSplitter::handle {{ background: transparent; width: 10px; }}

/* ---------- tabs ---------- */
QTabWidget::pane {{ border: none; border-top: 1px solid {BORDER}; top: -1px; }}
QTabBar {{ qproperty-drawBase: 0; }}
QTabBar::tab {{
    background: transparent; border: none; border-bottom: 2px solid transparent;
    padding: 8px 14px; margin-right: 2px; color: {TEXT_DIM}; font-weight: 500;
}}
QTabBar::tab:hover {{ color: {TEXT}; }}
QTabBar::tab:selected {{ color: {TEXT}; border-bottom-color: {ACCENT};
                         font-weight: 600; }}
"""
