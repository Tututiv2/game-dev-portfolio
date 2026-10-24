using NAudio.Wave;
using NAudio.Wave.SampleProviders;

// A known, constant sound source to test the boost against.
//
// Every measurement so far has been thrown off by whether Spotify happened to be playing at
// that moment — twice the capture read digital silence and the conclusion drawn from it was
// wrong. A steady tone removes that variable: constant amplitude, no song dynamics, and it
// keeps playing whether or not anyone is at the keyboard.
//
// 440 Hz at -20 dBFS, which is roughly a normal track's level and leaves room to be boosted.

// Hard time limit, always. An earlier version ran until killed, and `kill` from bash does not
// reliably stop a native Windows process — two orphaned copies ended up humming through the
// speakers with no obvious way to tell where the noise was coming from. A tone that stops on
// its own cannot do that, whatever happens to the thing that launched it.
var seconds = args.Length > 1 && int.TryParse(args[1], out var s) ? s : 15;

var amplitude = args.Length > 0 && double.TryParse(args[0], out var a) ? a : 0.1;

// Render rate is a parameter because the capture came back 4.72x below the generated amplitude,
// and a rate that differs from the device's forces Windows to convert the stream before our tap
// sees it. Matching the device rate removes that conversion from the experiment.
var rate = args.Length > 2 && int.TryParse(args[2], out var r) ? r : 48000;

var tone = new SignalGenerator(rate, 2)
{
    Gain = amplitude,
    Frequency = 440,
    Type = SignalGeneratorType.Sin,
};

using var output = new WasapiOut();
output.Init(tone);
output.Play();

Console.WriteLine($"tone: 440 Hz at {amplitude:F3}, {rate} Hz, pid {Environment.ProcessId}, stopping in {seconds}s");
Thread.Sleep(seconds * 1000);
output.Stop();
