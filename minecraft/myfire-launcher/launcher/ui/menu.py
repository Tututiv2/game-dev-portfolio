"""A drop-down built from plain widgets, because ``tk.Menu`` is native on Windows.

Tk's menus are real Windows ``HMENU``s on this platform, not Tk windows -- a posted one
reports a size of 1x1 and cannot be screenshotted, positioned or measured like a widget.
The practical consequence is that it takes the *system* theme, so the account switcher
would drop a light-grey Windows menu out of a black launcher. Everything else on the main
menu is drawn by hand for the same reason (see :mod:`launcher.ui.canvaskit`), so the
switcher is too.

What that costs is dismissal, which a real menu gets for free. Three things close this
one, and it needs all three: clicking an entry, pressing Escape, and losing focus (which
covers clicking anywhere else, including another application).

Hover changes colour and nothing else, deliberately -- a hover effect that *moves* a
widget out from under a stationary pointer is an infinite Enter/Leave loop that hangs the
process, and that has already shipped once here. See the check in the suite.
"""
from __future__ import annotations

import tkinter as tk

from . import theme

SEPARATOR = ("-", None)


class PopupMenu:
    """Rows of ``(label, action)``. ``action`` of ``None`` means an inert caption."""

    def __init__(self, master: tk.Misc, rows, *, font, min_width: int = 210) -> None:
        self.master = master
        self._closed = False
        self.top = tk.Toplevel(master)
        self.top.withdraw()
        self.top.overrideredirect(True)
        self.top.attributes("-topmost", True)
        # A 1 px outer frame is the border; overrideredirect windows have no chrome.
        self.top.configure(bg=theme.BORDER_HI)
        body = tk.Frame(self.top, bg=theme.PANEL)
        body.pack(padx=1, pady=1, fill="both", expand=True)

        self.items: list[tk.Label] = []
        for label, action in rows:
            if (label, action) == SEPARATOR:
                tk.Frame(body, bg=theme.BORDER, height=1).pack(fill="x", pady=4)
                continue
            item = tk.Label(body, text=label, bg=theme.PANEL,
                            fg=theme.FAINT if action is None else theme.TEXT,
                            font=font, anchor="w", padx=12, pady=6)
            item.pack(fill="x")
            if action is not None:
                item.configure(cursor="hand2")
                item.bind("<Enter>", lambda _e, w=item: w.configure(bg=theme.PANEL_SEL,
                                                                   fg=theme.ACCENT_HI))
                item.bind("<Leave>", lambda _e, w=item: w.configure(bg=theme.PANEL,
                                                                    fg=theme.TEXT))
                item.bind("<Button-1>", lambda _e, fn=action: self._chose(fn))
            self.items.append(item)

        self.top.bind("<Escape>", lambda _e: self.close())
        self.top.bind("<FocusOut>", lambda _e: self.close())
        self._min_width = min_width

    def show(self, x_root: int, y_root: int) -> "PopupMenu":
        self.top.update_idletasks()
        w = max(self._min_width, self.top.winfo_reqwidth())
        h = self.top.winfo_reqheight()
        self.top.geometry(f"{w}x{h}+{int(x_root)}+{int(y_root)}")
        self.top.deiconify()
        self.top.lift()
        # Without focus there is no FocusOut, and then clicking elsewhere would leave the
        # popup floating over the app for good.
        self.top.focus_force()
        return self

    def _chose(self, action) -> None:
        # Close first: an action may open a modal dialog, and a drop-down still hanging
        # over it is both wrong and, being topmost, in front of it.
        self.close()
        action()

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        try:
            self.top.destroy()
        except tk.TclError:
            pass
