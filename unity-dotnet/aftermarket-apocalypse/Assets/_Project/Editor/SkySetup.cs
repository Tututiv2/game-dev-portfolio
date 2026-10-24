using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;

/// <summary>
/// Phase 3, layer 3 (part 1): image-based lighting from a free CC0 HDRI.
///
/// §17: "A free CC0 HDRI (polyhaven.com) replaces Unity's procedural skybox and
/// gives image-based lighting with real colour and direction. Costs nothing, no
/// attribution required."
///
/// <para>Unlike <see cref="MapSeeder"/>, this tool <b>is</b> safe to re-run. It
/// makes a targeted, additive edit — skybox material and ambient source — rather
/// than authoring the scene, so it does not throw away hand dressing. Re-running
/// after changing the exposure or rotation below is the intended workflow.</para>
/// </summary>
public static class SkySetup
{
    const string k_Hdri = "Assets/_Project/Art/Sky/belfast_sunset_puresky_2k.hdr";
    const string k_Material = "Assets/_Project/Art/Sky/AshfallSky.mat";

    /// <summary>Degrees. Which way the HDRI's sun faces; match the Sun light to it by eye.</summary>
    const float k_Rotation = 200f;

    /// <summary>Multiplier on the HDRI. Ashfall wants it slightly hot.</summary>
    const float k_Exposure = 1.15f;

    [MenuItem("Tools/Aftermarket/Apply Sky")]
    public static void Apply()
    {
        if (!File.Exists(k_Hdri))
        {
            Debug.LogError($"[SkySetup] no HDRI at {k_Hdri}. It is a free CC0 download from " +
                           "polyhaven.com (belfast_sunset_puresky, 2k .hdr).");
            return;
        }

        var material = BuildSkyboxMaterial();
        if (material == null) return;

        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[SkySetup] no map scene at {MapSeeder.ScenePath}. " +
                           "Run Tools > Aftermarket > Seed Map Scene first.");
            return;
        }

        // Same hazard SceneShot has, and worse here because this one also saves.
        //
        // OpenScene called from a script does NOT prompt: it discards unsaved changes
        // and clears the undo stack. This method then marks dirty and saves, so the
        // discarded state is overwritten on disk as well. Use the map if it is
        // already open, and otherwise ask before replacing whatever is.
        var scene = UnityEngine.SceneManagement.SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[SkySetup] cancelled — the open scene has unsaved changes " +
                                 "and loading the map would discard them.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        RenderSettings.skybox = material;

        // The whole point of an HDRI: ambient light comes from the image, with real
        // colour and direction, instead of three flat constants.
        RenderSettings.ambientMode = AmbientMode.Skybox;
        RenderSettings.ambientIntensity = 1f;
        DynamicGI.UpdateEnvironment();

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[SkySetup] applied {Path.GetFileName(k_Hdri)} to {MapSeeder.ScenePath} " +
                  $"(rotation {k_Rotation}deg, exposure {k_Exposure}). Ambient source is now the skybox.");
    }

    static Material BuildSkyboxMaterial()
    {
        // An .hdr is linear light data, not colour-managed art. Importing it as
        // sRGB crushes the highlights that make image-based lighting work at all,
        // and the sun in the HDRI stops reading as a light source.
        var importer = (TextureImporter)AssetImporter.GetAtPath(k_Hdri);
        if (importer != null)
        {
            bool changed = false;
            if (importer.sRGBTexture) { importer.sRGBTexture = false; changed = true; }
            if (importer.textureShape != TextureImporterShape.Texture2D)
            {
                importer.textureShape = TextureImporterShape.Texture2D;
                changed = true;
            }
            if (importer.mipmapEnabled) { importer.mipmapEnabled = false; changed = true; }
            if (importer.wrapMode != TextureWrapMode.Repeat)
            {
                importer.wrapMode = TextureWrapMode.Repeat;
                changed = true;
            }
            if (changed)
            {
                importer.SaveAndReimport();
                Debug.Log("[SkySetup] re-imported the HDRI as linear (non-sRGB) 2D.");
            }
        }

        var texture = AssetDatabase.LoadAssetAtPath<Texture>(k_Hdri);
        if (texture == null)
        {
            Debug.LogError($"[SkySetup] {k_Hdri} did not import as a texture.");
            return null;
        }

        // Skybox/Panoramic is a built-in Unity shader and works under URP. It takes
        // the equirectangular HDRI directly, so no cubemap conversion step.
        var shader = Shader.Find("Skybox/Panoramic");
        if (shader == null)
        {
            Debug.LogError("[SkySetup] Skybox/Panoramic shader not found.");
            return null;
        }

        // Idempotent: update the existing material in place so its GUID survives
        // and anything already referencing it keeps working.
        var material = AssetDatabase.LoadAssetAtPath<Material>(k_Material);
        if (material == null)
        {
            material = new Material(shader);
            AssetDatabase.CreateAsset(material, k_Material);
        }
        else if (material.shader != shader)
        {
            material.shader = shader;
        }

        material.SetTexture("_MainTex", texture);
        material.SetFloat("_Mapping", 1f);      // 1 = latitude-longitude layout
        material.SetFloat("_ImageType", 0f);    // 0 = full 360
        material.SetFloat("_Rotation", k_Rotation);
        material.SetFloat("_Exposure", k_Exposure);
        material.SetColor("_Tint", Color.gray); // Unity's neutral for this shader

        EditorUtility.SetDirty(material);
        return material;
    }
}
