using System.IO;
using NUnit.Framework;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// The three Phase 8 screens have to be able to reach each other.
///
/// <para><b>The failure this exists to catch is silent-ish and total:</b>
/// <c>SceneManager.LoadScene</c> throws for a scene that is not in Build Settings.
/// From inside a UI button callback that reads as "the button does nothing", and
/// the exception goes to a console nobody has open. Worse, the scenes are BUILD
/// OUTPUTS — re-running a builder is routine, and a rebuild that forgot to
/// re-register would break navigation without touching a line of code.</para>
/// </summary>
public class NavTests
{
    [Test]
    public void Nav_EveryScreenExistsOnDisk()
    {
        foreach (string path in NavBarBuilder.ScenePaths)
        {
            Assert.IsTrue(File.Exists(path),
                "No scene at " + path + ". Run its builder under Tools > Aftermarket.");
        }
    }

    /// <summary>
    /// A scene that is absent from Build Settings — or present but DISABLED, which
    /// looks fine in the inspector — cannot be loaded at runtime at all.
    /// </summary>
    [Test]
    public void Nav_EveryScreenIsInBuildSettingsAndEnabled()
    {
        foreach (string path in NavBarBuilder.ScenePaths)
        {
            if (!File.Exists(path)) continue;

            bool found = false;
            foreach (EditorBuildSettingsScene s in EditorBuildSettings.scenes)
            {
                if (s.path != path) continue;
                found = true;
                Assert.IsTrue(s.enabled,
                    path + " is in Build Settings but DISABLED. Application." +
                    "CanStreamedLevelBeLoaded returns false for it, so every nav button " +
                    "pointing at it is dead — and a disabled row looks correct at a glance.");
                break;
            }
            Assert.IsTrue(found,
                path + " is not in Build Settings, so SceneManager.LoadScene throws for it at " +
                "runtime. Run Tools > Aftermarket > Wire Screen Navigation.");
        }
    }

    [Test]
    public void Nav_TheLabelListMatchesTheSceneList()
    {
        Assert.AreEqual(NavBarBuilder.ScenePaths.Length, NavBarBuilder.Labels.Length,
            "NavBarBuilder has " + NavBarBuilder.ScenePaths.Length + " scenes but " +
            NavBarBuilder.Labels.Length + " labels. The builder indexes them together, so the " +
            "shorter one throws mid-build and leaves a half-built bar.");
    }

    /// <summary>
    /// Each bar must know which scene it is IN. If it does not, the wrong button
    /// is greyed out and one screen becomes unreachable while another leads to
    /// itself — which reads as the navigation being broken generally.
    /// </summary>
    [Test]
    public void Nav_EachScreenCarriesABarThatKnowsWhereItIs()
    {
        // Opening a scene from a script DISCARDS unsaved changes without asking.
        // Same guard MapSceneTests uses, for the same reason: running the tests is
        // a routine thing to do and must never cost someone their work.
        if (EditorSceneManager.GetActiveScene().isDirty &&
            !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            Assert.Ignore("Skipped: the open scene has unsaved changes and loading the Phase 8 " +
                          "screens would discard them.");

        foreach (string path in NavBarBuilder.ScenePaths)
        {
            if (!File.Exists(path)) continue;

            Scene scene = EditorSceneManager.OpenScene(path, OpenSceneMode.Single);
            ScreenNav nav = null;
            foreach (GameObject root in scene.GetRootGameObjects())
            {
                nav = root.GetComponentInChildren<ScreenNav>(true);
                if (nav != null) break;
            }

            Assert.IsNotNull(nav, path + " has no ScreenNav, so it is a dead end.");
            Assert.AreEqual(NavBarBuilder.NameOf(path), nav.currentSceneName,
                path + "'s nav bar thinks it is in '" + nav.currentSceneName + "'. It will grey " +
                "out the wrong button, so one screen becomes unreachable and another links to " +
                "itself.");

            Assert.AreEqual(NavBarBuilder.ScenePaths.Length, nav.targets.Length,
                path + "'s bar offers " + nav.targets.Length + " destinations, not " +
                NavBarBuilder.ScenePaths.Length + ".");

            foreach (ScreenNav.Target t in nav.targets)
            {
                Assert.IsNotNull(t.button,
                    path + "'s bar has a target ('" + t.sceneName + "') with no button, so that " +
                    "destination silently does not exist.");

                bool known = false;
                foreach (string p in NavBarBuilder.ScenePaths)
                {
                    if (NavBarBuilder.NameOf(p) == t.sceneName) { known = true; break; }
                }
                Assert.IsTrue(known,
                    path + "'s bar points at '" + t.sceneName + "', which is not one of the " +
                    "known screens. LoadScene would throw.");
            }
        }
    }
}
