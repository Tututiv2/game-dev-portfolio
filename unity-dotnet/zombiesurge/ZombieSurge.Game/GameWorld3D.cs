using System.Numerics;
using ZombieSurge.Core;

namespace ZombieSurge.Game;

public sealed class GameWorld3D
{
    private readonly LevelData _level;
    private Vector3 _player;
    private float _yaw;
    private int _hp = GameBalance.StartHp;
    private int _ammoMag = GameBalance.MagSize;
    private int _ammoPool = GameBalance.StartReserve;
    private float _fireCd;
    private float _reloadT;
    private bool _reloading;
    private float _knifeCd;
    private float _damageInvuln;
    private int _round = 1;
    private int _points;
    private float _waveBreakT;
    private float _flashUi;
    private readonly List<ZombieActor> _zombies = new();
    private GamePhase _phase = GamePhase.Attract;

    private const float PlayerHalfW = 0.32f;
    private const float PlayerHeight = 1.78f;

    public GameWorld3D(LevelData level)
    {
        _level = level;
        _player = level.PlayerStart.ToVector3();
        _yaw = 0f;
    }

    public LevelData Level => _level;
    public Vector3 PlayerPosition => _player;
    public float Yaw => _yaw;
    public GamePhase Phase => _phase;
    public int Round => _round;
    public int Points => _points;
    public int Hp => _hp;
    public int AmmoMag => _ammoMag;
    public int AmmoPool => _ammoPool;
    public bool Reloading => _reloading;
    public float WaveBreakT => _waveBreakT;
    public float FlashUi => _flashUi;
    public IReadOnlyList<ZombieActor> Zombies => _zombies;

    public void AddYaw(float deltaRadians) => _yaw += deltaRadians;

    public void StartNewRun()
    {
        _zombies.Clear();
        _round = 1;
        _points = 0;
        _hp = GameBalance.StartHp;
        _ammoMag = GameBalance.MagSize;
        _ammoPool = GameBalance.StartReserve;
        _fireCd = 0f;
        _reloading = false;
        _reloadT = 0f;
        _knifeCd = 0f;
        _damageInvuln = 0f;
        _flashUi = 0f;
        _player = _level.PlayerStart.ToVector3();
        _yaw = 0f;
        _phase = GamePhase.WaveBreak;
        _waveBreakT = 0.01f;
    }

