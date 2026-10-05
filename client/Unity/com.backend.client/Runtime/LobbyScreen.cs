using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// The least screen to play from (docs 08 §8): sign in as the device's guest, then a seat in the public arena or a
    /// duel's queue, its state, and a match found to accept. IMGUI, so it needs no prefab and no scene set up; the
    /// app's real screens are its designers' to make, on the same calls.
    /// </summary>
    public sealed class LobbyScreen : MonoBehaviour
    {
        [SerializeField] private BackendClient client;

        private void OnGUI()
        {
            if (client.Match != null) return;
            float scale = Screen.height / 720f;
            GUI.matrix = Matrix4x4.Scale(new Vector3(scale, scale, 1f));
            GUILayout.BeginArea(new Rect(20f, 20f, 360f, 680f));
            if (client.Problem != null) GUILayout.Label(client.Problem);
            if (client.LastEnd != null) GUILayout.Label("The match ended: " + client.LastEnd);
            if (client.Session == null)
            {
                if (client.SigningIn) GUILayout.Label("Signing in...");
                else if (GUILayout.Button("Play")) client.SignIn();
            }
            else if (client.Lobby == null || client.Lobby.State != Backend.Client.Core.LobbyState.Ready)
            {
                GUILayout.Label("Lobby: " + (client.Lobby == null ? "none" : client.Lobby.State.ToString()));
            }
            else
            {
                var lobby = client.Lobby;
                if (lobby.Ready != null)
                {
                    GUILayout.Label("A match is found");
                    if (GUILayout.Button("Accept")) lobby.AcceptMatch();
                    if (GUILayout.Button("Decline")) lobby.DeclineMatch();
                }
                else if (lobby.QueueState == "none")
                {
                    if (GUILayout.Button("Play now")) client.PlayNow();
                    if (GUILayout.Button("A duel")) client.Queue("duel");
                }
                else
                {
                    GUILayout.Label("Queued: " + lobby.QueueState);
                    if (GUILayout.Button("Leave the queue")) lobby.LeaveQueue();
                }
                if (lobby.GrantRefusal != null) GUILayout.Label(lobby.GrantRefusal);
                if (lobby.QueueRefusal != null) GUILayout.Label(lobby.QueueRefusal);
            }
            GUILayout.EndArea();
        }
    }
}
