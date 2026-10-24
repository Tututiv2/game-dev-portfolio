using System.Collections.Generic;
using Unity.AI.Navigation;
using UnityEngine;

/// <summary>
/// Phase 5: the boarded window. §17 calls this "the round-one loop", and §1623
/// says plainly that it "doesn't exist" in the old build.
///
/// <para>Zombies tear boards off to get in; the player nails them back on for
/// <b>+10 Essence per board</b>. That is the entire early-round economy — before
/// wallbuys and before the box, repairing barriers is how a player affords
/// anything at all.</para>
///
/// <para><b>Why a NavMeshLink and not a carving obstacle.</b> A
/// <c>Kit_Wall_4_Window</c> opening runs from 1.0 m to 2.6 m off the floor, so it
/// was never a floor-level navmesh route — nothing was ever pathing through it,
/// boarded or not. Carving an obstacle there would block a route that does not
/// exist and change nothing. What actually makes a window an entrance is a
/// <see cref="NavMeshLink"/> spanning the sill, disabled while boards remain and
/// enabled when the last one comes off. That is also why tearing the final board
/// visibly opens a route rather than merely removing scenery.</para>
///
/// <para><b>The link is disabled, not destroyed, and re-disabled on repair</b> —
/// boarding a window back up has to shut the route again or repairing is
/// cosmetic, which is exactly the complaint §1623 makes about the old build.</para>
/// </summary>
public class WindowBarrier : MonoBehaviour, IInteractable
{
    /// <summary>Essence per board nailed back on (§1623).</summary>
    public const int RewardPerBoard = 10;


    [Tooltip("Boards when fully repaired. Six is the genre standard and is what " +
             "makes a full rebuild worth 60 Essence.")]
    public int maxBoards = 6;

    /// <summary>Every barrier in the scene, so zombies can find the nearest one
    /// without a FindObjectsByType sweep per frame.</summary>
    public static readonly List<WindowBarrier> All = new List<WindowBarrier>();

    public int BoardsRemaining { get; private set; }
    public bool IsOpen { get { return BoardsRemaining <= 0; } }

    NavMeshLink link;
    readonly List<GameObject> boardVisuals = new List<GameObject>();

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    static void ResetStatics()
    {
        All.Clear();
    }

    void Awake()
    {
        BoardsRemaining = maxBoards;

        link = GetComponent<NavMeshLink>();
        if (link == null) link = gameObject.AddComponent<NavMeshLink>();

        // Bidirectional: zombies come in, and a link that only worked one way
        // would strand anything that used it.
        link.bidirectional = true;

        CollectBoardVisuals();
        ApplyState();
    }

    void OnEnable() { if (!All.Contains(this)) All.Add(this); }
    void OnDisable() { All.Remove(this); }

    void CollectBoardVisuals()
    {
        boardVisuals.Clear();
        foreach (Transform child in transform)
            if (child.name.StartsWith("Board")) boardVisuals.Add(child.gameObject);
    }

    // ---- zombie side ------------------------------------------------------

    /// <summary>
    /// Rip one board off. Returns true if a board actually came away, so the
    /// caller can time its next swing only when it achieved something.
    /// </summary>
    public bool TearBoard()
    {
        if (IsOpen) return false;

        BoardsRemaining--;
        ApplyState();
        return true;
    }

    /// <summary>Nearest barrier that still has boards, within <paramref name="range"/>.
    /// Null when there is nothing worth attacking.</summary>
    public static WindowBarrier NearestBoarded(Vector3 from, float range)
    {
        WindowBarrier best = null;
        float bestSqr = range * range;

        for (int i = 0; i < All.Count; i++)
        {
            var b = All[i];
            if (b == null || b.IsOpen) continue;

            float sqr = (b.transform.position - from).sqrMagnitude;
            if (sqr <= bestSqr) { bestSqr = sqr; best = b; }
        }
        return best;
    }

    // ---- player side ------------------------------------------------------

    public string GetPrompt()
    {
        if (BoardsRemaining >= maxBoards) return null;
        return "[F] Rebuild Board [+" + RewardPerBoard + "]  " +
               BoardsRemaining + "/" + maxBoards;
    }

    /// <summary>
    /// ONE PRESS = ONE BOARD. Not a hold.
    ///
    /// <para>The first version accumulated <c>Time.deltaTime</c> per call and
    /// required 0.6 s of it, with a prompt reading "Hold [F]". That was wrong
    /// about the thing driving it: <see cref="InteractionSystem"/> gates on
    /// <c>GameInput.InteractPressedThisFrame</c>, which is
    /// <c>WasPressedThisFrame()</c> — one call per discrete press, never once per
    /// frame held. Holding F did nothing after the first frame, and a full rebuild
    /// would have needed roughly 216 separate taps at 60 fps.</para>
    ///
    /// <para>Fixed by making the mechanic match the input that exists rather than
    /// the input the comment imagined. Six presses, +10 each, 60 for a full
    /// window.</para>
    /// </summary>
    public bool Interact(GameObject player)
    {
        if (BoardsRemaining >= maxBoards) return false;

        BoardsRemaining++;
        ScoreManager.Award(RewardPerBoard);
        ApplyState();
        return true;
    }

    // ---- shared -----------------------------------------------------------

    void ApplyState()
    {
        BoardsRemaining = Mathf.Clamp(BoardsRemaining, 0, maxBoards);

        for (int i = 0; i < boardVisuals.Count; i++)
            if (boardVisuals[i] != null)
                boardVisuals[i].SetActive(i < BoardsRemaining);

        // The route opens only when the last board is gone, and closes again the
        // moment one goes back on.
        if (link != null) link.enabled = IsOpen;
    }
}
