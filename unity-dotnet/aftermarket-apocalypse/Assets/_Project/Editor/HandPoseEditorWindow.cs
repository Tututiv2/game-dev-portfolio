using System.IO;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Drag a slider, see the hands change. <c>Tools > Aftermarket > First-Person
/// Hand Editor</c>.
///
/// <para>Built because the loop for "the hands are too closed" was: I pick a
/// number, edit a constant, run batch-mode Unity, render seven PNGs, look at
/// one, and ask whether that is what you meant. Four rounds of that had gone by
/// and I was still the one deciding what "more open" meant — which is a judgement
/// only the owner can make.</para>
///
/// <para>It drives the REAL <see cref="FirstPersonBody"/> against the REAL
/// character prefab, not a mock. A preview that poses the rig its own way would
/// be a second implementation to keep in step, and it would be the one that
/// looked right while the game looked wrong.</para>
/// </summary>
public class HandPoseEditorWindow : EditorWindow
{
    const string k_Catalog = "Assets/_Project/Content/Characters/CharacterCatalog.asset";
    const string k_Asset = "Assets/_Project/Content/HandPose.asset";

    HandPoseSettings m_settings;
    CharacterCatalog m_catalog;
    GameObject m_root;
    FirstPersonBody m_body;
    Camera m_cam;
    RenderTexture m_rt;
    Editor m_inspector;

    int m_character;
    float m_pitch;
    bool m_lookFromEye = true;

    [MenuItem("Tools/Aftermarket/First-Person Hand Editor")]
    public static void Open()
    {
        var w = GetWindow<HandPoseEditorWindow>("Hand Pose");
        w.minSize = new Vector2(560f, 640f);
        w.Show();
    }

