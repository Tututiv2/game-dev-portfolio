using System.Globalization;
using System.Numerics;
using System.Windows;
using System.Windows.Media;

namespace ZombieSurge;

public enum GamePhase
{
    Attract,
    Combat,
    WaveBreak,
    Dead
}

public sealed class UndeadRoundsEngine
{
    private const float PlayerRadius = 14f;
    private const float ZombieRadius = 17f;
    private const float BulletRadius = 3f;
    private const float PlayerSpeed = 210f;
    private const float BulletSpeed = 620f;
    private const float FireInterval = 0.085f;
    private const float ReloadTime = 1.65f;
    private const float KnifeRange = 56f;
    private const float KnifeCooldown = 0.4f;
    private const int MagSize = 32;
    private const int StartReserve = 192;
    private const float HitInvuln = 0.55f;

    private Vector2 _player;
    private float _aimAngle;
    private int _hp = 100;
    private int _ammoMag = MagSize;
    private int _ammoPool = StartReserve;
    private float _fireCd;
    private float _reloadT;
    private bool _reloading;
    private float _knifeCd;
    private float _damageInvuln;

    private int _round = 1;
    private int _points;
    private float _waveBreakT;
    private float _flashUi;

    private readonly List<Zombie> _zombies = new();
    private readonly List<Bullet> _bullets = new();

    private GamePhase _phase = GamePhase.Attract;
    private Vector2 _size;

    // Cached, frozen render resources. Draw runs every frame; allocating unfrozen
    // brushes/pens per frame (and per zombie) churns the GC and is slow in WPF.
    private static readonly LinearGradientBrush BgBrush = Freeze(new LinearGradientBrush(
        Colour(0x0c, 0x08, 0x08), Colour(0x18, 0x10, 0x0c), 45));
    private static readonly Pen GridPen = Freeze(new Pen(
        new SolidColorBrush(System.Windows.Media.Color.FromArgb(0x12, 0xFF, 0xFF, 0xFF)), 1));
    private static readonly RadialGradientBrush ZombieBrush = Freeze(new RadialGradientBrush
    {
        GradientOrigin = new System.Windows.Point(0.4, 0.35),
        Center = new System.Windows.Point(0.5, 0.5),
        GradientStops = new GradientStopCollection
        {
            new(Colour(0x4a, 0x6b, 0x3a), 0),
            new(Colour(0x1a, 0x2a, 0x10), 1)
        }
    });
    private static readonly Pen ZombieOutlinePen = Freeze(new Pen(Brushes.Black, 1.4));
    private static readonly SolidColorBrush PlayerBrush = Freeze(new SolidColorBrush(Colour(0x4a, 0x7a, 0xbb)));
    private static readonly Pen PlayerOutlinePen = Freeze(new Pen(Brushes.Black, 1.2));
    private static readonly Pen GunPen = Freeze(new Pen(Brushes.DimGray, 5));
    private static readonly Pen BulletPen = Freeze(new Pen(Brushes.Orange, 0.6));
    private static readonly SolidColorBrush HudBrush = Freeze(new SolidColorBrush(
        System.Windows.Media.Color.FromArgb(0xD8, 0xFF, 0x8C, 0x00)));
    private static readonly SolidColorBrush HpBackBrush = Freeze(new SolidColorBrush(
        System.Windows.Media.Color.FromArgb(0x50, 0, 0, 0)));
    private static readonly Pen HpBorderPen = Freeze(new Pen(Brushes.OrangeRed, 1));
    private static readonly Typeface UiTypeface = new("Segoe UI");
    private static readonly Typeface UiSemiboldTypeface = new("Segoe UI Semibold");
    private static readonly Typeface MonoTypeface = new("Consolas");

    private static T Freeze<T>(T freezable) where T : Freezable
    {
        freezable.Freeze();
        return freezable;
    }

    public void StartNewRun()
    {
        _zombies.Clear();
        _bullets.Clear();
        _round = 1;
        _points = 0;
        _hp = 100;
        _ammoMag = MagSize;
        _ammoPool = StartReserve;
        _fireCd = 0f;
        _reloading = false;
        _reloadT = 0f;
        _knifeCd = 0f;
        _damageInvuln = 0f;
        _flashUi = 0f;
        if (_size.X >= 32f && _size.Y >= 32f)
        {
            _player = new Vector2(_size.X * 0.5f, _size.Y * 0.5f);
        }

        _phase = GamePhase.WaveBreak;
        _waveBreakT = 0.01f;
    }

