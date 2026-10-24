using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Builds the player's weapon view models and fills
/// <see cref="WeaponInventory.weapons"/>. This is what unblocks Phase 6 —
/// wallbuys and the Mystery Box both refuse to charge while that list is empty.
///
/// <para><b>These are scene objects, not prefabs, and that is what the inventory
/// expects.</b> <c>WeaponInventory.Equip</c> switches guns with
/// <c>weapons[i].SetActive(i == slot)</c>, so every weapon lives under the camera
/// holder all match and is toggled. Instantiating a prefab per purchase would
/// need a different inventory.</para>
///
/// <para><b>Every stat comes from the <see cref="WeaponDefinition"/> asset via
/// <c>ApplyTo</c>.</b> Nothing is typed into the component. That is the cure for
/// the Part I "READ FIRST" defect this project keeps designing out: a serialized
/// field on a scene object always beats the script default, so the scene ends up
/// running numbers nobody wrote down. Re-run this tool after editing an asset and
/// the rig matches it again.</para>
///
/// <para><b>It never destroys a view model, and that is a correction.</b> This
/// used to open with <c>DestroyImmediate</c> on the whole WeaponHolder subtree —
/// not <c>Undo.DestroyObjectImmediate</c>, so not undoable — and then
/// <c>SaveScene</c> in the same call, writing the loss to disk before you could
/// react. It called that "idempotent". Idempotent is not the same as
/// non-destructive: swap the placeholder cube for a real gun mesh, tune its
/// offset, then bump <c>magazineSize</c> in the asset and re-run exactly as this
/// project's own docs instruct, and the mesh, materials, transform tuning and any
/// added components were gone and already saved. Every other builder here refuses
/// to re-run precisely to protect hand work; this one did the opposite.</para>
///
/// <para>Now it MATCHES existing view models by name and reuses them, re-pushing
/// only the stats that come from the asset. Missing ones are created; extra ones
/// are reported and left alone. So the advertised "re-run after editing an asset"
/// does exactly what it says and nothing else.</para>
/// </summary>
public static class WeaponRigBuilder
{
    const string k_Holder = "WeaponHolder";
    const string k_ContentFolder = "Assets/_Project/Content/Weapons";

    [MenuItem("Tools/Aftermarket/Build Weapon Rig")]
    public static void Build()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[WeaponRig] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[WeaponRig] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        var definitions = LoadDefinitions();
        if (definitions.Count == 0)
        {
            Debug.LogError($"[WeaponRig] no WeaponDefinition assets in {k_ContentFolder}. " +
                           "Run Tools > Aftermarket > Rebuild Phase 2 Content first.");
            return;
        }

        GameObject player = null;
        foreach (var go in scene.GetRootGameObjects())
            if (go.name == "Player") { player = go; break; }
        if (player == null)
        {
            Debug.LogError("[WeaponRig] no 'Player' root in the map.");
            return;
        }

        var inventory = player.GetComponentInChildren<WeaponInventory>(true);
        if (inventory == null) inventory = player.AddComponent<WeaponInventory>();

        // View models hang off the camera holder so they move with the look, not
        // with the body. MapSeeder names it CameraHolder.
        Transform mount = FindDeep(player.transform, "CameraHolder") ?? player.transform;

        // Reuse the holder if it is there. Slot correctness comes from rebuilding
        // the LIST below in definition order, which costs nothing -- it never
        // required destroying the objects.
        var holder = FindDeep(mount, k_Holder);
        if (holder == null)
        {
            holder = new GameObject(k_Holder).transform;
            holder.SetParent(mount, false);
            holder.localPosition = new Vector3(0.18f, -0.16f, 0.34f);
        }

        int enemyLayer = LayerMask.NameToLayer("Enemy");
        int weaponLayer = LayerMask.NameToLayer("Weapon");

        var wanted = new HashSet<string>();
        int reused = 0, created = 0;

        inventory.weapons.Clear();
        for (int i = 0; i < definitions.Count; i++)
        {
            string vmName = "VM_" + definitions[i].weaponName;
            wanted.Add(vmName);

            var vm = FindChild(holder, vmName);
            if (vm == null)
            {
                vm = CreateViewModel(vmName, holder, weaponLayer, definitions[i]);
                created++;
            }
            else
            {
                reused++;
            }

            ApplyDefinition(vm, definitions[i], enemyLayer);
            inventory.weapons.Add(vm.gameObject);
        }

