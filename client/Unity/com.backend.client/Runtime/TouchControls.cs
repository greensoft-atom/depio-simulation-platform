using Backend.Client.Core;
using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// Two sticks where the thumbs land (docs 08 §8): the left half of the screen moves, the right aims and fires;
    /// keys (W A S D) and the mouse in the editor and a desktop build. What a stick means is the core's (TouchSticks); this reads Unity's
    /// touches and sends the input every frame, as the core asks.
    /// </summary>
    public sealed class TouchControls : MonoBehaviour
    {
        [SerializeField] private BackendClient client;
        [Tooltip("A stick's reach, as a fraction of the screen's height")]
        [SerializeField] private float reach = 0.12f;

        private int _moveFinger = -1, _aimFinger = -1;
        private Vector2 _moveFrom, _aimFrom;
        private int _aim;

        private void Update()
        {
            MatchConnection match = client.Match;
            if (match == null) return;
            int move = 0;
            bool fire = false;
            float stick = Screen.height * reach;
            if (Input.touchCount > 0)
            {
                for (int i = 0; i < Input.touchCount; i++)
                {
                    Touch touch = Input.GetTouch(i);
                    if (touch.phase == TouchPhase.Began)
                    {
                        bool left = touch.position.x < Screen.width / 2f;
                        if (left && _moveFinger < 0)
                        {
                            _moveFinger = touch.fingerId;
                            _moveFrom = touch.position;
                        }
                        else if (!left && _aimFinger < 0)
                        {
                            _aimFinger = touch.fingerId;
                            _aimFrom = touch.position;
                        }
                    }
                    bool lifted = touch.phase == TouchPhase.Ended || touch.phase == TouchPhase.Canceled;
                    if (touch.fingerId == _moveFinger)
                    {
                        Vector2 off = (touch.position - _moveFrom) / stick;
                        move = lifted ? 0 : TouchSticks.MoveMask(Mathf.Clamp(off.x, -1f, 1f), Mathf.Clamp(off.y, -1f, 1f));
                        if (lifted) _moveFinger = -1;
                    }
                    else if (touch.fingerId == _aimFinger)
                    {
                        Vector2 off = (touch.position - _aimFrom) / stick;
                        if (off.sqrMagnitude > 0.01f) _aim = TouchSticks.Aim(off.x, off.y);
                        fire = !lifted && TouchSticks.Fires(off.x, off.y);
                        if (lifted) _aimFinger = -1;
                    }
                }
            }
#if UNITY_EDITOR || !(UNITY_IOS || UNITY_ANDROID)
            else
            {
                // The editor and a desktop build: the own tank is at the screen's centre (FollowCamera). Not on a
                // phone: Unity makes a mouse of the touches, at the last finger's place, and read as one it turned the
                // aim there the moment both thumbs lifted (client review, 2026-10-04).
                move = TouchSticks.MoveMask(Input.GetKey(KeyCode.W), Input.GetKey(KeyCode.S), Input.GetKey(KeyCode.A),
                    Input.GetKey(KeyCode.D));
                Vector2 mouse = Input.mousePosition;
                Vector2 off = mouse - new Vector2(Screen.width / 2f, Screen.height / 2f);
                if (off.sqrMagnitude > 1f) _aim = TouchSticks.Aim(off.x, off.y);
                fire = Input.GetMouseButton(0);
            }
#endif
            match.SetInput(move, _aim, fire, 0);
        }
    }
}
