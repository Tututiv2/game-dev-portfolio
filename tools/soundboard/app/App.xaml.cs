using System;
using System.Threading;
using System.Windows;

namespace SoundBoard
{
    public partial class App : Application
    {
        // Every instance installs its own global keyboard hook. Two instances means two
        // hooks, and disabling the one you can see does nothing about the other — which
        // is exactly how you end up hearing sounds from an app you just switched off.
        // So: one instance, and launching again just re-shows the one already running.
        const string MutexName = @"Local\SoundBoard.SingleInstance";
        const string ShowEventName = @"Local\SoundBoard.ShowWindow";

        Mutex _mutex;
        EventWaitHandle _showSignal;

        protected override void OnStartup(StartupEventArgs e)
        {
            bool isFirst;
            _mutex = new Mutex(true, MutexName, out isFirst);

            if (!isFirst)
            {
                try
                {
                    EventWaitHandle existing;
                    if (EventWaitHandle.TryOpenExisting(ShowEventName, out existing))
                    {
                        existing.Set();          // poke the running copy to show itself
                        existing.Dispose();
                    }
                }
                catch { }
                Shutdown();
                return;
            }

            _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
            var listener = new Thread(WaitForShowRequests) { IsBackground = true, Name = "show-listener" };
            listener.Start();

            base.OnStartup(e);

            DispatcherUnhandledException += (s, ev) =>
            {
                MessageBox.Show(ev.Exception.ToString(), "SoundBoard hit a problem",
                    MessageBoxButton.OK, MessageBoxImage.Error);
                ev.Handled = true;
            };
        }

        void WaitForShowRequests()
        {
            while (true)
            {
                try
                {
                    if (!_showSignal.WaitOne()) continue;
                }
                catch { return; }   // disposed on shutdown

                try
                {
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        var w = MainWindow as MainWindow;
                        if (w != null) w.RestoreFromAnywhere();
                    }));
                }
                catch { return; }
            }
        }

        protected override void OnExit(ExitEventArgs e)
        {
            try { if (_showSignal != null) _showSignal.Dispose(); } catch { }
            try { if (_mutex != null) { _mutex.ReleaseMutex(); _mutex.Dispose(); } } catch { }
            base.OnExit(e);
        }
    }
}