        // Anything left over is reported, NOT deleted -- it may be hand-built work
        // whose asset was renamed, and this tool has no business deciding that.
        var stale = new List<string>();
        foreach (Transform child in holder)
            if (!wanted.Contains(child.name)) stale.Add(child.name);
        if (stale.Count > 0)
        {
            Debug.LogWarning($"[WeaponRig] {stale.Count} view model(s) under {k_Holder} have no " +
                             $"matching WeaponDefinition and are NOT in any slot: " +
                             string.Join(", ", stale) + ". They were left in place -- delete " +
                             "them by hand if they are genuinely stale.");
        }

        // The player camera's near plane must clear the view models, and it did not.
        //
        // MapSeeder sets farClipPlane and never touches nearClipPlane, so the player
        // camera sat on Unity's DEFAULT 0.3 m -- the only camera in this project
        // still on it, and the only one with a gun in front of it. Measured from the
        // live rig: the Glock and the M4 were both cut 0.170 m into their length and
        // the Sakura Fang 0.060 m, so every weapon in the game was rendering with
        // its back end sliced off, in every build, since Phase 6.
        //
        // Nothing reports this. Near-plane clipping is not an error, and the weapon
        // still looks like a weapon -- it just quietly loses whatever is nearest the
        // eye, which on a view model is the part the player is meant to feel.
        //
        // Derived, not typed: take the closest point on any view model and leave a
        // 40% margin, clamped so a freak model cannot push the near plane somewhere
        // that wrecks depth precision against the 200 m far plane.
        var playerCam = player.GetComponentInChildren<Camera>(true);
        if (playerCam != null)
        {
            float nearest = float.MaxValue;
            foreach (var r in holder.GetComponentsInChildren<Renderer>(true))
                foreach (var corner in Corners(r.bounds))
                    nearest = Mathf.Min(nearest, Vector3.Dot(
                        corner - playerCam.transform.position, playerCam.transform.forward));

            if (nearest < float.MaxValue)
            {
                float nearPlane = Mathf.Clamp(nearest * 0.6f, 0.02f, 0.3f);
                if (nearPlane < playerCam.nearClipPlane - 0.0001f)
                {
                    Debug.Log($"[WeaponRig] player camera near plane {playerCam.nearClipPlane:F3} m " +
                              $"was clipping a view model at {nearest:F3} m. Lowered to {nearPlane:F3} m.");
                    playerCam.nearClipPlane = nearPlane;
                }
            }
        }

        // Slot 0 is the starting pistol. Everything else has to be bought, which
        // is the whole point of Phase 6's economy.
        inventory.startingWeapons = new List<int> { 0 };

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        var names = new List<string>();
        for (int i = 0; i < definitions.Count; i++) names.Add(i + "=" + definitions[i].displayName);

