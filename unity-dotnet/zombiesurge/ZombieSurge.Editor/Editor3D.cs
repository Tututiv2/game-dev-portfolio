using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Media3D;

namespace ZombieSurge.Editor;

internal static class Editor3D
{
    public static MeshGeometry3D AxisAlignedBox(Point3D min, Point3D max) => BoxMeshWithNormals(min, max);

    /// <summary>Axis-aligned box with per-face normals for smooth Gouraud shading in WPF 3D.</summary>
    public static MeshGeometry3D BoxMeshWithNormals(Point3D min, Point3D max)
    {
        double x0 = min.X, y0 = min.Y, z0 = min.Z;
        double x1 = max.X, y1 = max.Y, z1 = max.Z;
        var positions = new Point3DCollection();
        var normals = new Vector3DCollection();
        var indices = new Int32Collection();

        void face(Point3D a, Point3D b, Point3D c, Point3D d, Vector3D n)
        {
            int i = positions.Count;
            positions.Add(a);
            positions.Add(b);
            positions.Add(c);
            positions.Add(d);
            normals.Add(n);
            normals.Add(n);
            normals.Add(n);
            normals.Add(n);
            indices.Add(i);
            indices.Add(i + 1);
            indices.Add(i + 2);
            indices.Add(i);
            indices.Add(i + 2);
            indices.Add(i + 3);
        }

        // Bottom (-Y)
        face(new Point3D(x0, y0, z0), new Point3D(x1, y0, z0), new Point3D(x1, y0, z1), new Point3D(x0, y0, z1), new Vector3D(0, -1, 0));
        // Top (+Y)
        face(new Point3D(x0, y1, z0), new Point3D(x0, y1, z1), new Point3D(x1, y1, z1), new Point3D(x1, y1, z0), new Vector3D(0, 1, 0));
        // -Z
        face(new Point3D(x0, y0, z0), new Point3D(x0, y1, z0), new Point3D(x1, y1, z0), new Point3D(x1, y0, z0), new Vector3D(0, 0, -1));
        // +Z
        face(new Point3D(x1, y0, z1), new Point3D(x1, y1, z1), new Point3D(x0, y1, z1), new Point3D(x0, y0, z1), new Vector3D(0, 0, 1));
        // -X
        face(new Point3D(x0, y0, z1), new Point3D(x0, y1, z1), new Point3D(x0, y1, z0), new Point3D(x0, y0, z0), new Vector3D(-1, 0, 0));
        // +X
        face(new Point3D(x1, y0, z0), new Point3D(x1, y1, z0), new Point3D(x1, y1, z1), new Point3D(x1, y0, z1), new Vector3D(1, 0, 0));

        return new MeshGeometry3D
        {
            Positions = positions,
            Normals = normals,
            TriangleIndices = indices,
        };
    }

    public static GeometryModel3D LitBox(Point3D min, Point3D max, Color diffuse, double opacity = 1, bool specular = true)
    {
        var geom = BoxMeshWithNormals(min, max);
        geom.Freeze();
        var brush = new SolidColorBrush(Color.FromArgb((byte)(opacity * 255), diffuse.R, diffuse.G, diffuse.B));
        brush.Freeze();
        var mat = specular
            ? (Material)new MaterialGroup
            {
                Children =
                {
                    new DiffuseMaterial(brush),
                    new SpecularMaterial(new SolidColorBrush(Color.FromRgb(220, 230, 245)), 22),
                },
            }
            : new DiffuseMaterial(brush);

        var back = new SolidColorBrush(Color.FromArgb((byte)(opacity * 170), diffuse.R, diffuse.G, diffuse.B));
        back.Freeze();
        return new GeometryModel3D
        {
            Geometry = geom,
            Material = mat,
            BackMaterial = new DiffuseMaterial(back),
        };
    }

    public static DrawingBrush CreateGroundGridBrush()
    {
        var bg = new SolidColorBrush(Color.FromRgb(38, 44, 52));
        var line = new Pen(new SolidColorBrush(Color.FromRgb(58, 66, 76)), 0.012);
        var dg = new DrawingGroup();
        dg.Children.Add(new GeometryDrawing(bg, null, new RectangleGeometry(new Rect(0, 0, 1, 1))));
        dg.Children.Add(new GeometryDrawing(null, line, new LineGeometry(new Point(0, 0), new Point(1, 0))));
        dg.Children.Add(new GeometryDrawing(null, line, new LineGeometry(new Point(0, 0), new Point(0, 1))));
        dg.Freeze();
        var brush = new DrawingBrush(dg)
        {
            TileMode = TileMode.Tile,
            Viewport = new Rect(0, 0, 1, 1),
            ViewportUnits = BrushMappingMode.Absolute,
        };
        brush.Freeze();
        return brush;
    }

