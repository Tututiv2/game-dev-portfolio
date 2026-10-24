using System.Collections.Generic;
using NUnit.Framework;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// Guards for the front door.
///
/// <para>Until this scene existed the project was a set of scenes with no way in
/// as a human: the map was not in Build Settings at all, and build index 0 was
/// the untouched URP template. Both were invisible to every existing test,
/// because both are ABSENCES.</para>
/// </summary>
public class MainMenuTests
{
    const string k_Menu = "Assets/_Project/Scenes/MainMenu.unity";
    const string k_Map = "Assets/_Project/Scenes/AshfallDepot.unity";

    static void RequireMenu()
    {
        if (!System.IO.File.Exists(k_Menu))
            Assert.Ignore("MainMenu.unity does not exist yet. Run Tools > Aftermarket > Build Main Menu.");
    }

    /// <summary>
    /// The game must OPEN on the menu. Index 0 is what a build launches and what
    /// Play-from-a-fresh-project runs; it was SampleScene, the untouched URP
    /// template, so every build opened an empty grey room.
    /// </summary>
    [Test]
    public void Menu_TheGameStartsOnTheMainMenu()
    {
        RequireMenu();
        var scenes = EditorBuildSettings.scenes;
        Assert.That(scenes.Length, Is.GreaterThan(0), "Build Settings is empty.");
        Assert.That(scenes[0].path, Is.EqualTo(k_Menu),
            "build index 0 is '" + scenes[0].path + "'. That is the scene a build launches, so " +
            "the game would open on it instead of the menu.");
        Assert.That(scenes[0].enabled, Is.True, "the menu is present but DISABLED at index 0.");
    }

    /// <summary>
    /// The one that matters most: PLAY has to be able to reach the map.
    /// SceneManager.LoadScene throws for a scene that is not in Build Settings,
    /// and from inside a UI callback that exception goes to a console nobody has
    /// open — so the symptom is a button that does nothing at all.
    /// </summary>
    [Test]
    public void Menu_TheMapIsInBuildSettingsSoPlayCanLoadIt()
    {
        RequireMenu();
        bool found = false;
        foreach (var s in EditorBuildSettings.scenes)
            if (s.path == k_Map) { found = true; Assert.That(s.enabled, Is.True,
                "AshfallDepot is in Build Settings but disabled, which is invisible to " +
                "CanStreamedLevelBeLoaded and fails exactly like being absent."); }

        Assert.That(found, Is.True,
            "AshfallDepot.unity is not in Build Settings, so nothing can load the map — " +
            "not the menu, not a nav bar, and a built player would not contain it.");
    }

    /// <summary>
    /// Every button must point at a scene that is actually shipped, and must
    /// carry a PERSISTENT listener. A listener added at runtime is not
    /// serialized, so the buttons would work in the editor session that built
    /// them and do nothing in a build.
    /// </summary>
    [Test]
    public void Menu_EveryButtonIsWiredToASceneThatShips()
    {
        RequireMenu();
        var scene = EditorSceneManager.OpenScene(k_Menu, OpenSceneMode.Additive);
        try
        {
            var shipped = new HashSet<string>();
            foreach (var s in EditorBuildSettings.scenes)
                if (s.enabled) shipped.Add(System.IO.Path.GetFileNameWithoutExtension(s.path));

            var buttons = new List<Button>();
            foreach (var root in scene.GetRootGameObjects())
                buttons.AddRange(root.GetComponentsInChildren<Button>(true));

            Assert.That(buttons.Count, Is.GreaterThan(0), "the menu has no buttons at all.");

            var problems = new List<string>();
            foreach (var b in buttons)
            {
                int persistent = b.onClick.GetPersistentEventCount();
                if (persistent == 0) { problems.Add(b.name + ": no persistent listener"); continue; }

                for (int i = 0; i < persistent; i++)
                {
                    if (b.onClick.GetPersistentTarget(i) == null)
                        problems.Add(b.name + ": listener " + i + " targets nothing");
                    string method = b.onClick.GetPersistentMethodName(i);
                    if (string.IsNullOrEmpty(method))
                        problems.Add(b.name + ": listener " + i + " calls no method");
                }
            }

            // The scene name is READ OUT OF THE WIRED ARGUMENT, not re-derived
            // from the button's label.
            //
            // The first version of this test mapped label -> scene itself
            // ("STORE" -> "STORE") and failed on the one case where the two
            // differ, because the scene is "Store". That is the same defect it
            // is meant to catch, one level up: a check that recomputes a value
            // instead of reading the one the game actually uses can only ever
            // test its own arithmetic.
            foreach (var b in buttons)
            {
                var so = new SerializedObject(b);
                var calls = so.FindProperty("m_OnClick.m_PersistentCalls.m_Calls");
                for (int i = 0; i < calls.arraySize; i++)
                {
                    var call = calls.GetArrayElementAtIndex(i);
                    if (call.FindPropertyRelative("m_MethodName").stringValue != "Go") continue;
                    string target = call
                        .FindPropertyRelative("m_Arguments.m_StringArgument").stringValue;

                    if (string.IsNullOrEmpty(target))
                        problems.Add(b.name + ": Go() is wired with an EMPTY scene name");
                    else if (!shipped.Contains(target))
                        problems.Add(b.name + ": target scene '" + target + "' is not shipped");
                }
            }

            Assert.That(problems, Is.Empty, string.Join("; ", problems));
        }
        finally
        {
            EditorSceneManager.CloseScene(scene, true);
        }
    }

    /// <summary>
    /// A TextMeshPro component with a null font renders NOTHING while looking
    /// perfectly healthy in the hierarchy. That is why the builder refuses to
    /// run without a font, and this is the half that survives a rebuild.
    /// </summary>
    [Test]
    public void Menu_EveryLabelHasAFont()
    {
        RequireMenu();
        var scene = EditorSceneManager.OpenScene(k_Menu, OpenSceneMode.Additive);
        try
        {
            var missing = new List<string>();
            foreach (var root in scene.GetRootGameObjects())
                foreach (var t in root.GetComponentsInChildren<TextMeshProUGUI>(true))
                    if (t.font == null) missing.Add(t.name);

            Assert.That(missing, Is.Empty,
                "these labels have no font and render nothing: " + string.Join(", ", missing));
        }
        finally
        {
            EditorSceneManager.CloseScene(scene, true);
        }
    }
}