    public void Update(float dt, bool up, bool down, bool left, bool right, bool fire, bool reload, bool knife, bool start, bool restart)
    {
        if (_flashUi > 0f)
        {
            _flashUi = MathF.Max(0f, _flashUi - dt);
        }

        if (_phase == GamePhase.Attract)
        {
            if (start)
            {
                StartNewRun();
            }

            return;
        }

        if (_phase == GamePhase.Dead)
        {
            if (restart)
            {
                _phase = GamePhase.Attract;
            }

            return;
        }

        if (_phase == GamePhase.WaveBreak)
        {
            _waveBreakT += dt;
            if (_waveBreakT >= GameBalance.WaveBreakSeconds)
            {
                BeginRound();
            }

            return;
        }

        if (_reloading)
        {
            _reloadT += dt;
            if (_reloadT >= GameBalance.ReloadTime)
            {
                _reloading = false;
                var need = GameBalance.MagSize - _ammoMag;
                var take = Math.Min(need, _ammoPool);
                _ammoMag += take;
                _ammoPool -= take;
            }
        }
        else if (reload && _ammoMag < GameBalance.MagSize && _ammoPool > 0)
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

        var forward = new Vector3(MathF.Sin(_yaw), 0f, -MathF.Cos(_yaw));
        var rightV = new Vector3(MathF.Cos(_yaw), 0f, MathF.Sin(_yaw));
        var move = Vector3.Zero;
        if (up)
        {
            move += forward;
        }

        if (down)
        {
            move -= forward;
        }

        if (left)
        {
            move -= rightV;
        }

        if (right)
        {
            move += rightV;
        }

        if (move.LengthSquared() > 1e-6f)
        {
            move = Vector3.Normalize(move);
        }

        _player += move * (GameBalance.PlayerMoveSpeed * dt);
        _player.Y = 0f;
        ResolveBoxCollisions();

        if (knife && _knifeCd <= 0f)
        {
            _knifeCd = GameBalance.KnifeCooldown;
            var eye = EyePosition();
            var tip = eye + forward * GameBalance.KnifeRange;
            for (var i = _zombies.Count - 1; i >= 0; i--)
            {
                var z = _zombies[i];
                var close = DistanceXZ(tip, z.Position) < GameBalance.ZombieRadius + 0.55f
                            || DistanceXZ(_player, z.Position) < GameBalance.ZombieRadius + 0.65f;
                if (close)
                {
                    z.Hp -= 2;
                    if (z.Hp <= 0)
                    {
                        KillZombieAt(i);
                    }
                }
            }
        }

        if (fire && _fireCd <= 0f && !_reloading && _ammoMag > 0)
        {
            _fireCd = GameBalance.FireInterval;
            _ammoMag--;
            Hitscan(eye: EyePosition(), direction: Vector3.Normalize(forward + new Vector3(0f, -0.04f, 0f)));
        }

        for (var i = _zombies.Count - 1; i >= 0; i--)
        {
            var z = _zombies[i];
            var to = _player - z.Position;
            to.Y = 0f;
            if (to.LengthSquared() < 1e-6f)
            {
                continue;
            }

            to = Vector3.Normalize(to);
            var spd = 1.65f + _round * 0.12f;
            var maxSpd = 4.85f;
            if (spd > maxSpd)
            {
                spd = maxSpd;
            }

            z.Position += to * (spd * dt);
            z.Position.Y = 0f;

            if (DistanceXZ(z.Position, _player) < GameBalance.PlayerRadius + GameBalance.ZombieRadius
                && _damageInvuln <= 0f)
            {
                _hp -= 20;
                _damageInvuln = GameBalance.HitInvuln;
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

    public (Vector3 eye, Vector3 target) CameraRig(float distance, float height, float pivotHeight)
    {
        var back = new Vector3(-MathF.Sin(_yaw) * distance, height, MathF.Cos(_yaw) * distance);
        var pivot = _player + new Vector3(0f, pivotHeight, 0f);
        var eye = pivot + back;
        return (eye, pivot);
    }

    private Vector3 EyePosition() => _player + new Vector3(0f, 1.45f, 0f);

    private void Hitscan(Vector3 eye, Vector3 direction)
    {
        direction = Vector3.Normalize(direction);
        var bestT = GameBalance.HitscanRange;
        var bestIndex = -1;
        for (var i = 0; i < _zombies.Count; i++)
        {
            var z = _zombies[i];
            var center = z.Position + new Vector3(0f, 0.9f, 0f);
            if (RayVsSphere(eye, direction, center, GameBalance.ZombieRadius, out var t)
                && t >= 0f
                && t <= bestT)
            {
                bestT = t;
                bestIndex = i;
            }
        }

        if (bestIndex >= 0)
        {
            var z = _zombies[bestIndex];
            z.Hp -= 1;
            if (z.Hp <= 0)
            {
                KillZombieAt(bestIndex);
            }
        }
    }

    private static bool RayVsSphere(Vector3 origin, Vector3 dir, Vector3 center, float radius, out float t)
    {
        t = 0f;
        var oc = origin - center;
        var b = Vector3.Dot(oc, dir);
        var c = Vector3.Dot(oc, oc) - radius * radius;
        if (c > 0f && b > 0f)
        {
            return false;
        }

        var disc = b * b - c;
        if (disc < 0f)
        {
            return false;
        }

        var sqrtD = MathF.Sqrt(disc);
        t = -b - sqrtD;
        if (t < 0f)
        {
            t = -b + sqrtD;
        }

        return t >= 0f;
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

    private ZombieActor SpawnZombie(int hp)
    {
        Vector3 p;
        if (_level.ZombieSpawns.Count > 0)
        {
            var s = _level.ZombieSpawns[Random.Shared.Next(_level.ZombieSpawns.Count)];
            p = s.ToVector3();
            p.Y = 0f;
            var jitter = new Vector3((float)(Random.Shared.NextDouble() * 2 - 1), 0f, (float)(Random.Shared.NextDouble() * 2 - 1));
            p += jitter * 1.25f;
        }
        else
        {
            var ang = (float)(Random.Shared.NextDouble() * Math.PI * 2);
            var rad = 22f + (float)Random.Shared.NextDouble() * 8f;
            p = new Vector3(MathF.Cos(ang) * rad, 0f, MathF.Sin(ang) * rad);
        }

        return new ZombieActor { Position = p, Hp = hp };
    }

    private void KillZombieAt(int index)
    {
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

    private void ResolveBoxCollisions()
    {
        var min = new Vector3(_player.X - PlayerHalfW, _player.Y, _player.Z - PlayerHalfW);
        var max = new Vector3(_player.X + PlayerHalfW, _player.Y + PlayerHeight, _player.Z + PlayerHalfW);
        foreach (var b in _level.Boxes)
        {
            var bmin = b.Min.ToVector3();
            var bmax = b.Max.ToVector3();
            if (!Intersects(min, max, bmin, bmax))
            {
                continue;
            }

            var pen = ComputePenetration(min, max, bmin, bmax);
            _player += pen;
            min = new Vector3(_player.X - PlayerHalfW, _player.Y, _player.Z - PlayerHalfW);
            max = new Vector3(_player.X + PlayerHalfW, _player.Y + PlayerHeight, _player.Z + PlayerHalfW);
        }
    }

    private static bool Intersects(Vector3 amin, Vector3 amax, Vector3 bmin, Vector3 bmax) =>
        amin.X <= bmax.X && amax.X >= bmin.X
        && amin.Y <= bmax.Y && amax.Y >= bmin.Y
        && amin.Z <= bmax.Z && amax.Z >= bmin.Z;

    private static Vector3 ComputePenetration(Vector3 amin, Vector3 amax, Vector3 bmin, Vector3 bmax)
    {
        var overlapX = MathF.Min(amax.X, bmax.X) - MathF.Max(amin.X, bmin.X);
        var overlapY = MathF.Min(amax.Y, bmax.Y) - MathF.Max(amin.Y, bmin.Y);
        var overlapZ = MathF.Min(amax.Z, bmax.Z) - MathF.Max(amin.Z, bmin.Z);
        if (overlapX <= 0f || overlapY <= 0f || overlapZ <= 0f)
        {
            return Vector3.Zero;
        }

        if (overlapX < overlapY && overlapX < overlapZ)
        {
            var dir = (amin.X + amax.X) * 0.5f < (bmin.X + bmax.X) * 0.5f ? -1f : 1f;
            return new Vector3(dir * overlapX, 0f, 0f);
        }

        if (overlapY < overlapZ)
        {
            var dir = (amin.Y + amax.Y) * 0.5f < (bmin.Y + bmax.Y) * 0.5f ? -1f : 1f;
            return new Vector3(0f, dir * overlapY, 0f);
        }

        {
            var dir = (amin.Z + amax.Z) * 0.5f < (bmin.Z + bmax.Z) * 0.5f ? -1f : 1f;
            return new Vector3(0f, 0f, dir * overlapZ);
        }
    }

    private static float DistanceXZ(Vector3 a, Vector3 b)
    {
        var dx = a.X - b.X;
        var dz = a.Z - b.Z;
        return MathF.Sqrt(dx * dx + dz * dz);
    }
}