    /// <summary>Infinite-looking ground slightly below y=0 to avoid z-fight with content on the plane.</summary>
    public static GeometryModel3D GroundGridPlane(Point3D center, double halfExtent, double y = -0.04)
    {
        double x0 = center.X - halfExtent;
        double x1 = center.X + halfExtent;
        double z0 = center.Z - halfExtent;
        double z1 = center.Z + halfExtent;
        double tiles = Math.Clamp(halfExtent / 3.5, 8, 48);
        var mesh = new MeshGeometry3D
        {
            Positions = new Point3DCollection
            {
                new(x0, y, z0),
                new(x1, y, z0),
                new(x1, y, z1),
                new(x0, y, z1),
            },
            TextureCoordinates = new PointCollection
            {
                new Point(0, 0),
                new Point(tiles, 0),
                new Point(tiles, tiles),
                new Point(0, tiles),
            },
            TriangleIndices = new Int32Collection { 0, 1, 2, 0, 2, 3 },
            Normals = new Vector3DCollection { new Vector3D(0, 1, 0), new Vector3D(0, 1, 0), new Vector3D(0, 1, 0), new Vector3D(0, 1, 0) },
        };
        mesh.Freeze();
        var grid = CreateGroundGridBrush();
        return new GeometryModel3D
        {
            Geometry = mesh,
            Material = new DiffuseMaterial(grid),
            BackMaterial = new DiffuseMaterial(grid),
        };
    }

    /// <summary>Double-sided sky box so the camera always sits inside a lit enclosure.</summary>
    public static Model3DGroup Skybox(Point3D center, double halfExtent, Color side, Color top, Color bottom)
    {
        double cx = center.X, cy = center.Y, cz = center.Z;
        double h = halfExtent;
        var g = new Model3DGroup();

        void addWall(Point3D a, Point3D b, Point3D c, Point3D d, Color col)
        {
            var mesh = new MeshGeometry3D
            {
                Positions = new Point3DCollection { a, b, c, d },
                TriangleIndices = new Int32Collection { 0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2 },
            };
            mesh.Freeze();
            var brush = new SolidColorBrush(col);
            brush.Freeze();
            var mat = new DiffuseMaterial(brush);
            g.Children.Add(new GeometryModel3D { Geometry = mesh, Material = mat, BackMaterial = mat });
        }

        // +Y (top)
        addWall(
            new Point3D(cx - h, cy + h, cz - h),
            new Point3D(cx + h, cy + h, cz - h),
            new Point3D(cx + h, cy + h, cz + h),
            new Point3D(cx - h, cy + h, cz + h),
            top);
        // -Y (bottom / horizon haze)
        addWall(
            new Point3D(cx - h, cy - h, cz + h),
            new Point3D(cx + h, cy - h, cz + h),
            new Point3D(cx + h, cy - h, cz - h),
            new Point3D(cx - h, cy - h, cz - h),
            bottom);
        // +X
        addWall(
            new Point3D(cx + h, cy - h, cz - h),
            new Point3D(cx + h, cy + h, cz - h),
            new Point3D(cx + h, cy + h, cz + h),
            new Point3D(cx + h, cy - h, cz + h),
            side);
        // -X
        addWall(
            new Point3D(cx - h, cy - h, cz + h),
            new Point3D(cx - h, cy + h, cz + h),
            new Point3D(cx - h, cy + h, cz - h),
            new Point3D(cx - h, cy - h, cz - h),
            side);
        // +Z
        addWall(
            new Point3D(cx - h, cy - h, cz + h),
            new Point3D(cx - h, cy + h, cz + h),
            new Point3D(cx + h, cy + h, cz + h),
            new Point3D(cx + h, cy - h, cz + h),
            side);
        // -Z
        addWall(
            new Point3D(cx + h, cy - h, cz - h),
            new Point3D(cx + h, cy + h, cz - h),
            new Point3D(cx - h, cy + h, cz - h),
            new Point3D(cx - h, cy - h, cz - h),
            side);

        return g;
    }