    public void Update(float dt, GameInput input)
    {
        _size = input.ViewSize;
        if (_size.X < 32 || _size.Y < 32)
        {
            return;
        }

        if (_flashUi > 0f)
        {
            _flashUi = MathF.Max(0f, _flashUi - dt);
        }

        if (_phase == GamePhase.Attract)
        {
            if (input.Start)
            {
                StartNewRun();
            }
            return;
        }

        if (_phase == GamePhase.Dead)
        {
            if (input.Restart)
            {
                _phase = GamePhase.Attract;
            }
            return;
        }

        if (_phase == GamePhase.WaveBreak)
        {
            _waveBreakT += dt;
            if (_waveBreakT >= 2.2f)
            {
                BeginRound();
            }
            return;
        }

        if (_reloading)
        {
            _reloadT += dt;
            if (_reloadT >= ReloadTime)
            {
                _reloading = false;
                var need = MagSize - _ammoMag;
                var take = Math.Min(need, _ammoPool);
                _ammoMag += take;
                _ammoPool -= take;
            }
        }
        else if (input.Reload && _ammoMag < MagSize && _ammoPool > 0)
        {
            _reloading = true;
            _reloadT = 0f;
        }

        if (_fireCd > 0f)
        {
            _fireCd -= dt;
        }

        if (_knifeCd > 0f)
        {
            _knifeCd -= dt;
        }

        if (_damageInvuln > 0f)
        {
            _damageInvuln -= dt;
        }

        var aim = new Vector2(input.AimX - _player.X, input.AimY - _player.Y);
        _aimAngle = MathF.Atan2(aim.Y, aim.X);

        var move = Vector2.Zero;
        if (input.Up)
        {
            move.Y -= 1f;
        }

        if (input.Down)
        {
            move.Y += 1f;
        }

        if (input.Left)
        {
            move.X -= 1f;
        }

        if (input.Right)
        {
            move.X += 1f;
        }

        if (move.LengthSquared() > 0.0001f)
        {
            move = Vector2.Normalize(move);
        }

        _player += move * (PlayerSpeed * dt);
        _player = new Vector2(
            Math.Clamp(_player.X, PlayerRadius, _size.X - PlayerRadius),
            Math.Clamp(_player.Y, PlayerRadius, _size.Y - PlayerRadius));

        if (input.Knife && _knifeCd <= 0f)
        {
            _knifeCd = KnifeCooldown;
            var dir = new Vector2(MathF.Cos(_aimAngle), MathF.Sin(_aimAngle));
            var tip = _player + dir * KnifeRange;
            for (var i = _zombies.Count - 1; i >= 0; i--)
            {
                var z = _zombies[i];
                if (Vector2.Distance(tip, z.Pos) < ZombieRadius + 16f
                    || Vector2.Distance(_player, z.Pos) < ZombieRadius + 22f)
                {
                    z.Hp -= 2;
                    if (z.Hp <= 0)
                    {
                        KillZombieAt(i, tip);
                    }
                }
            }
        }

        if (input.Fire && _fireCd <= 0f && !_reloading && _ammoMag > 0)
        {
            _fireCd = FireInterval;
            _ammoMag--;
            var d = new Vector2(MathF.Cos(_aimAngle), MathF.Sin(_aimAngle));
            _bullets.Add(new Bullet { Pos = _player + d * 28f, Vel = d * BulletSpeed });
        }

        for (var i = _bullets.Count - 1; i >= 0; i--)
        {
            _bullets[i].Pos += _bullets[i].Vel * dt;
            var p = _bullets[i].Pos;
            if (p.X < 0 || p.Y < 0 || p.X > _size.X || p.Y > _size.Y)
            {
                _bullets.RemoveAt(i);
                continue;
            }

            for (var z = _zombies.Count - 1; z >= 0; z--)
            {
                if (Vector2.Distance(p, _zombies[z].Pos) < ZombieRadius + BulletRadius)
                {
                    _zombies[z].Hp -= 1;
                    _bullets.RemoveAt(i);
                    if (_zombies[z].Hp <= 0)
                    {
                        KillZombieAt(z, p);
                    }
                    break;
                }
            }
        }

        for (var i = _zombies.Count - 1; i >= 0; i--)
        {
            var z = _zombies[i];
            var d = _player - z.Pos;
            if (d.LengthSquared() < 0.0001f)
            {
                continue;
            }

            d = Vector2.Normalize(d);
            var spd = 52f + _round * 3.2f;
            var maxSpd = 150f;
            if (spd > maxSpd)
            {
                spd = maxSpd;
            }

            z.Pos += d * (spd * dt);

            if (Vector2.Distance(z.Pos, _player) < PlayerRadius + ZombieRadius && _damageInvuln <= 0f)
            {
                _hp -= 20;
                _damageInvuln = HitInvuln;
                if (_hp <= 0)
                {
                    _hp = 0;
                    _phase = GamePhase.Dead;
                    return;
                }
            }
        }

        if (_zombies.Count == 0 && _phase == GamePhase.Combat)
        {
            _phase = GamePhase.WaveBreak;
            _waveBreakT = 0f;
            _round++;
            _ammoPool = Math.Min(_ammoPool + 40, 400);
            _flashUi = 0.45f;
        }
    }

