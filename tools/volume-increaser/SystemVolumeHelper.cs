using NAudio.CoreAudioApi;

namespace VolumeIncreaser;

internal static class SystemVolumeHelper
{
    public static void SetMasterToScalar(MMDevice device, float level01)
    {
        if (level01 < 0f)
        {
            level01 = 0f;
        }

        if (level01 > 1f)
        {
            level01 = 1f;
        }

        device.AudioEndpointVolume.MasterVolumeLevelScalar = level01;
    }

    public static void MaximizeAllAppSessionsOnDevice(MMDevice device)
    {
        var sessionManager = device.AudioSessionManager;
        sessionManager.RefreshSessions();
        for (var i = 0; i < sessionManager.Sessions.Count; i++)
        {
            var session = sessionManager.Sessions[i];
            if (session.SimpleAudioVolume == null)
            {
                continue;
            }

            try
            {
                session.SimpleAudioVolume.Volume = 1.0f;
            }
            catch
            {
                // Some system sessions are read-only; ignore
            }
        }
    }
}
