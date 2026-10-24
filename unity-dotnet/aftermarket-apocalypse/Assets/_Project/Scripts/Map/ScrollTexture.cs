using UnityEngine;

/// <summary>Scrolls _BaseMap — the sea, conveyor belts, smoke sheets (spec §9).</summary>
[RequireComponent(typeof(Renderer))]
public class ScrollTexture : MonoBehaviour
{
    public Vector2 speed = new Vector2(0.006f, 0.014f);

    private Renderer rend;
    private MaterialPropertyBlock block;
    private Vector2 offset;

    void Awake()
    {
        rend = GetComponent<Renderer>();
        block = new MaterialPropertyBlock();
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        offset += speed * Time.deltaTime;
        offset.x %= 1f;
        offset.y %= 1f;

        rend.GetPropertyBlock(block);
        block.SetVector("_BaseMap_ST", new Vector4(1f, 1f, offset.x, offset.y));
        rend.SetPropertyBlock(block);
    }
}