    private void BeginRound()
    {
        _phase = GamePhase.Combat;
        _waveBreakT = 0f;
        _zombies.Clear();
        var n = Math.Min(4 + _round * 2, 55);
        var hpZ = 1 + (_round - 1) / 2;
        if (hpZ > 8)
        {
            hpZ = 8;
        }

        for (var k = 0; k < n; k++)
        {
            _zombies.Add(SpawnZombie(hpZ));
        }
    }

    private Zombie SpawnZombie(int hp)
    {
        var edge = Random.Shared.Next(4);
        float x, y;
        if (edge == 0)
        {
            x = -30f;
            y = (float)Random.Shared.NextDouble() * _size.Y;
        }
        else if (edge == 1)
        {
            x = _size.X + 30f;
            y = (float)Random.Shared.NextDouble() * _size.Y;
        }
        else if (edge == 2)
        {
            y = -30f;
            x = (float)Random.Shared.NextDouble() * _size.X;
        }
        else
        {
            y = _size.Y + 30f;
            x = (float)Random.Shared.NextDouble() * _size.X;
        }

        return new Zombie { Pos = new Vector2(x, y), Hp = hp };
    }

    private void KillZombieAt(int index, Vector2 spark)
    {
        _ = spark;
        _zombies.RemoveAt(index);
        var add = 10 * (1 + (_round - 1) / 2);
        if (add < 5)
        {
            add = 5;
        }

        if (add > 5000)
        {
            add = 5000;
        }

        _points += add;
    }

    public void Draw(DrawingContext dc, Size renderSize)
    {
        _size = new Vector2((float)renderSize.Width, (float)renderSize.Height);
        dc.DrawRectangle(BgBrush, null, new Rect(0, 0, renderSize.Width, renderSize.Height));

        for (var g = 0; g < renderSize.Width; g += 48)
        {
            dc.DrawLine(GridPen, new System.Windows.Point(g, 0), new System.Windows.Point(g, renderSize.Height));
        }

        for (var g = 0; g < renderSize.Height; g += 48)
        {
            dc.DrawLine(GridPen, new System.Windows.Point(0, g), new System.Windows.Point(renderSize.Width, g));
        }

        foreach (var z in _zombies)
        {
            dc.DrawEllipse(ZombieBrush, ZombieOutlinePen, new System.Windows.Point(z.Pos.X, z.Pos.Y), ZombieRadius, ZombieRadius);
            dc.DrawEllipse(Brushes.DarkRed, null, new System.Windows.Point(z.Pos.X - 4, z.Pos.Y - 2), 3, 2);
            dc.DrawEllipse(Brushes.DarkRed, null, new System.Windows.Point(z.Pos.X + 4, z.Pos.Y - 2), 3, 2);
        }

        var gunLen = 26f;
        var gx = _player.X + MathF.Cos(_aimAngle) * gunLen;
        var gy = _player.Y + MathF.Sin(_aimAngle) * gunLen;
        dc.DrawEllipse(PlayerBrush, PlayerOutlinePen, new System.Windows.Point(_player.X, _player.Y), PlayerRadius, PlayerRadius);
        dc.DrawLine(
            GunPen,
            new System.Windows.Point(_player.X, _player.Y),
            new System.Windows.Point(gx, gy));

        foreach (var b in _bullets)
        {
            dc.DrawEllipse(Brushes.Gold, BulletPen, new System.Windows.Point(b.Pos.X, b.Pos.Y), 4, 4);
        }

        DrawHud(dc, renderSize);
    }