        Debug.Log($"[WeaponRig] {created} view model(s) created, {reused} reused unchanged, " +
                  $"under Player/CameraHolder/{k_Holder}. WeaponInventory.weapons: " +
                  string.Join(", ", names) + ". Slot 0 is the starting weapon; the rest are " +
                  "bought from wallbuys or the Mystery Box. Nothing was deleted -- hand edits " +
                  "to an existing view model's mesh, materials or transform survive a re-run. " +
                  "NOTE: WeaponInventory lives on the Player and the holder under CameraHolder, " +
                  "both OUTSIDE every phase root, so deleting the Phase6 root does not undo them.");
    }

    /// <summary>
    /// Sorted by asset name so slot indices are STABLE across runs. Phase6Builder
    /// writes weapon slots into each wallbuy, and an unordered scan would
    /// reshuffle which gun a chalk outline sells every time either tool ran.
    /// </summary>
    public static List<WeaponDefinition> LoadDefinitions()
    {
        var list = new List<WeaponDefinition>();
        foreach (var guid in AssetDatabase.FindAssets("t:WeaponDefinition", new[] { k_ContentFolder }))
        {
            var def = AssetDatabase.LoadAssetAtPath<WeaponDefinition>(AssetDatabase.GUIDToAssetPath(guid));
            if (def != null) list.Add(def);
        }
        list.Sort((a, b) => string.CompareOrdinal(a.name, b.name));
        return list;
    }

    static IEnumerable<Vector3> Corners(Bounds b)
    {
        for (int i = 0; i < 8; i++)
            yield return new Vector3((i & 1) == 0 ? b.min.x : b.max.x,
                                     (i & 2) == 0 ? b.min.y : b.max.y,
                                     (i & 4) == 0 ? b.min.z : b.max.z);
    }

    static void SetLayerDeep(GameObject go, int layer)
    {
        if (layer < 0) return;
        foreach (var t in go.GetComponentsInChildren<Transform>(true))
            t.gameObject.layer = layer;
    }

    /// <summary>Creates a NEW view model. Only ever called when none exists.</summary>
    static Transform CreateViewModel(string vmName, Transform holder, int weaponLayer,
                                     WeaponDefinition definition)
    {
        // A definition that names a view model prefab GETS that prefab.
        //
        // Until this branch existed, WeaponDefinition.viewModelPrefab was read by
        // nothing anywhere in the project — a serialized inspector field, on every
        // weapon asset, that turned nothing. That is this project's signature bug,
        // the same class as interactKey/pauseKey (deleted for it) and the zombie's
        // whatIsEnemy pointing at a layer no zombie was on. Assign real gun art in
        // the asset and the rig would still have built a grey box, with the
        // inspector showing your prefab sitting there looking connected.
        if (definition != null && definition.viewModelPrefab != null)
        {
            var fromPrefab = (GameObject)PrefabUtility.InstantiatePrefab(
                definition.viewModelPrefab, holder);
            fromPrefab.name = vmName;
            fromPrefab.transform.localPosition = Vector3.zero;
            fromPrefab.transform.localRotation = Quaternion.identity;
            SetLayerDeep(fromPrefab, weaponLayer);

            // Belt and braces. The prefab builder strips colliders, but a prefab
            // edited by hand afterwards can gain one back, and the symptom — the
            // interaction raycast reporting your own gun instead of the door in
            // front of you — reads as broken doors, not as a broken weapon.
            foreach (var col in fromPrefab.GetComponentsInChildren<Collider>(true))
                Object.DestroyImmediate(col);

            return fromPrefab.transform;
        }

        var go = new GameObject(vmName);
        go.transform.SetParent(holder, false);
        if (weaponLayer >= 0) go.layer = weaponLayer;

        // Placeholder body, for a definition that names no prefab. There is no gun
        // art for most weapons in this project yet, so this is a block you can see
        // in frame rather than an invisible weapon that looks like nothing
        // equipped. Replace the mesh, keep the components — and re-running this
        // tool will not take that work away again.
        var body = GameObject.CreatePrimitive(PrimitiveType.Cube);
        body.name = "Placeholder";
        body.transform.SetParent(go.transform, false);
        body.transform.localScale = new Vector3(0.06f, 0.09f, 0.42f);
        if (weaponLayer >= 0) body.layer = weaponLayer;

        // No collider on a view model. It sits inside the camera's near plane and
        // would otherwise block the player's own interaction raycast — you would
        // walk up to a door and be told about your gun.
        Object.DestroyImmediate(body.GetComponent<BoxCollider>());

        var material = AssetDatabase.LoadAssetAtPath<Material>(
            "Assets/_Project/Art/Materials/Diamond Plate Metal 1/Diamond Plate Metal 1.mat");
        if (material != null) body.GetComponent<MeshRenderer>().sharedMaterial = material;

        var muzzle = new GameObject("Muzzle").transform;
        muzzle.SetParent(go.transform, false);
        muzzle.localPosition = new Vector3(0f, 0f, 0.24f);

        return go.transform;
    }

    /// <summary>
    /// Pushes asset values onto an existing view model. This is the ONLY thing a
    /// re-run does to a view model that already exists — mesh, materials, local
    /// transform and any hand-added components are not touched.
    /// </summary>
    static void ApplyDefinition(Transform vm, WeaponDefinition definition, int enemyLayer)
    {
        var gun = vm.GetComponent<GunSystem>();
        if (gun == null) gun = vm.gameObject.AddComponent<GunSystem>();

        if (gun.muzzlePoint == null)
        {
            var muzzle = FindChild(vm, "Muzzle");
            if (muzzle == null)
            {
                muzzle = new GameObject("Muzzle").transform;
                muzzle.SetParent(vm, false);
                muzzle.localPosition = new Vector3(0f, 0f, 0.24f);
            }
            gun.muzzlePoint = muzzle;
        }

        // §13.1 defect #25: the old whatIsEnemy mask was Default|Enemy, so a shot
        // that hit a wall on the Default layer registered as a hit on an enemy.
        // Enemy alone for damage, everything-but-enemy for the world.
        gun.whatIsEnemy = enemyLayer >= 0 ? (1 << enemyLayer) : 0;
        gun.whatIsWorld = enemyLayer >= 0 ? ~(1 << enemyLayer) : ~0;

        // The asset is the source of truth; the component is a view of it.
        definition.ApplyTo(gun);
        gun.reserveAmmo = definition.maxReserve;
        gun.currentAmmo = definition.magazineSize;
    }

    static Transform FindChild(Transform parent, string name)
    {
        foreach (Transform child in parent)
            if (child.name == name) return child;
        return null;
    }

    static Transform FindDeep(Transform root, string name)
    {
        if (root.name == name) return root;
        foreach (Transform child in root)
        {
            var found = FindDeep(child, name);
            if (found != null) return found;
        }
        return null;
    }
}
