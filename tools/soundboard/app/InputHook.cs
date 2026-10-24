using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace SoundBoard
{
    /// <summary>
    /// Low-level keyboard + mouse hooks. Chosen over RegisterHotKey because a hot-key
    /// ALWAYS eats the key — here each binding decides whether the game still sees it.
    ///
    /// The callback runs on the thread that installed the hook (our UI thread). If it
    /// takes longer than LowLevelHooksTimeout (~300 ms) Windows silently unhooks us,
    /// so the handler must never do real work — it decides, and hands off.
    /// </summary>
    public sealed class InputHook : IDisposable
    {
        public delegate bool TriggerHandler(int code, bool isMouse, bool ctrl, bool alt, bool shift, bool repeat);

        /// <summary>
        /// Return true to swallow the input (stop it reaching the game). Called for OS
        /// auto-repeat too, with repeat=true — answer the same way but don't re-fire,
        /// otherwise holding the key machine-guns the sound.
        /// </summary>
        public TriggerHandler OnTrigger;

        const int WH_KEYBOARD_LL = 13, WH_MOUSE_LL = 14;
        const int WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_SYSKEYDOWN = 0x104, WM_SYSKEYUP = 0x105;
        const int WM_MBUTTONDOWN = 0x207, WM_MBUTTONUP = 0x208;
        const int WM_XBUTTONDOWN = 0x20B, WM_XBUTTONUP = 0x20C;

        delegate IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr SetWindowsHookEx(int idHook, HookProc lpfn, IntPtr hMod, uint dwThreadId);
        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        static extern bool UnhookWindowsHookEx(IntPtr hhk);
        [DllImport("user32.dll")]
        static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr GetModuleHandle(string lpModuleName);
        [DllImport("user32.dll")]
        static extern short GetAsyncKeyState(int vKey);

        [StructLayout(LayoutKind.Sequential)]
        struct KBDLLHOOKSTRUCT { public int vkCode, scanCode, flags, time; public IntPtr dwExtraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        struct MSLLHOOKSTRUCT { public int x, y, mouseData, flags, time; public IntPtr dwExtraInfo; }

        IntPtr _kbdHook, _mouseHook;
        readonly HookProc _kbdProc, _mouseProc;   // fields: the delegates must outlive the hooks
        // Auto-repeat guard. Also remembers the swallow decision so key-up matches key-down.
        readonly HashSet<long> _held = new HashSet<long>();

        public bool Installed => _kbdHook != IntPtr.Zero;

        public InputHook()
        {
            _kbdProc = KbdCallback;
            _mouseProc = MouseCallback;
        }

        public void Install()
        {
            if (_kbdHook != IntPtr.Zero) return;
            var mod = GetModuleHandle(null);
            _kbdHook = SetWindowsHookEx(WH_KEYBOARD_LL, _kbdProc, mod, 0);
            _mouseHook = SetWindowsHookEx(WH_MOUSE_LL, _mouseProc, mod, 0);
        }

        static bool Down(int vk) => (GetAsyncKeyState(vk) & 0x8000) != 0;
        static bool Ctrl => Down(0x11);
        static bool Alt => Down(0x12);
        static bool Shift => Down(0x10);

        static long KeyOf(int code, bool mouse) => (mouse ? 1L << 32 : 0L) | (uint)code;

        bool Fire(int code, bool isMouse, bool repeat)
        {
            var h = OnTrigger;
            if (h == null) return false;
            try { return h(code, isMouse, Ctrl, Alt, Shift, repeat); }
            catch { return false; }
        }

        IntPtr KbdCallback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0)
            {
                int msg = (int)wParam;
                var d = Marshal.PtrToStructure<KBDLLHOOKSTRUCT>(lParam);

                // Injected events are handled too — nothing here injects keys, so there
                // is no loop to guard against, and it keeps macro keyboards / AHK working.
                long id = KeyOf(d.vkCode, false);
                if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN)
                {
                    bool repeat = !_held.Add(id);                // HashSet.Add is false if already held
                    if (Fire(d.vkCode, false, repeat)) return (IntPtr)1;
                }
                else if (msg == WM_KEYUP || msg == WM_SYSKEYUP)
                {
                    _held.Remove(id);
                }
            }
            return CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
        }

        IntPtr MouseCallback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0)
            {
                int msg = (int)wParam;
                if (msg == WM_MBUTTONDOWN || msg == WM_XBUTTONDOWN ||
                    msg == WM_MBUTTONUP || msg == WM_XBUTTONUP)
                {
                    var d = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
                    int code;
                    if (msg == WM_MBUTTONDOWN || msg == WM_MBUTTONUP) code = Trig.MouseMiddle;
                    else code = ((d.mouseData >> 16) & 0xFFFF) == 1 ? Trig.MouseX1 : Trig.MouseX2;

                    long id = KeyOf(code, true);
                    if (msg == WM_MBUTTONDOWN || msg == WM_XBUTTONDOWN)
                    {
                        bool repeat = !_held.Add(id);
                        if (Fire(code, true, repeat)) return (IntPtr)1;
                    }
                    else
                    {
                        _held.Remove(id);
                    }
                }
            }
            return CallNextHookEx(IntPtr.Zero, nCode, wParam, lParam);
        }

        public void Dispose()
        {
            if (_kbdHook != IntPtr.Zero) { UnhookWindowsHookEx(_kbdHook); _kbdHook = IntPtr.Zero; }
            if (_mouseHook != IntPtr.Zero) { UnhookWindowsHookEx(_mouseHook); _mouseHook = IntPtr.Zero; }
        }
    }
}
