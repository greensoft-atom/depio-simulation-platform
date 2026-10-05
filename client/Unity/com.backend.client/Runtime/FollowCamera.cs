using Backend.Client.Core;
using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// An orthographic camera on the own tank (docs 08 §8), showing the arena's view: 1 600 world units high, as the
    /// server's interest is (02 §7). Runs after the client's Update, which built the scene it reads.
    /// </summary>
    [RequireComponent(typeof(Camera))]
    public sealed class FollowCamera : MonoBehaviour
    {
        [SerializeField] private BackendClient client;
        [SerializeField] private WorldView view;
        [Tooltip("World units the screen's height shows")]
        [SerializeField] private float viewUnits = 1600f;

        private Camera _camera;

        private void Awake()
        {
            _camera = GetComponent<Camera>();
            _camera.orthographic = true;
        }

        private void LateUpdate()
        {
            _camera.orthographicSize = viewUnits * view.Scale / 2f;
            if (client.Match == null) return;
            var items = client.Scene.Items;
            for (int i = 0; i < items.Count; i++)
            {
                DrawItem d = items[i];
                if (!d.Self) continue;
                transform.position = new Vector3(d.X * view.Scale, -d.Y * view.Scale, transform.position.z);
                return;
            }
        }
    }
}
