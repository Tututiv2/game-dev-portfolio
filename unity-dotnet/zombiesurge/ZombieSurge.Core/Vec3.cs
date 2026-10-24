using System.Numerics;

namespace ZombieSurge.Core;

public struct Vec3
{
    public float X { get; set; }
    public float Y { get; set; }
    public float Z { get; set; }

    public Vec3(float x, float y, float z)
    {
        X = x;
        Y = y;
        Z = z;
    }

    public Vector3 ToVector3() => new(X, Y, Z);

    public static Vec3 From(Vector3 v) => new(v.X, v.Y, v.Z);
}
