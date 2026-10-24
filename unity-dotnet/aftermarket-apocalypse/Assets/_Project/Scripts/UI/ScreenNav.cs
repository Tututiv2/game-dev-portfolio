using System;
using TMPro;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// The bar that lets the three Phase 8 screens reach each other — Store,
/// Collection and Character.
///
/// <para>Before this they were three scenes with no way between them: you could
/// only reach each by opening it in the editor, so the whole §15.12 surface was
/// unreachable as a thing you could actually walk through.</para>
///
/// <para><b>A scene not in Build Settings cannot be loaded, and the failure is
/// ugly.</b> <c>SceneManager.LoadScene</c> throws at runtime for a scene that was
/// never added, which in a UI callback means the button appears to do nothing and
/// the console fills with an exception nobody reads. So every target is checked
/// with <see cref="Application.CanStreamedLevelBeLoaded"/> at Start and a button
/// whose scene is missing is DISABLED with its label struck through, rather than
/// left looking clickable. <c>NavTests</c> asserts the build list matches at
/// author time so it should never come to that.</para>
/// </summary>
public class ScreenNav : MonoBehaviour
{
    [Serializable]
    public class Target
    {
        [Tooltip("Scene NAME, not path — this is what SceneManager.LoadScene takes.")]
        public string sceneName;
        public string label;
        public Button button;
    }

    public Target[] targets = new Target[0];

    [Tooltip("The scene this bar lives in. Its own button is shown pressed and does nothing.")]
    public string currentSceneName;

    void Start()
    {
        foreach (Target t in targets)
        {
            if (t == null || t.button == null) continue;

            TMP_Text label = t.button.GetComponentInChildren<TMP_Text>();
            if (label != null && !string.IsNullOrEmpty(t.label)) label.text = t.label;

            bool isHere = t.sceneName == currentSceneName;
            bool loadable = Application.CanStreamedLevelBeLoaded(t.sceneName);

            if (isHere)
            {
                // You are here. Not an error, just nothing to do.
                t.button.interactable = false;
                if (label != null) label.color = new Color(0.55f, 0.55f, 0.6f);
                continue;
            }

            if (!loadable)
            {
                t.button.interactable = false;
                if (label != null)
                {
                    label.text = t.label + " (missing)";
                    label.color = new Color(0.8f, 0.35f, 0.35f);
                }
                Debug.LogError("[Nav] scene '" + t.sceneName + "' is not in Build Settings, so it " +
                               "can never be loaded at runtime. Add it via " +
                               "Tools > Aftermarket > Wire Screen Navigation.");
                continue;
            }

            string captured = t.sceneName;
            t.button.onClick.AddListener(delegate { Go(captured); });
        }
    }

    /// <summary>
    /// Load a sibling screen.
    ///
    /// <para>Guarded rather than trusting: a nav bar built against a stale scene
    /// list would otherwise throw from inside a UI callback, where the exception
    /// is easy to miss and the symptom is "the button does nothing".</para>
    /// </summary>
    public void Go(string sceneName)
    {
        if (!Application.CanStreamedLevelBeLoaded(sceneName))
        {
            Debug.LogError("[Nav] cannot load '" + sceneName + "' — it is not in Build Settings.");
            return;
        }
        SceneManager.LoadScene(sceneName, LoadSceneMode.Single);
    }
}