    private void DrawHud(DrawingContext dc, Size renderSize)
    {
        Brush hud = HudBrush;
        if (_flashUi > 0f)
        {
            hud = Brushes.Orange;
        }

        if (_phase == GamePhase.Attract)
        {
            DrawText(dc, 44, "ZOMBIE SURGE", 26, 36, true);
            DrawText(dc, 28, "UNDead ROUNDS", 26, 80, true);
            DrawText(dc, 18, "WASD — move  |  Mouse — aim  |  Click — full-auto", 32, 180, true);
            DrawText(dc, 18, "R — reload  |  F — knife  |  Space — start", 32, 210, true);
            DrawText(dc, 20, "PRESS  SPACE  TO  START", 32, 300, true);
            return;
        }

        if (_phase == GamePhase.Dead)
        {
            DrawText(dc, 36, "MIA", 32, 120, true);
            DrawText(dc, 22, "Round reached: " + _round, 32, 200, true);
            DrawText(dc, 22, "Points: " + _points, 32, 240, true);
            DrawText(dc, 20, "Press  R  to return to title", 32, 320, true);
            return;
        }

        var roundLabel = "ROUND  " + _round;
        if (_phase == GamePhase.WaveBreak)
        {
            roundLabel = "GET READY  —  ROUND  " + _round;
        }

        const double pixelsPerDip = 96.0;
        var ft = new FormattedText(
            "POINTS  " + _points,
            CultureInfo.InvariantCulture,
            FlowDirection.LeftToRight,
            UiTypeface,
            18,
            hud,
            pixelsPerDip);

        dc.DrawText(ft, new System.Windows.Point(18, 12));

        ft = new FormattedText(
            roundLabel,
            CultureInfo.InvariantCulture,
            FlowDirection.LeftToRight,
            UiSemiboldTypeface,
            22,
            Brushes.Orange,
            pixelsPerDip);
        var tw = ft.Width;
        dc.DrawText(ft, new System.Windows.Point((renderSize.Width - tw) * 0.5, 10));

        var hpW = 220.0;
        var hpF = 100.0;
        if (_hp > 0)
        {
            hpF = _hp;
        }

        var hpBar = (hpF / 100.0) * hpW;
        dc.DrawRectangle(HpBackBrush, null, new Rect(18, 44, hpW, 8));
        dc.DrawRectangle(Brushes.DarkRed, null, new Rect(18, 44, hpBar, 8));
        dc.DrawRectangle(null, HpBorderPen, new Rect(18, 44, hpW, 8));

        var ammo = _ammoMag + "  /  " + _ammoPool;
        if (_reloading)
        {
            ammo = "RELOAD...";
        }

        var ammoT = new FormattedText(
            ammo,
            CultureInfo.InvariantCulture,
            FlowDirection.LeftToRight,
            MonoTypeface,
            20,
            Brushes.PaleGreen,
            pixelsPerDip);
        dc.DrawText(ammoT, new System.Windows.Point(renderSize.Width - ammoT.Width - 24, 12));

        if (_phase == GamePhase.WaveBreak)
        {
            var sec = 2.2f - _waveBreakT;
            if (sec < 0f)
            {
                sec = 0f;
            }

            var q = "Next wave in  " + sec.ToString("0.0", CultureInfo.InvariantCulture) + "s";
            var qt = new FormattedText(
                q,
                CultureInfo.InvariantCulture,
                FlowDirection.LeftToRight,
                UiTypeface,
                20,
                Brushes.Orange,
                pixelsPerDip);
            dc.DrawText(qt, new System.Windows.Point((renderSize.Width - qt.Width) * 0.5, renderSize.Height * 0.38));
        }
    }

    private void DrawText(DrawingContext dc, double size, string t, double x, double y, bool center)
    {
        const double pixelsPerDip = 96.0;
        var f = new FormattedText(
            t,
            CultureInfo.InvariantCulture,
            FlowDirection.LeftToRight,
            UiTypeface,
            size,
            Brushes.WhiteSmoke,
            pixelsPerDip);
        if (center)
        {
            x = (_size.X - f.Width) * 0.5f;
        }

        dc.DrawText(f, new System.Windows.Point(x, y));
    }

    private static System.Windows.Media.Color Colour(byte r, byte g, byte b) =>
        System.Windows.Media.Color.FromRgb(r, g, b);
}