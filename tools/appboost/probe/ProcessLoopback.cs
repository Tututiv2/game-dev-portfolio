using System.Runtime.InteropServices;
using System.Threading;

namespace Probe;

/// <summary>
/// WASAPI process loopback — capture the audio of ONE process, with no virtual cable and
/// no routing. Windows 10 2004+ only. NAudio does not wrap this, so the COM plumbing is
/// hand-written here.
///
/// The activation path is a magic string rather than a real device, and the target process
/// is passed through a PROPVARIANT blob. Get any of that wrong and ActivateAudioInterfaceAsync
/// simply hands back an error code instead of an audio client.
/// </summary>
internal static class ProcessLoopback
{
    private const string VirtualAudioDeviceProcessLoopback = "VAD\\Process_Loopback";
    private const int AudioClientActivationTypeProcessLoopback = 1;
    private const int ProcessLoopbackModeIncludeTargetProcessTree = 0;

    private const int AUDCLNT_SHAREMODE_SHARED = 0;
    private const uint AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private const uint AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    private const int VT_BLOB = 65;

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessLoopbackParams
    {
        public uint TargetProcessId;
        public int ProcessLoopbackMode;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ActivationParams
    {
        public int ActivationType;
        public ProcessLoopbackParams LoopbackParams;
    }

    // PROPVARIANT, only the BLOB arm. The union starts at offset 8 on both x86 and x64.
    [StructLayout(LayoutKind.Explicit)]
    private struct PropVariantBlob
    {
        [FieldOffset(0)] public ushort vt;
        [FieldOffset(2)] public ushort r1;
        [FieldOffset(4)] public ushort r2;
        [FieldOffset(6)] public ushort r3;
        [FieldOffset(8)] public int cbSize;
        [FieldOffset(16)] public IntPtr pBlobData;
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceAsyncOperation
    {
        void GetActivateResult(out int activateResult,
            [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IActivateAudioInterfaceCompletionHandler
    {
        void ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint streamFlags, long bufferDuration,
            long periodicity, IntPtr format, IntPtr audioSessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferFrameCount);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint padding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, IntPtr closestMatch);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, out IntPtr service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint framesToRead, out uint flags,
            out long devicePosition, out long qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint framesWritten);
        [PreserveSig] int GetNextPacketSize(out uint framesInNextPacket);
    }

    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = false)]
    private static extern void ActivateAudioInterfaceAsync(
        [MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
        [MarshalAs(UnmanagedType.LPStruct)] Guid riid,
        IntPtr activationParams,
        IActivateAudioInterfaceCompletionHandler completionHandler,
        out IActivateAudioInterfaceAsyncOperation operation);

    private sealed class Handler : IActivateAudioInterfaceCompletionHandler
    {
        public readonly ManualResetEventSlim Done = new(false);
        public object? Result;
        public int Hr;

        public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
        {
            try
            {
                operation.GetActivateResult(out Hr, out var iface);
                Result = iface;
            }
            catch (Exception ex)
            {
                Hr = Marshal.GetHRForException(ex);
            }
            finally
            {
                Done.Set();
            }
        }
    }

    /// <summary>Activates a loopback IAudioClient bound to one process id.</summary>
    public static IAudioClient Activate(uint processId)
    {
        // PROBE_ACTIVATION_TYPE lets us pass a deliberately invalid type. If activation still
        // succeeds with garbage here, the params blob is not reaching the API and we have been
        // getting a plain default client all along.
        var activationType = int.TryParse(Environment.GetEnvironmentVariable("PROBE_ACTIVATION_TYPE"), out var t)
            ? t
            : AudioClientActivationTypeProcessLoopback;
        Console.WriteLine($"  activation type = {activationType}");

        var pars = new ActivationParams
        {
            ActivationType = activationType,
            LoopbackParams = new ProcessLoopbackParams
            {
                TargetProcessId = processId,
                ProcessLoopbackMode = ProcessLoopbackModeIncludeTargetProcessTree,
            },
        };

        var parsPtr = Marshal.AllocHGlobal(Marshal.SizeOf<ActivationParams>());
        var propPtr = Marshal.AllocHGlobal(Marshal.SizeOf<PropVariantBlob>());
        try
        {
            Marshal.StructureToPtr(pars, parsPtr, false);
            var prop = new PropVariantBlob
            {
                vt = VT_BLOB,
                cbSize = Marshal.SizeOf<ActivationParams>(),
                pBlobData = parsPtr,
            };
            Marshal.StructureToPtr(prop, propPtr, false);

            var handler = new Handler();
            ActivateAudioInterfaceAsync(
                VirtualAudioDeviceProcessLoopback,
                typeof(IAudioClient).GUID,
                propPtr,
                handler,
                out _);

            if (!handler.Done.Wait(5000))
            {
                throw new TimeoutException("Activation never completed.");
            }

            if (handler.Hr != 0 || handler.Result is null)
            {
                throw new InvalidOperationException($"Activation failed: 0x{handler.Hr:X8}");
            }

            return (IAudioClient)handler.Result;
        }
        finally
        {
            Marshal.FreeHGlobal(propPtr);
            Marshal.FreeHGlobal(parsPtr);
        }
    }

    /// <summary>
    /// Process loopback will not tell you a mix format, so we must state one. 44.1k stereo
    /// float is what the engine hands back for shared-mode clients on this hardware.
    /// </summary>
    public static IntPtr BuildFloatFormat(int sampleRate, int channels)
    {
        // WAVEFORMATEX, WAVE_FORMAT_IEEE_FLOAT (3)
        var ptr = Marshal.AllocHGlobal(18);
        Marshal.WriteInt16(ptr, 0, 3);
        Marshal.WriteInt16(ptr, 2, (short)channels);
        Marshal.WriteInt32(ptr, 4, sampleRate);
        Marshal.WriteInt32(ptr, 8, sampleRate * channels * 4);
        Marshal.WriteInt16(ptr, 12, (short)(channels * 4));
        Marshal.WriteInt16(ptr, 14, 32);
        Marshal.WriteInt16(ptr, 16, 0);
        return ptr;
    }

    public static IAudioCaptureClient InitCapture(IAudioClient client, IntPtr format,
        long bufferDuration, bool verbose = true)
    {
        // Polling, not event-driven: we read with GetNextPacketSize, so asking for an event
        // callback only adds a way to get the client into a state it does not want.
        var hr = client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK,
            bufferDuration, 0, format, IntPtr.Zero);
        if (hr != 0)
        {
            throw new InvalidOperationException($"Initialize failed: 0x{hr:X8}");
        }

        if (verbose)
        {
            // Process loopback ignores the requested buffer duration and uses the engine
            // period, so 480 frames at 48 kHz (10 ms) is the expected answer, not our 1 s.
            var bufHr = client.GetBufferSize(out var bufFrames);
            Console.WriteLine($"  Initialize OK; GetBufferSize hr=0x{bufHr:X8} frames={bufFrames}");
            DumpServices(client, "loopback");
        }

        var svcPtr = GetServiceRaw(client, typeof(IAudioCaptureClient).GUID, out hr);
        if (hr != 0 || svcPtr == IntPtr.Zero)
        {
            throw new InvalidOperationException($"GetService failed: 0x{hr:X8}");
        }

        var svc = (IAudioCaptureClient)Marshal.GetTypedObjectForIUnknown(svcPtr, typeof(IAudioCaptureClient));
        Marshal.Release(svcPtr);
        return svc;
    }

