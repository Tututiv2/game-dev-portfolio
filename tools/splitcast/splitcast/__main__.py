import sys

from . import config, devices, pipeline


def _quote(argv):
    return " ".join(a if " " not in a else '"' + a + '"' for a in argv)


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "gui"
    cfg = config.load()

    if cmd == "gui":
        from . import gui
        gui.main()
        return 0

    if cmd == "devices":
        names = devices.rank(devices.list_audio(cfg["ffmpeg"]))
        for name in names:
            mark = "   <-- the music bus" if name == devices.SONAR_STREAM else ""
            print("  " + name + mark)
        if devices.SONAR_STREAM not in names:
            print("")
            print("  " + devices.SONAR_STREAM + " is missing.")
            print("  Turn Streamer Mode ON in SteelSeries Sonar, then run this again.")
        return 0

    if cmd == "dryrun":
        print("Streamlabs server :", pipeline.streamlabs_server(cfg))
        print("Streamlabs key    :", cfg["key"])
        for line in pipeline.describe(cfg):
            print("   " + line)
        print("")
        from .runner import _safe
        print(_quote(_safe(pipeline.build(cfg), cfg)))
        return 0

    print("usage: py -3 -m splitcast [gui|devices|dryrun]")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
