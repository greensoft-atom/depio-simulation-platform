using System.Collections.Generic;
using Backend.Client.Core;
using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// The least to play by (docs 08 §8): level and experience, the kill feed, and, once the own tank is dead, a
    /// respawn; leaving at any time. The events are the match's own (MatchConnection.OnEvent), copied, as the core
    /// reuses each for the next frame.
    /// </summary>
    public sealed class Hud : MonoBehaviour
    {
        [SerializeField] private BackendClient client;
        [Tooltip("Kills shown")]
        [SerializeField] private int feedLength = 5;

        private readonly Queue<string> _feed = new Queue<string>();
        private MatchConnection _heard;
        private long _level, _xp, _xpNext;

        private void Update()
        {
            MatchConnection match = client.Match;
            if (match == null || ReferenceEquals(match, _heard)) return;
            _heard = match;
            _feed.Clear();
            match.OnEvent = Heard;
        }

        private void Heard(MatchEvent e)
        {
            if (e.Type == Wire.EvtStats)
            {
                _level = e.Level;
                _xp = e.Xp;
                _xpNext = e.XpForNextLevel;
            }
            else if (e.Type == Wire.EvtKill)
            {
                _feed.Enqueue((e.Killer.Length == 0 ? "?" : e.Killer) + " > " + (e.Victim.Length == 0 ? "?" : e.Victim));
                while (_feed.Count > feedLength) _feed.Dequeue();
            }
        }

        private void OnGUI()
        {
            MatchConnection match = client.Match;
            if (match == null) return;
            float scale = Screen.height / 720f;
            GUI.matrix = Matrix4x4.Scale(new Vector3(scale, scale, 1f));
            GUILayout.BeginArea(new Rect(20f, 20f, 400f, 300f));
            GUILayout.Label("Level " + _level + "   " + _xp + " / " + _xpNext);
            foreach (string kill in _feed) GUILayout.Label(kill);
            if (match.State == MatchState.InMatch && !match.Alive && GUILayout.Button("Respawn")) match.Respawn();
            if (GUILayout.Button("Leave")) match.Leave();
            GUILayout.EndArea();
        }
    }
}
