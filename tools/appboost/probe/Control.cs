using System.Runtime.InteropServices;

namespace Probe;

/// <summary>
/// The control experiment for the GetService blocker.
///
/// Process loopback's client refuses GetService for every IID, including ones every client
/// must support. Two stories fit that: our vtable call is wrong, or the process-loopback
/// client really is different. This runs the SAME IAudioClient declaration and the SAME
/// GetServiceRaw against a plain device client from IMMDevice::Activate.
///
///   works here  -> the call is fine, the process-loopback client is the odd one
///   fails here  -> the declaration is wrong and every earlier result is a red herring
/// </summary>
internal static class Control
{
    private static readonly Guid CLSID_MMDeviceEnumerator = new("BCDE0395-E52F-467C-8E3D-C4579291692E");

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams,
            [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        [PreserveSig] int OpenPropertyStore(int access, out IntPtr store);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out int state);
    }

    [DllImport("ole32.dll")]
    private static extern int CoCreateInstance(ref Guid clsid, IntPtr outer, uint clsCtx,
        ref Guid iid, out IntPtr ppv);

    public static void Run()
    {
        Console.WriteLine("=== CONTROL: plain device client, same declaration, same raw call ===");

        // CoCreateInstance directly, NOT Type.GetTypeFromCLSID: that call permanently caches a
        // synthesized __ComObject type against this CLSID, and NAudio's own MMDeviceEnumerator
        // then fails to cast when it activates the same coclass later in the run.
        var clsid = CLSID_MMDeviceEnumerator;
        var enumIid = typeof(IMMDeviceEnumerator).GUID;
        var hrCreate = CoCreateInstance(ref clsid, IntPtr.Zero, 1 /* CLSCTX_INPROC_SERVER */,
            ref enumIid, out var pEnum);
        Console.WriteLine($"  CoCreateInstance(MMDeviceEnumerator) hr=0x{hrCreate:X8}");
        if (hrCreate != 0) return;

        var enumerator = (IMMDeviceEnumerator)Marshal.GetTypedObjectForIUnknown(
            pEnum, typeof(IMMDeviceEnumerator));
        Marshal.Release(pEnum);

        // eRender = 0, eConsole = 0
        var hr = enumerator.GetDefaultAudioEndpoint(0, 0, out var device);
        Console.WriteLine($"  GetDefaultAudioEndpoint hr=0x{hr:X8}");
        if (hr != 0) return;

        var clientIid = typeof(ProcessLoopback.IAudioClient).GUID;
        hr = device.Activate(ref clientIid, 1 /* CLSCTX_INPROC_SERVER */, IntPtr.Zero, out var obj);
        Console.WriteLine($"  IMMDevice::Activate(IAudioClient) hr=0x{hr:X8}");
        if (hr != 0 || obj is null) return;

        var client = (ProcessLoopback.IAudioClient)obj;

        hr = client.GetMixFormat(out var mixFormat);
        Console.WriteLine($"  GetMixFormat hr=0x{hr:X8}");
        if (hr != 0) return;

        // Loopback on a render endpoint, so the control is as close to the real case as a
        // normal device gets: same share mode, same flag, also a capture client at the end.
        const uint AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
        hr = client.Initialize(0, AUDCLNT_STREAMFLAGS_LOOPBACK, 10_000_000, 0, mixFormat, IntPtr.Zero);
        Console.WriteLine($"  Initialize hr=0x{hr:X8}");
        if (hr != 0) return;

        var bufHr = client.GetBufferSize(out var frames);
        Console.WriteLine($"  GetBufferSize hr=0x{bufHr:X8} frames={frames}");

        ProcessLoopback.DumpServices(client, "control");

        // And the runtime's own stub, for the same IID, on the same object.
        var iid = typeof(ProcessLoopback.IAudioCaptureClient).GUID;
        var stubHr = client.GetService(ref iid, out var svc);
        Console.WriteLine($"  [stub]    GetService(IAudioCaptureClient) hr=0x{stubHr:X8} ptr=0x{svc:X}");
        if (stubHr == 0 && svc != IntPtr.Zero) Marshal.Release(svc);

        Marshal.FreeCoTaskMem(mixFormat);
        Marshal.ReleaseComObject(client);
        Console.WriteLine();
    }
}