    void OnEnable()
    {
        m_catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_Catalog);
        m_settings = AssetDatabase.LoadAssetAtPath<HandPoseSettings>(k_Asset);
        if (m_settings == null) m_settings = CreateSettingsAsset();
        BuildRig();
    }

    void OnDisable() { Teardown(); }

    static HandPoseSettings CreateSettingsAsset()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(k_Asset));
        var s = CreateInstance<HandPoseSettings>();
        AssetDatabase.CreateAsset(s, k_Asset);
        AssetDatabase.SaveAssets();
        Debug.Log("[HandPose] created " + k_Asset);
        return s;
    }

    void BuildRig()
    {
        Teardown();
        if (m_catalog == null) return;

        // hideFlags.DontSave: this rig lives only for the window. Without it the
        // preview character is left behind in whatever scene happened to be open
        // and gets saved into it.
        m_root = new GameObject("HandPosePreview") { hideFlags = HideFlags.HideAndDontSave };
        var holder = new GameObject("CameraHolder") { hideFlags = HideFlags.HideAndDontSave };
        holder.transform.SetParent(m_root.transform, false);

        m_body = m_root.AddComponent<FirstPersonBody>();
        m_body.catalog = m_catalog;
        m_body.handPose = m_settings;
        m_body.cameraHolder = holder.transform;

        // The camera must exist BEFORE Spawn: the arms copy is parented to it.
        holder.transform.localPosition = Vector3.up * m_body.eyeHeight;
        var camGo = new GameObject("PreviewCam") { hideFlags = HideFlags.HideAndDontSave };
        camGo.transform.SetParent(holder.transform, false);
        m_cam = camGo.AddComponent<Camera>();
        m_cam.fieldOfView = 60f;
        m_cam.nearClipPlane = 0.02f;
        m_cam.farClipPlane = 50f;
        m_cam.clearFlags = CameraClearFlags.SolidColor;
        m_cam.backgroundColor = new Color(0.13f, 0.13f, 0.16f);
        m_cam.enabled = false;                      // rendered on demand only

        var def = m_catalog.characters != null && m_character < m_catalog.characters.Length
                  ? m_catalog.characters[m_character] : null;
        if (def != null) PlayerPrefs.SetString("SelectedCharacter", def.characterId);

        if (!m_body.Spawn()) { Teardown(); return; }
        m_body.ResolveEye();
        m_body.ApplySettings();
        m_body.CalibrateBend();
        m_body.CalibrateSpine();
    }

    void Teardown()
    {
        if (m_rt != null) { m_rt.Release(); DestroyImmediate(m_rt); m_rt = null; }
        if (m_root != null) { DestroyImmediate(m_root); m_root = null; }
        m_body = null;
        m_cam = null;
    }

    void OnGUI()
    {
        if (m_catalog == null)
        {
            EditorGUILayout.HelpBox("No CharacterCatalog. Run Build Character Select.",
                                    MessageType.Error);
            return;
        }

        EditorGUI.BeginChangeCheck();

        using (new EditorGUILayout.HorizontalScope())
        {
            var names = new string[m_catalog.characters.Length];
            for (int i = 0; i < names.Length; i++)
                names[i] = m_catalog.characters[i] == null ? "-" : m_catalog.characters[i].displayName;
            int pick = EditorGUILayout.Popup("Character", m_character, names);
            if (pick != m_character) { m_character = pick; BuildRig(); }
            if (GUILayout.Button("Rebuild", GUILayout.Width(70f))) BuildRig();
        }

        m_lookFromEye = EditorGUILayout.Toggle("View from the eyes", m_lookFromEye);
        m_pitch = EditorGUILayout.Slider("Look up / down", m_pitch, -60f, 60f);

        EditorGUILayout.Space();
        if (m_inspector == null || m_inspector.target != m_settings)
            m_inspector = Editor.CreateEditor(m_settings);
        m_inspector.OnInspectorGUI();

        bool changed = EditorGUI.EndChangeCheck();

        EditorGUILayout.Space();
        using (new EditorGUILayout.HorizontalScope())
        {
            if (GUILayout.Button("Open hands wide")) Preset(0.02f, 0.12f, -0.15f, 1.25f);
            if (GUILayout.Button("Relaxed")) Preset(0.16f, 0.30f, -0.30f, 1f);
            if (GUILayout.Button("Fist")) Preset(0.85f, 0.90f, -0.45f, 0.2f);
        }

        EditorGUILayout.HelpBox(
            "Changes are saved into " + k_Asset + " as you drag, and the game reads " +
            "that asset — so what you see here is what you get in play.",
            MessageType.None);

        Repaint();
        DrawPreview(changed);
    }

    void Preset(float curl, float thumb, float spread, float fan)
    {
        Undo.RecordObject(m_settings, "hand preset");
        m_settings.fingerCurl = curl;
        m_settings.thumbCurl = thumb;
        m_settings.thumbSpread = spread;
        m_settings.fan = fan;
        EditorUtility.SetDirty(m_settings);
        AssetDatabase.SaveAssets();
        if (m_body != null) m_body.ApplySettings();
    }

    void DrawPreview(bool changed)
    {
        if (m_body == null || m_cam == null) return;

        if (changed)
        {
            EditorUtility.SetDirty(m_settings);
            m_body.ApplySettings();
        }

        m_cam.transform.parent.localRotation = Quaternion.Euler(m_pitch, 0f, 0f);
        if (m_lookFromEye)
        {
            m_cam.transform.localPosition = Vector3.zero;
            m_cam.transform.localRotation = Quaternion.identity;
        }
        else
        {
            // Over the shoulder, so the arms can be judged as arms and not just
            // as two hands filling the frame.
            m_cam.transform.localPosition = new Vector3(0.75f, 0.15f, -0.85f);
            m_cam.transform.localRotation = Quaternion.Euler(6f, -32f, 0f);
        }
        m_body.PushPose();

        Rect r = GUILayoutUtility.GetRect(position.width, 300f);
        int w = Mathf.Max(64, (int)r.width);
        int h = Mathf.Max(64, (int)r.height);
        if (m_rt == null || m_rt.width != w || m_rt.height != h)
        {
            if (m_rt != null) { m_rt.Release(); DestroyImmediate(m_rt); }
            m_rt = new RenderTexture(w, h, 24) { antiAliasing = 4 };
        }

        m_cam.targetTexture = m_rt;
        m_cam.Render();
        m_cam.targetTexture = null;
        GUI.DrawTexture(r, m_rt, ScaleMode.StretchToFill, false);
    }
}
