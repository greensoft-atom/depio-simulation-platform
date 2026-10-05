// Stubs of the Unity API the package's scripts use, and nothing else, written to Unity's own signatures so the
// scripts compile on a machine without Unity (docs 08 §8, D-73). Never shipped: Unity supplies the real ones.
// Bodies throw: nothing here is meant to run. What the naming rule excludes (docs/README.md) is left out, the
// scripts doing without it.
using System;

namespace UnityEngine
{
    public class Object
    {
        public string name { get => throw Stub(); set => throw Stub(); }
        public static void DontDestroyOnLoad(Object target) => throw Stub();
        public static T Instantiate<T>(T original, Transform parent) where T : Object => throw Stub();
        internal static Exception Stub() => new NotSupportedException("a stub of Unity's API, for compiling only");
    }

    public class Component : Object
    {
        public Transform transform => throw Stub();
        public T GetComponent<T>() => throw Stub();
    }

    public class Behaviour : Component
    {
        public bool enabled { get => throw Stub(); set => throw Stub(); }
    }

    public class MonoBehaviour : Behaviour { }

    public class ScriptableObject : Object { }

    public class Transform : Component
    {
        public Vector3 position { get => throw Stub(); set => throw Stub(); }
        public Vector3 localPosition { get => throw Stub(); set => throw Stub(); }
        public Quaternion localRotation { get => throw Stub(); set => throw Stub(); }
        public Vector3 localScale { get => throw Stub(); set => throw Stub(); }
        public void SetParent(Transform parent, bool worldPositionStays) => throw Stub();
    }

    public class Renderer : Component
    {
        public bool enabled { get => throw Stub(); set => throw Stub(); }
    }

    public sealed class SpriteRenderer : Renderer
    {
        public Sprite sprite { get => throw Stub(); set => throw Stub(); }
        public Color color { get => throw Stub(); set => throw Stub(); }
    }

    public sealed class Sprite : Object { }

    public sealed class Camera : Behaviour
    {
        public bool orthographic { get => throw Stub(); set => throw Stub(); }
        public float orthographicSize { get => throw Stub(); set => throw Stub(); }
    }

    public struct Vector2
    {
        public float x, y;
        public Vector2(float x, float y) { this.x = x; this.y = y; }
        public float sqrMagnitude => x * x + y * y;
        public static Vector2 operator -(Vector2 a, Vector2 b) => new Vector2(a.x - b.x, a.y - b.y);
        public static Vector2 operator /(Vector2 a, float d) => new Vector2(a.x / d, a.y / d);
        public static implicit operator Vector2(Vector3 v) => new Vector2(v.x, v.y);
    }

    public struct Vector3
    {
        public float x, y, z;
        public Vector3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    public struct Quaternion
    {
        public static Quaternion Euler(float x, float y, float z) => throw Object.Stub();
    }

    public struct Color
    {
        public float r, g, b, a;
        public Color(float r, float g, float b) { this.r = r; this.g = g; this.b = b; a = 1f; }
        public Color(float r, float g, float b, float a) { this.r = r; this.g = g; this.b = b; this.a = a; }
        public static Color white => new Color(1f, 1f, 1f, 1f);
    }

    public struct Rect
    {
        public Rect(float x, float y, float width, float height) => throw Object.Stub();
    }

    public struct Matrix4x4
    {
        public static Matrix4x4 Scale(Vector3 vector) => throw Object.Stub();
    }

    public static class Mathf
    {
        public const float Rad2Deg = 57.29578f;
        public static float Clamp(float value, float min, float max) => throw Object.Stub();
    }

    public enum TouchPhase { Began, Moved, Stationary, Ended, Canceled }

    public struct Touch
    {
        public int fingerId => throw Object.Stub();
        public Vector2 position => throw Object.Stub();
        public TouchPhase phase => throw Object.Stub();
    }

    public enum KeyCode { A = 97, D = 100, S = 115, W = 119 }

    public static class Input
    {
        public static int touchCount => throw Object.Stub();
        public static Touch GetTouch(int index) => throw Object.Stub();
        public static bool GetKey(KeyCode key) => throw Object.Stub();
        public static Vector3 mousePosition => throw Object.Stub();
        public static bool GetMouseButton(int button) => throw Object.Stub();
    }

    public static class Screen
    {
        public static int width => throw Object.Stub();
        public static int height => throw Object.Stub();
    }

    public static class PlayerPrefs
    {
        public static bool HasKey(string key) => throw Object.Stub();
        public static string GetString(string key) => throw Object.Stub();
        public static void SetString(string key, string value) => throw Object.Stub();
        public static void DeleteKey(string key) => throw Object.Stub();
        public static void Save() => throw Object.Stub();
    }

    public sealed class GUILayoutOption { }

    public static class GUI
    {
        public static Matrix4x4 matrix { get => throw Object.Stub(); set => throw Object.Stub(); }
    }

    public static class GUILayout
    {
        public static void BeginArea(Rect screenRect) => throw Object.Stub();
        public static void EndArea() => throw Object.Stub();
        public static void Label(string text, params GUILayoutOption[] options) => throw Object.Stub();
        public static bool Button(string text, params GUILayoutOption[] options) => throw Object.Stub();
    }

    [AttributeUsage(AttributeTargets.Field)]
    public sealed class SerializeField : Attribute { }

    [AttributeUsage(AttributeTargets.Field)]
    public sealed class TooltipAttribute : Attribute
    {
        public TooltipAttribute(string tooltip) { }
    }

    [AttributeUsage(AttributeTargets.Class)]
    public sealed class CreateAssetMenuAttribute : Attribute
    {
        public string menuName { get; set; }
        public string fileName { get; set; }
    }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = true)]
    public sealed class RequireComponent : Attribute
    {
        public RequireComponent(Type requiredComponent) { }
    }

    public class AndroidJavaObject : IDisposable
    {
        public AndroidJavaObject(string className, params object[] args) => throw Object.Stub();
        public T Call<T>(string methodName, params object[] args) => throw Object.Stub();
        public void Call(string methodName, params object[] args) => throw Object.Stub();
        public T GetStatic<T>(string fieldName) => throw Object.Stub();
        public void Dispose() => throw Object.Stub();
    }

    public class AndroidJavaClass : AndroidJavaObject
    {
        public AndroidJavaClass(string className) : base(className) { }
    }
}
