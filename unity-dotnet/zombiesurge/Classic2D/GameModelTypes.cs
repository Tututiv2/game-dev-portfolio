using System.Numerics;

namespace ZombieSurge;

public struct GameInput
{
    public float AimX, AimY;
    public bool Up, Down, Left, Right;
    public bool Fire;
    public bool Reload;
    public bool Knife;
    public bool Start;
    public bool Restart;
    public Vector2 ViewSize;
}

public sealed class Zombie
{
    public Vector2 Pos;
    public int Hp;
}

public sealed class Bullet
{
    public Vector2 Pos, Vel;
}
