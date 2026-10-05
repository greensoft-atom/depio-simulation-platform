using Backend.Client.Core;
using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// How each thing is drawn (docs 08 §8): a sprite by kind, class, subtype and skin, and a colour by team. Sprites
    /// are drawn one Unity unit across at a scale of 1, so draw them so: 100 pixels at 100 pixels to the unit.
    /// </summary>
    [CreateAssetMenu(menuName = "Backend/Look table", fileName = "LookTable")]
    public sealed class LookTable : ScriptableObject
    {
        [Tooltip("A tank by its class id; the first is Basic, and stands in for any class missing")]
        [SerializeField] private Sprite[] classes = new Sprite[0];
        [Tooltip("A tank by its skin's number, GET /v1/content/skins; none drawn over its class's")]
        [SerializeField] private Sprite[] skins = new Sprite[0];
        [SerializeField] private Sprite bullet;
        [Tooltip("A shape by its subtype")]
        [SerializeField] private Sprite[] shapes = new Sprite[0];
        [Tooltip("A unit by its subtype: 1 trap, 2 drone")]
        [SerializeField] private Sprite[] units = new Sprite[0];
        [Tooltip("By team: 0 none, 1 and 2 a made match's sides")]
        [SerializeField] private Color[] teams = { Color.white, new Color(0.2f, 0.5f, 1f), new Color(1f, 0.3f, 0.3f) };
        [Tooltip("A tank's radius in world units: its class's body, which the class table has (GET /v1/content/classes)")]
        [SerializeField] private float tankRadius = 25f;

        public Sprite SpriteFor(DrawItem d)
        {
            switch (d.Kind)
            {
                case Wire.KindTank:
                    if (d.Skin > 0 && d.Skin < skins.Length && skins[d.Skin] != null) return skins[d.Skin];
                    return Pick(classes, d.ClassId);
                case Wire.KindPredicted:
                    return bullet;
                case Wire.KindStatic:
                    return Pick(shapes, d.Subtype);
                default:
                    return Pick(units, d.Subtype);
            }
        }

        public Color ColourFor(DrawItem d) => d.Team >= 0 && d.Team < teams.Length ? teams[d.Team] : Color.white;

        public float TankRadius(int classId) => tankRadius;

        private static Sprite Pick(Sprite[] sprites, int index)
        {
            if (sprites.Length == 0) return null;
            return index >= 0 && index < sprites.Length && sprites[index] != null ? sprites[index] : sprites[0];
        }
    }
}