    /// <summary>
    /// Asks one client for every service worth asking about, and also QueryInterfaces the
    /// client itself for the capture interface. IAudioClock is supported by every initialized
    /// client, so a client that refuses it is either broken or being called wrong.
    /// </summary>
    public static void DumpServices(IAudioClient client, string label)
    {
        foreach (var (name, guid) in new[]
                 {
                     ("IAudioClock          ", "CD63314F-3FBA-4A1B-812C-EF96358728E7"),
                     ("ISimpleAudioVolume   ", "87CE5498-68D6-44E5-9215-6DA47EF883D8"),
                     ("IAudioCaptureClient  ", "C8ADBD64-E71E-48A0-A4DE-185C395CD317"),
                     ("IAudioRenderClient   ", "F294ACFC-3146-4483-A7BF-ADDCA7C260E2"),
                 })
        {
            var ptr = GetServiceRaw(client, new Guid(guid), out var probeHr);
            Console.WriteLine($"  [{label}] {name} hr=0x{probeHr:X8}{(probeHr == 0 ? "  <-- AVAILABLE" : "")}");
            if (probeHr == 0 && ptr != IntPtr.Zero) Marshal.Release(ptr);
        }

        // Long shot, but free: some implementations put the capture client on the same object.
        var pClient = Marshal.GetComInterfaceForObject(client, typeof(IAudioClient));
        var captureIid = typeof(IAudioCaptureClient).GUID;
        var qiHr = Marshal.QueryInterface(pClient, ref captureIid, out var qiPtr);
        Console.WriteLine($"  [{label}] QI(IAudioCaptureClient) hr=0x{qiHr:X8}");
        if (qiHr == 0 && qiPtr != IntPtr.Zero) Marshal.Release(qiPtr);
        Marshal.Release(pClient);
    }

    /// <summary>
    /// Calls IAudioClient::GetService by walking the vtable directly.
    ///
    /// Going through the runtime's generated stub returned E_NOINTERFACE for every IID —
    /// including IAudioSessionControl, which every initialized client supports — so the
    /// failure was in the marshalling of that one call rather than in the client. Invoking
    /// the function pointer ourselves removes the runtime from the path entirely.
    ///
    /// Slot 14: IUnknown takes 0-2, then IAudioClient's twelve methods, GetService last.
    /// </summary>
    internal static unsafe IntPtr GetServiceRaw(IAudioClient client, Guid iid, out int hr)
    {
        var pClient = Marshal.GetComInterfaceForObject(client, typeof(IAudioClient));
        try
        {
            var vtbl = Marshal.ReadIntPtr(pClient);
            var fn = Marshal.ReadIntPtr(vtbl, 14 * IntPtr.Size);
            var getService = (delegate* unmanaged[Stdcall]<IntPtr, Guid*, IntPtr*, int>)fn;

            IntPtr service;
            hr = getService(pClient, &iid, &service);
            return hr == 0 ? service : IntPtr.Zero;
        }
        finally
        {
            Marshal.Release(pClient);
        }
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateEvent(IntPtr attrs, bool manualReset, bool initialState, string? name);
}