    public static Model3DGroup AxisTriad(Point3D origin, double arm = 2.2, double thickness = 0.06)
    {
        var g = new Model3DGroup();
        double ox = origin.X, oy = origin.Y, oz = origin.Z;
        double t = thickness * 0.5;
        // X — red
        g.Children.Add(LitBox(new Point3D(ox, oy - t, oz - t), new Point3D(ox + arm, oy + t, oz + t), Color.FromRgb(200, 72, 72), 1, specular: false));
        // Y — green
        g.Children.Add(LitBox(new Point3D(ox - t, oy, oz - t), new Point3D(ox + t, oy + arm, oz + t), Color.FromRgb(86, 190, 110), 1, specular: false));
        // Z — blue
        g.Children.Add(LitBox(new Point3D(ox - t, oy - t, oz), new Point3D(ox + t, oy + t, oz + arm), Color.FromRgb(96, 148, 220), 1, specular: false));
        return g;
    }

    /// <summary>
    /// World-space ray from <paramref name="mouse"/> through a perspective camera's center projection.
    /// </summary>
    public static bool TryWorldRay(PerspectiveCamera camera, double viewportWidth, double viewportHeight, Point mouse, out Point3D origin, out Vector3D direction)
    {
        origin = default;
        direction = default;
        if (viewportWidth <= 1 || viewportHeight <= 1)
        {
            return false;
        }

        var look = camera.LookDirection;
        look.Normalize();
        var up = camera.UpDirection;
        up.Normalize();
        var right = Vector3D.CrossProduct(look, up);
        if (right.LengthSquared < 1e-20)
        {
            return false;
        }

        right.Normalize();
        var upOrtho = Vector3D.CrossProduct(right, look);
        upOrtho.Normalize();

        var xNorm = (2.0 * mouse.X / viewportWidth) - 1.0;
        var yNorm = 1.0 - (2.0 * mouse.Y / viewportHeight);

        var fovRad = camera.FieldOfView * Math.PI / 180.0;
        var tanHalf = Math.Tan(fovRad * 0.5);
        var aspect = viewportWidth / viewportHeight;

        var dir = look + right * (xNorm * tanHalf * aspect) + upOrtho * (yNorm * tanHalf);
        if (dir.LengthSquared < 1e-20)
        {
            return false;
        }

        dir.Normalize();
        origin = camera.Position;
        direction = dir;
        return true;
    }

    public static bool TryRayPlaneY(Point3D origin, Vector3D direction, double planeY, out double t)
    {
        t = 0;
        if (Math.Abs(direction.Y) < 1e-12)
        {
            return false;
        }

        t = (planeY - origin.Y) / direction.Y;
        return t > 1e-6;
    }

    /// <summary>Ray vs axis-aligned box. Returns entry distance along the ray (positive forward).</summary>
    public static bool TryRayBox(Point3D origin, Vector3D direction, Point3D min, Point3D max, out double tHit)
    {
        tHit = 0;
        var tEnter = double.NegativeInfinity;
        var tExit = double.PositiveInfinity;

        if (!Slab(origin.X, direction.X, min.X, max.X, ref tEnter, ref tExit)
            || !Slab(origin.Y, direction.Y, min.Y, max.Y, ref tEnter, ref tExit)
            || !Slab(origin.Z, direction.Z, min.Z, max.Z, ref tEnter, ref tExit))
        {
            return false;
        }

        if (tExit < 0)
        {
            return false;
        }

        tHit = tEnter >= 0 ? tEnter : tExit;
        return tHit >= 0 && !double.IsInfinity(tHit);
    }

    private static bool Slab(double o, double d, double min, double max, ref double tEnter, ref double tExit)
    {
        if (Math.Abs(d) < 1e-12)
        {
            return o >= min && o <= max;
        }

        var invD = 1.0 / d;
        var t1 = (min - o) * invD;
        var t2 = (max - o) * invD;
        if (t1 > t2)
        {
            (t1, t2) = (t2, t1);
        }

        tEnter = t1 > tEnter ? t1 : tEnter;
        tExit = t2 < tExit ? t2 : tExit;
        return tEnter <= tExit;
    }

    /// <summary>Closest hit along the ray to boxes and the ground plane y=<paramref name="groundY"/>.</summary>
    public static bool TryClosestHit(
        Point3D origin,
        Vector3D direction,
        double groundY,
        IEnumerable<(Point3D min, Point3D max)> boxes,
        out Point3D hit,
        out double tBest)
    {
        hit = default;
        tBest = double.PositiveInfinity;
        var found = false;

        if (TryRayPlaneY(origin, direction, groundY, out var tg) && tg < tBest)
        {
            tBest = tg;
            hit = origin + direction * tg;
            found = true;
        }

        foreach (var (min, max) in boxes)
        {
            if (TryRayBox(origin, direction, min, max, out var tb) && tb < tBest)
            {
                tBest = tb;
                hit = origin + direction * tb;
                found = true;
            }
        }

        return found;
    }
}
