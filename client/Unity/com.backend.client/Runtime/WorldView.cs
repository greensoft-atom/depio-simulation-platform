using System.Collections.Generic;
using Backend.Client.Core;
using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// Draws the scene (docs 08 §8): a pooled sprite a handle, placed, turned and sized as the core's Scene says. The
    /// world's y grows downward and Unity's upward, so y and angles are turned over here, and nowhere else.
    /// </summary>
    public sealed class WorldView : MonoBehaviour
    {
        [SerializeField] private BackendClient client;
        [SerializeField] private LookTable looks;
        [Tooltip("What each thing is drawn with: a SpriteRenderer, cloned for each, a child of this")]
        [SerializeField] private SpriteRenderer spritePrefab;
        [Tooltip("Unity units to a world unit: 0.01 puts the arena's 1 600-unit view 16 units across")]
        [SerializeField] private float scale = 0.01f;

        public float Scale => scale;

        private readonly Dictionary<int, SpriteRenderer> _drawn = new Dictionary<int, SpriteRenderer>();
        private readonly Stack<SpriteRenderer> _spare = new Stack<SpriteRenderer>();
        private readonly HashSet<int> _seen = new HashSet<int>();
        private readonly List<int> _gone = new List<int>();

        private void LateUpdate()
        {
            _seen.Clear();
            if (client.Match != null)
            {
                IReadOnlyList<DrawItem> items = client.Scene.Items;
                for (int i = 0; i < items.Count; i++)
                {
                    DrawItem d = items[i];
                    _seen.Add(d.Handle);
                    if (!_drawn.TryGetValue(d.Handle, out SpriteRenderer r))
                    {
                        r = Take();
                        _drawn[d.Handle] = r;
                    }
                    r.sprite = looks.SpriteFor(d);
                    r.color = looks.ColourFor(d);
                    Transform t = r.transform;
                    t.localPosition = new Vector3(d.X * scale, -d.Y * scale, 0f);
                    t.localRotation = Quaternion.Euler(0f, 0f, -d.Angle * Mathf.Rad2Deg);
                    float across = (d.Kind == Wire.KindTank ? looks.TankRadius(d.ClassId) : d.Radius) * 2f * scale;
                    t.localScale = new Vector3(across, across, 1f);
                }
            }
            _gone.Clear();
            foreach (int handle in _drawn.Keys)
                if (!_seen.Contains(handle)) _gone.Add(handle);
            foreach (int handle in _gone)
            {
                SpriteRenderer r = _drawn[handle];
                _drawn.Remove(handle);
                r.enabled = false;                  // hidden, kept for the next
                _spare.Push(r);
            }
        }

        private SpriteRenderer Take()
        {
            if (_spare.Count > 0)
            {
                SpriteRenderer r = _spare.Pop();
                r.enabled = true;
                return r;
            }
            return Instantiate(spritePrefab, transform);
        }
    }
}
