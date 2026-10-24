using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Media3D;
using System.Windows.Threading;
using ZombieSurge.Core;

namespace ZombieSurge.Editor;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<EditorBox> _boxes = new();
    private readonly ObservableCollection<EditorSpawn> _spawns = new();
    private readonly Model3DGroup _levelGroup = new();
    private readonly DispatcherTimer _previewDebounce = new() { Interval = TimeSpan.FromMilliseconds(100) };

    private double _orbitYaw = 0.65;
    private double _orbitPitch = 0.38;
    private double _orbitDistance = 42;
    private Point3D _orbitTarget;
    private bool _orbitDragging;
    private MouseButton _orbitButton;
    private Point _orbitLastPos;

    private bool _placeLeftDown;
    private bool _placeDragged;
    private Point _placeStart;

    private bool _rebuildingHierarchy;
    private bool _muteBoxProps;
    private bool _muteSpawnProps;
    private readonly DispatcherTimer _hierarchyLabelDebounce = new() { Interval = TimeSpan.FromMilliseconds(400) };

    public MainWindow()
    {
        InitializeComponent();
        ViewToolCombo.SelectionChanged += ViewToolCombo_OnSelectionChanged;
        BoxesGrid.ItemsSource = _boxes;
        SpawnsGrid.ItemsSource = _spawns;
        BoxesGrid.SelectionChanged += (_, _) => PushBoxPropsToFields();
        SpawnsGrid.SelectionChanged += (_, _) => PushSpawnPropsToFields();
        WireSelectedBoxEditors();
        WireSelectedSpawnEditors();
        _hierarchyLabelDebounce.Tick += (_, _) =>
        {
            _hierarchyLabelDebounce.Stop();
            RefreshSceneHierarchy();
        };
        WorldRoot.Content = _levelGroup;

        _boxes.CollectionChanged += LevelData_CollectionChanged;
        _spawns.CollectionChanged += LevelData_CollectionChanged;

        _previewDebounce.Tick += (_, _) =>
        {
            _previewDebounce.Stop();
            RebuildViewport3D();
            RefreshSceneHierarchy();
        };

        PlayerX.TextChanged += PlayerFields_TextChanged;
        PlayerY.TextChanged += PlayerFields_TextChanged;
        PlayerZ.TextChanged += PlayerFields_TextChanged;

        Loaded += (_, _) =>
        {
            ResetOrbitToScene();
            RebuildViewport3D();
            RefreshSceneHierarchy();
            // First layout pass often has Viewport3D ActualWidth/Height = 0; refresh after render so the scene appears.
            Dispatcher.BeginInvoke(
                DispatcherPriority.Render,
                () =>
                {
                    ResetOrbitToScene();
                    RebuildViewport3D();
                    RefreshSceneHierarchy();
                });
        };

        LoadFromPath(GameLevelPath(), quiet: true);
        UpdateViewportHint();
    }

    private void WireSelectedBoxEditors()
    {
        SelBoxMinX.TextChanged += SelBox_OnTextChanged;
        SelBoxMinY.TextChanged += SelBox_OnTextChanged;
        SelBoxMinZ.TextChanged += SelBox_OnTextChanged;
        SelBoxMaxX.TextChanged += SelBox_OnTextChanged;
        SelBoxMaxY.TextChanged += SelBox_OnTextChanged;
        SelBoxMaxZ.TextChanged += SelBox_OnTextChanged;
    }

    private void WireSelectedSpawnEditors()
    {
        SelSpawnX.TextChanged += SelSpawn_OnTextChanged;
        SelSpawnZ.TextChanged += SelSpawn_OnTextChanged;
    }

    private void MainTabs_OnSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!ReferenceEquals(e.Source, MainTabs))
        {
            return;
        }

        if (MainTabs.SelectedIndex == 0)
        {
            PushBoxPropsToFields();
        }
        else
        {
            PushSpawnPropsToFields();
        }
    }

    private void SelBox_OnTextChanged(object sender, TextChangedEventArgs e)
    {
        if (_muteBoxProps)
        {
            return;
        }

        ApplyBoxPropsFromFields();
    }

    private void SelSpawn_OnTextChanged(object sender, TextChangedEventArgs e)
    {
        if (_muteSpawnProps)
        {
            return;
        }

        ApplySpawnPropsFromFields();
    }

    private void PushBoxPropsToFields()
    {
        if (SelBoxMinX is null)
        {
            return;
        }

        _muteBoxProps = true;
        try
        {
            if (BoxesGrid.SelectedItem is not EditorBox b)
            {
                SelBoxMinX.Text = "";
                SelBoxMinY.Text = "";
                SelBoxMinZ.Text = "";
                SelBoxMaxX.Text = "";
                SelBoxMaxY.Text = "";
                SelBoxMaxZ.Text = "";
                BoxPropsGroup.IsEnabled = false;
                return;
            }

            BoxPropsGroup.IsEnabled = true;
            SelBoxMinX.Text = b.MinX.ToString(CultureInfo.InvariantCulture);
            SelBoxMinY.Text = b.MinY.ToString(CultureInfo.InvariantCulture);
            SelBoxMinZ.Text = b.MinZ.ToString(CultureInfo.InvariantCulture);
            SelBoxMaxX.Text = b.MaxX.ToString(CultureInfo.InvariantCulture);
            SelBoxMaxY.Text = b.MaxY.ToString(CultureInfo.InvariantCulture);
            SelBoxMaxZ.Text = b.MaxZ.ToString(CultureInfo.InvariantCulture);
        }
        finally
        {
            _muteBoxProps = false;
        }
    }

    private void PushSpawnPropsToFields()
    {
        if (SelSpawnX is null)
        {
            return;
        }

        _muteSpawnProps = true;
        try
        {
            if (SpawnsGrid.SelectedItem is not EditorSpawn s)
            {
                SelSpawnX.Text = "";
                SelSpawnZ.Text = "";
                return;
            }

            SelSpawnX.Text = s.X.ToString(CultureInfo.InvariantCulture);
            SelSpawnZ.Text = s.Z.ToString(CultureInfo.InvariantCulture);
        }
        finally
        {
            _muteSpawnProps = false;
        }
    }

    private void ApplyBoxPropsFromFields()
    {
        if (BoxesGrid.SelectedItem is not EditorBox b)
        {
            return;
        }

        if (!float.TryParse(SelBoxMinX.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var minX)
            || !float.TryParse(SelBoxMinY.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var minY)
            || !float.TryParse(SelBoxMinZ.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var minZ)
            || !float.TryParse(SelBoxMaxX.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var maxX)
            || !float.TryParse(SelBoxMaxY.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var maxY)
            || !float.TryParse(SelBoxMaxZ.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var maxZ))
        {
            return;
        }

        b.MinX = minX;
        b.MinY = minY;
        b.MinZ = minZ;
        b.MaxX = maxX;
        b.MaxY = maxY;
        b.MaxZ = maxZ;
        BoxesGrid.Items.Refresh();
        RebuildViewport3D();
        _hierarchyLabelDebounce.Stop();
        _hierarchyLabelDebounce.Start();
    }

    private void ApplySpawnPropsFromFields()
    {
        if (SpawnsGrid.SelectedItem is not EditorSpawn s)
        {
            return;
        }

        if (!float.TryParse(SelSpawnX.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var x)
            || !float.TryParse(SelSpawnZ.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var z))
        {
            return;
        }

        s.X = x;
        s.Z = z;
        SpawnsGrid.Items.Refresh();
        RebuildViewport3D();
        _hierarchyLabelDebounce.Stop();
        _hierarchyLabelDebounce.Start();
    }

    private void AddEmptyBox_OnClick(object sender, RoutedEventArgs e)
    {
        _boxes.Add(new EditorBox
        {
            MinX = -1f,
            MinY = 0f,
            MinZ = -1f,
            MaxX = 1f,
            MaxY = 2f,
            MaxZ = 1f,
        });
        MainTabs.SelectedIndex = 0;
        BoxesGrid.SelectedIndex = _boxes.Count - 1;
        PushBoxPropsToFields();
        StatusText.Text = "Added 2×2×2 box at origin.";
    }

    private void AddSpawnOrigin_OnClick(object sender, RoutedEventArgs e)
    {
        _spawns.Add(new EditorSpawn { X = 0f, Z = 0f });
        MainTabs.SelectedIndex = 1;
        SpawnsGrid.SelectedIndex = _spawns.Count - 1;
        PushSpawnPropsToFields();
        StatusText.Text = "Added spawn at (0, 0).";
    }

    private void RefreshSceneHierarchy()
    {
        if (SceneTree is null)
        {
            return;
        }

        _rebuildingHierarchy = true;
        try
        {
            SceneTree.Items.Clear();
            var root = new TreeViewItem { Header = "Level", IsExpanded = true };

            root.Items.Add(new TreeViewItem { Header = FormatPlayerHierarchyHeader(), Tag = "player" });

            var boxesNode = new TreeViewItem { Header = $"Boxes ({ _boxes.Count })", IsExpanded = true };
            for (var i = 0; i < _boxes.Count; i++)
            {
                var b = _boxes[i];
                boxesNode.Items.Add(new TreeViewItem
                {
                    Header = $"[{i}]  min ({b.MinX:0.##}, {b.MinY:0.##}, {b.MinZ:0.##})  max ({b.MaxX:0.##}, {b.MaxY:0.##}, {b.MaxZ:0.##})",
                    Tag = $"box:{i}",
                });
            }

            root.Items.Add(boxesNode);

            var spawnsNode = new TreeViewItem { Header = $"Spawns ({ _spawns.Count })", IsExpanded = true };
            for (var i = 0; i < _spawns.Count; i++)
            {
                var s = _spawns[i];
                spawnsNode.Items.Add(new TreeViewItem
                {
                    Header = $"[{i}]  XZ ({s.X:0.##}, {s.Z:0.##})",
                    Tag = $"spawn:{i}",
                });
            }

            root.Items.Add(spawnsNode);
            SceneTree.Items.Add(root);
        }
        finally
        {
            _rebuildingHierarchy = false;
        }
    }

    private string FormatPlayerHierarchyHeader()
    {
        if (!float.TryParse(PlayerX.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var px)
            || !float.TryParse(PlayerY.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var py)
            || !float.TryParse(PlayerZ.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var pz))
        {
            return "Player start";
        }

        return $"Player start  ({px:0.##}, {py:0.##}, {pz:0.##})";
    }

    private void SceneTree_OnSelectedItemChanged(object sender, RoutedPropertyChangedEventArgs<object> e)
    {
        if (_rebuildingHierarchy || e.NewValue is not TreeViewItem item || item.Tag is not string tag)
        {
            return;
        }

        if (tag == "player")
        {
            PlayerX.Focus();
            return;
        }

        if (tag.StartsWith("box:", StringComparison.Ordinal) && int.TryParse(tag[4..], out var bi)
            && bi >= 0 && bi < _boxes.Count)
        {
            MainTabs.SelectedIndex = 0;
            BoxesGrid.SelectedIndex = bi;
            if (BoxesGrid.SelectedItem is not null)
            {
                BoxesGrid.ScrollIntoView(BoxesGrid.SelectedItem);
            }

            PushBoxPropsToFields();
            return;
        }

        if (tag.StartsWith("spawn:", StringComparison.Ordinal) && int.TryParse(tag[6..], out var si)
            && si >= 0 && si < _spawns.Count)
        {
            MainTabs.SelectedIndex = 1;
            SpawnsGrid.SelectedIndex = si;
            if (SpawnsGrid.SelectedItem is not null)
            {
                SpawnsGrid.ScrollIntoView(SpawnsGrid.SelectedItem);
            }

            PushSpawnPropsToFields();
        }
    }

    private void AddBoxCenter_OnClick(object sender, RoutedEventArgs e)
    {
        var c = ComputeSceneCenter();
        PlaceBoxAt(new Point3D(c.X, 0, c.Z));
        StatusText.Text = "Added box at scene center (ground).";
    }

    private void AddSpawnCenter_OnClick(object sender, RoutedEventArgs e)
    {
        var c = ComputeSceneCenter();
        PlaceSpawnAt(new Point3D(c.X, 0, c.Z));
        StatusText.Text = "Added spawn at scene center XZ.";
    }

    private void AddGroundPlate_OnClick(object sender, RoutedEventArgs e)
    {
        const float half = 22f;
        var c = ComputeSceneCenter();
        _boxes.Add(new EditorBox
        {
            MinX = (float)c.X - half,
            MinY = -0.2f,
            MinZ = (float)c.Z - half,
            MaxX = (float)c.X + half,
            MaxY = 0f,
            MaxZ = (float)c.Z + half,
        });
        StatusText.Text = "Added ground plate under scene center.";
    }

    private void DistrictMap_OnClick(object sender, RoutedEventArgs e)
    {
        PushUiFromLevel(LevelData.CreateDistrictMap());
        StatusText.Text = "District map loaded (save to push to the game).";
        ResetOrbitToScene();
        RebuildViewport3D();
        RefreshSceneHierarchy();
        ReselectGridsAfterLevelLoad();
    }

    private void DuplicateSelected_OnClick(object sender, RoutedEventArgs e)
    {
        if (!TryResolveSelection(out var kind, out var index))
        {
            StatusText.Text = "Select a box or spawn in the hierarchy or grid.";
            return;
        }

        if (kind == 'b')
        {
            var o = _boxes[index];
            _boxes.Add(CloneBoxOffset(o, 2f, 0f, 2f));
            StatusText.Text = $"Duplicated box [{index}] with +2 X/Z offset.";
        }
        else
        {
            var o = _spawns[index];
            _spawns.Add(new EditorSpawn { X = o.X + 2f, Z = o.Z + 2f });
            StatusText.Text = $"Duplicated spawn [{index}] with +2 X/Z.";
        }
    }

    private void DeleteSelected_OnClick(object sender, RoutedEventArgs e)
    {
        if (!TryResolveSelection(out var kind, out var index))
        {
            StatusText.Text = "Select a box or spawn in the hierarchy or grid.";
            return;
        }

        if (kind == 'b')
        {
            _boxes.RemoveAt(index);
            StatusText.Text = $"Removed box [{index}].";
        }
        else
        {
            _spawns.RemoveAt(index);
            StatusText.Text = $"Removed spawn [{index}].";
        }
    }

    private bool TryResolveSelection(out char kind, out int index)
    {
        kind = '\0';
        index = -1;
        if (SceneTree?.SelectedItem is TreeViewItem tvi && tvi.Tag is string tag)
        {
            if (tag.StartsWith("box:", StringComparison.Ordinal) && int.TryParse(tag[4..], out var bi))
            {
                kind = 'b';
                index = bi;
                return bi >= 0 && bi < _boxes.Count;
            }

            if (tag.StartsWith("spawn:", StringComparison.Ordinal) && int.TryParse(tag[6..], out var si))
            {
                kind = 's';
                index = si;
                return si >= 0 && si < _spawns.Count;
            }
        }

        if (MainTabs.SelectedIndex == 0)
        {
            var bi2 = BoxesGrid.SelectedIndex;
            if (bi2 >= 0 && bi2 < _boxes.Count)
            {
                kind = 'b';
                index = bi2;
                return true;
            }
        }

        if (MainTabs.SelectedIndex == 1)
        {
            var si2 = SpawnsGrid.SelectedIndex;
            if (si2 >= 0 && si2 < _spawns.Count)
            {
                kind = 's';
                index = si2;
                return true;
            }
        }

        return false;
    }

    private static EditorBox CloneBoxOffset(EditorBox o, float ox, float oy, float oz)
    {
        return new EditorBox
        {
            MinX = o.MinX + ox,
            MinY = o.MinY + oy,
            MinZ = o.MinZ + oz,
            MaxX = o.MaxX + ox,
            MaxY = o.MaxY + oy,
            MaxZ = o.MaxZ + oz,
        };
    }

    private void ViewToolCombo_OnSelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateViewportHint();

    private void UpdateViewportHint()
    {
        if (ViewportHintText is null || ViewToolCombo is null)
        {
            return;
        }

        ViewportHintText.Text = ViewToolCombo.SelectedIndex switch
        {
            1 => "Place box: left-click a surface. The box bottom sits on that point; footprint and height use toolbar X×Y×Z. Right-drag orbits.",
            2 => "Place spawn: left-click for X/Z (hit on ground or geometry). Right-drag orbits.",
            _ => "Navigate: left-drag or right-drag to orbit. Wheel zooms. Orange = player, green = spawns, blue = boxes.",
        };
    }

    private void PlayerFields_TextChanged(object sender, TextChangedEventArgs e)
    {
        _previewDebounce.Stop();
        _previewDebounce.Start();
    }

    private void LevelData_CollectionChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        _hierarchyLabelDebounce.Stop();
        RebuildViewport3D();
        RefreshSceneHierarchy();
    }

    private void ResetCamera_OnClick(object sender, RoutedEventArgs e)
    {
        ResetOrbitToScene();
        UpdateCamera();
    }

    private void Refresh3D_OnClick(object sender, RoutedEventArgs e) => RebuildViewport3D();

    private void ResetOrbitToScene()
    {
        _orbitTarget = ComputeSceneCenter();
        var span = ComputeSceneSpan();
        _orbitDistance = Math.Clamp(Math.Max(span.X, span.Z) * 1.35 + span.Y * 0.5, 12, 220);
        _orbitYaw = 0.65;
        _orbitPitch = 0.38;
    }

    private Point3D ComputeSceneCenter()
    {
        double minX = double.MaxValue, maxX = double.MinValue;
        double minY = double.MaxValue, maxY = double.MinValue;
        double minZ = double.MaxValue, maxZ = double.MinValue;

        void expand(double x, double y, double z)
        {
            minX = Math.Min(minX, x);
            maxX = Math.Max(maxX, x);
            minY = Math.Min(minY, y);
            maxY = Math.Max(maxY, y);
            minZ = Math.Min(minZ, z);
            maxZ = Math.Max(maxZ, z);
        }

        foreach (var b in _boxes)
        {
            expand(b.MinX, b.MinY, b.MinZ);
            expand(b.MaxX, b.MaxY, b.MaxZ);
        }

        if (float.TryParse(PlayerX.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var px)
            && float.TryParse(PlayerY.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var py)
            && float.TryParse(PlayerZ.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var pz))
        {
            expand(px, py + 0.9, pz);
        }

        foreach (var s in _spawns)
        {
            expand(s.X, 0, s.Z);
        }

        if (minX > maxX)
        {
            return new Point3D(0, 1, 0);
        }

        return new Point3D((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
    }

    private Vector3D ComputeSceneSpan()
    {
        double minX = double.MaxValue, maxX = double.MinValue;
        double minY = double.MaxValue, maxY = double.MinValue;
        double minZ = double.MaxValue, maxZ = double.MinValue;

        void expand(double x, double y, double z)
        {
            minX = Math.Min(minX, x);
            maxX = Math.Max(maxX, x);
            minY = Math.Min(minY, y);
            maxY = Math.Max(maxY, y);
            minZ = Math.Min(minZ, z);
            maxZ = Math.Max(maxZ, z);
        }

        foreach (var b in _boxes)
        {
            expand(b.MinX, b.MinY, b.MinZ);
            expand(b.MaxX, b.MaxY, b.MaxZ);
        }

        if (minX > maxX)
        {
            return new Vector3D(28, 8, 28);
        }

        return new Vector3D(maxX - minX, maxY - minY, maxZ - minZ);
    }

    private void RebuildViewport3D()
    {
        _levelGroup.Children.Clear();
        _orbitTarget = ComputeSceneCenter();

        var span = ComputeSceneSpan();
        var ext = Math.Max(span.X, Math.Max(span.Y, span.Z));
        var skyHalf = Math.Clamp(Math.Max(ext * 2.85, 150), 150, 400);
        var groundHalf = Math.Clamp(Math.Max(ext * 1.95, 96), 96, 300);
        var triadArm = Math.Clamp(ext * 0.045, 1.4, 4.0);

        _levelGroup.Children.Add(
            Editor3D.Skybox(
                _orbitTarget,
                skyHalf,
                side: Color.FromRgb(78, 102, 138),
                top: Color.FromRgb(130, 176, 224),
                bottom: Color.FromRgb(40, 48, 58)));
        _levelGroup.Children.Add(Editor3D.GroundGridPlane(_orbitTarget, groundHalf));
        _levelGroup.Children.Add(Editor3D.AxisTriad(new Point3D(_orbitTarget.X, 0.02, _orbitTarget.Z), arm: triadArm, thickness: Math.Clamp(triadArm * 0.035, 0.04, 0.09)));

        foreach (var b in _boxes)
        {
            var min = new Point3D(b.MinX, b.MinY, b.MinZ);
            var max = new Point3D(b.MaxX, b.MaxY, b.MaxZ);
            var model = Editor3D.LitBox(min, max, Color.FromRgb(88, 128, 188), 0.92);
            _levelGroup.Children.Add(model);
        }

        if (float.TryParse(PlayerX.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var px)
            && float.TryParse(PlayerY.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var py)
            && float.TryParse(PlayerZ.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var pz))
        {
            var half = 0.32;
            var h = 1.78;
            var pMin = new Point3D(px - half, py, pz - half);
            var pMax = new Point3D(px + half, py + h, pz + half);
            _levelGroup.Children.Add(Editor3D.LitBox(pMin, pMax, Color.FromRgb(255, 130, 90), 1));
        }

        foreach (var s in _spawns)
        {
            var t = 0.06;
            var pad = Editor3D.LitBox(
                new Point3D(s.X - 0.55, t, s.Z - 0.55),
                new Point3D(s.X + 0.55, t * 2, s.Z + 0.55),
                Color.FromRgb(70, 190, 95),
                1);
            _levelGroup.Children.Add(pad);
        }

        UpdateCamera();
    }

    private void UpdateCamera()
    {
        var ch = Math.Cos(_orbitPitch);
        var dx = ch * Math.Sin(_orbitYaw);
        var dy = Math.Sin(_orbitPitch);
        var dz = ch * Math.Cos(_orbitYaw);
        var dir = new Vector3D(dx, dy, dz);
        dir.Normalize();
        var pos = _orbitTarget - dir * _orbitDistance;
        PreviewCamera.Position = pos;
        PreviewCamera.LookDirection = _orbitTarget - pos;
        PreviewCamera.UpDirection = new Vector3D(0, 1, 0);
    }

    private void Viewport3D_OnMouseDown(object sender, MouseButtonEventArgs e)
    {
        var p = e.GetPosition(PreviewViewport);

        if (e.ChangedButton == MouseButton.Right)
        {
            if (_placeLeftDown)
            {
                _placeDragged = true;
            }

            _orbitDragging = true;
            _orbitButton = MouseButton.Right;
            _orbitLastPos = p;
            PreviewViewport.CaptureMouse();
            return;
        }

        if (e.ChangedButton != MouseButton.Left)
        {
            return;
        }

        if (ViewToolCombo.SelectedIndex == 0)
        {
            _orbitDragging = true;
            _orbitButton = MouseButton.Left;
            _orbitLastPos = p;
            PreviewViewport.CaptureMouse();
            return;
        }

        _placeLeftDown = true;
        _placeDragged = false;
        _placeStart = p;
        PreviewViewport.CaptureMouse();
    }

    private void Viewport3D_OnMouseMove(object sender, MouseEventArgs e)
    {
        var p = e.GetPosition(PreviewViewport);

        if (_orbitDragging)
        {
            var dx = p.X - _orbitLastPos.X;
            var dy = p.Y - _orbitLastPos.Y;
            _orbitLastPos = p;
            _orbitYaw += dx * 0.008;
            _orbitPitch = Math.Clamp(_orbitPitch + dy * 0.008, -1.45, 1.45);
            UpdateCamera();
            return;
        }

        if (_placeLeftDown)
        {
            var d = p - _placeStart;
            if (d.Length > 5)
            {
                _placeDragged = true;
            }
        }
    }

    private void Viewport3D_OnMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (_orbitDragging && e.ChangedButton == _orbitButton)
        {
            _orbitDragging = false;
            if (!_placeLeftDown)
            {
                PreviewViewport.ReleaseMouseCapture();
            }
        }

        if (e.ChangedButton == MouseButton.Left && _placeLeftDown)
        {
            _placeLeftDown = false;
            if (!_orbitDragging)
            {
                PreviewViewport.ReleaseMouseCapture();
            }

            if (!_placeDragged && ViewToolCombo.SelectedIndex is 1 or 2)
            {
                TryPlaceAt(e.GetPosition(PreviewViewport));
            }
        }
    }

    private void TryPlaceAt(Point mouse)
    {
        var w = PreviewViewport.ActualWidth;
        var h = PreviewViewport.ActualHeight;
        if (!Editor3D.TryWorldRay(PreviewCamera, w, h, mouse, out var origin, out var dir))
        {
            return;
        }

        var boxBounds = _boxes.Select(b => (
            new Point3D(b.MinX, b.MinY, b.MinZ),
            new Point3D(b.MaxX, b.MaxY, b.MaxZ)));

        if (!Editor3D.TryClosestHit(origin, dir, 0, boxBounds, out var hit, out _))
        {
            StatusText.Text = "Click did not hit the ground or any box.";
            return;
        }

        if (ViewToolCombo.SelectedIndex == 1)
        {
            PlaceBoxAt(hit);
        }
        else if (ViewToolCombo.SelectedIndex == 2)
        {
            PlaceSpawnAt(hit);
        }
    }

    private void PlaceBoxAt(Point3D hit)
    {
        static float Dim(TextBox tb, float d) =>
            Math.Max(0.25f, float.TryParse(tb.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : d);

        var sx = Dim(NewBoxSizeX, 4f);
        var sy = Dim(NewBoxSizeY, 2f);
        var sz = Dim(NewBoxSizeZ, 4f);
        var hx = (float)hit.X;
        var hy = (float)hit.Y;
        var hz = (float)hit.Z;
        _boxes.Add(new EditorBox
        {
            MinX = hx - sx * 0.5f,
            MaxX = hx + sx * 0.5f,
            MinY = hy,
            MaxY = hy + sy,
            MinZ = hz - sz * 0.5f,
            MaxZ = hz + sz * 0.5f,
        });
        StatusText.Text = $"Added box (footprint {sx}×{sz}, height {sy}) at ({hx:F2}, {hy:F2}, {hz:F2}).";
    }

    private void PlaceSpawnAt(Point3D hit)
    {
        _spawns.Add(new EditorSpawn { X = (float)hit.X, Z = (float)hit.Z });
        StatusText.Text = $"Added spawn at XZ ({hit.X:F2}, {hit.Z:F2}).";
    }

    private void Viewport3D_OnMouseWheel(object sender, MouseWheelEventArgs e)
    {
        var f = e.Delta > 0 ? 0.9 : 1.11;
        _orbitDistance = Math.Clamp(_orbitDistance * f, 4, 400);
        UpdateCamera();
    }

    private static string GameLevelPath()
    {
        var editorBin = AppContext.BaseDirectory;
        var solutionRelative = global::System.IO.Path.GetFullPath(global::System.IO.Path.Combine(editorBin, "..", "..", "..", "..", "ZombieSurge.Game", "Content", "levels", "default.json"));
        return solutionRelative;
    }

    private void PushUiFromLevel(LevelData level)
    {
        PlayerX.Text = level.PlayerStart.X.ToString(CultureInfo.InvariantCulture);
        PlayerY.Text = level.PlayerStart.Y.ToString(CultureInfo.InvariantCulture);
        PlayerZ.Text = level.PlayerStart.Z.ToString(CultureInfo.InvariantCulture);

        _boxes.CollectionChanged -= LevelData_CollectionChanged;
        _spawns.CollectionChanged -= LevelData_CollectionChanged;
        try
        {
            _boxes.Clear();
            foreach (var b in level.Boxes)
            {
                _boxes.Add(new EditorBox
                {
                    MinX = b.Min.X,
                    MinY = b.Min.Y,
                    MinZ = b.Min.Z,
                    MaxX = b.Max.X,
                    MaxY = b.Max.Y,
                    MaxZ = b.Max.Z,
                });
            }

            _spawns.Clear();
            foreach (var s in level.ZombieSpawns)
            {
                _spawns.Add(new EditorSpawn { X = s.X, Z = s.Z });
            }
        }
        finally
        {
            _boxes.CollectionChanged += LevelData_CollectionChanged;
            _spawns.CollectionChanged += LevelData_CollectionChanged;
        }
    }

    private LevelData PullLevelFromUi()
    {
        static float ParseBox(System.Windows.Controls.TextBox tb, float fallback)
        {
            return float.TryParse(tb.Text, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : fallback;
        }

        var level = new LevelData
        {
            PlayerStart = new Vec3(ParseBox(PlayerX, 0f), ParseBox(PlayerY, 0f), ParseBox(PlayerZ, 0f)),
        };

        foreach (var row in _boxes)
        {
            level.Boxes.Add(new LevelBox
            {
                Min = new Vec3(row.MinX, row.MinY, row.MinZ),
                Max = new Vec3(row.MaxX, row.MaxY, row.MaxZ),
            });
        }

        foreach (var row in _spawns)
        {
            level.ZombieSpawns.Add(new Vec3(row.X, 0f, row.Z));
        }

        return level;
    }

    private void LoadFromPath(string path, bool quiet)
    {
        try
        {
            var level = LevelIO.LoadOrDefault(path);
            PushUiFromLevel(level);
            StatusText.Text = quiet ? "" : $"Loaded {path}";
            ResetOrbitToScene();
            RebuildViewport3D();
            RefreshSceneHierarchy();
            ReselectGridsAfterLevelLoad();
        }
        catch (Exception ex)
        {
            StatusText.Text = "Load failed: " + ex.Message;
        }
    }

    private void ReselectGridsAfterLevelLoad()
    {
        MainTabs.SelectedIndex = 0;
        BoxesGrid.SelectedIndex = _boxes.Count > 0 ? 0 : -1;
        PushBoxPropsToFields();
        SpawnsGrid.SelectedIndex = _spawns.Count > 0 ? 0 : -1;
        PushSpawnPropsToFields();
    }

    private void NewArena_OnClick(object sender, RoutedEventArgs e)
    {
        PushUiFromLevel(LevelData.CreateDefaultArena());
        StatusText.Text = "New arena template (not saved yet).";
        ResetOrbitToScene();
        RebuildViewport3D();
        RefreshSceneHierarchy();
        ReselectGridsAfterLevelLoad();
    }

    private void SmallMap_OnClick(object sender, RoutedEventArgs e)
    {
        PushUiFromLevel(LevelData.CreateSmallPlaytestArena());
        StatusText.Text = "Small playtest map (save when ready).";
        ResetOrbitToScene();
        RebuildViewport3D();
        RefreshSceneHierarchy();
        ReselectGridsAfterLevelLoad();
    }

    private void Load_OnClick(object sender, RoutedEventArgs e)
    {
        LoadFromPath(GameLevelPath(), quiet: false);
    }

    private void Save_OnClick(object sender, RoutedEventArgs e)
    {
        try
        {
            var path = GameLevelPath();
            Directory.CreateDirectory(global::System.IO.Path.GetDirectoryName(path)!);
            LevelIO.Save(PullLevelFromUi(), path);
            StatusText.Text = $"Saved {path}";
        }
        catch (Exception ex)
        {
            StatusText.Text = "Save failed: " + ex.Message;
        }
    }

    public sealed class EditorBox
    {
        public float MinX { get; set; }
        public float MinY { get; set; }
        public float MinZ { get; set; }
        public float MaxX { get; set; }
        public float MaxY { get; set; }
        public float MaxZ { get; set; }
    }

    public sealed class EditorSpawn
    {
        public float X { get; set; }
        public float Z { get; set; }
    }
}
