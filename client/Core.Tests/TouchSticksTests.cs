using System;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>Two sticks to an input (08 §8, plan item 77 (a)): the stick's y grows upward, as a screen's in Unity.</summary>
    public class TouchSticksTests
    {
        [Test]
        public void AMoveStickIsEightDirectionsPastADeadZone()
        {
            Assert.That(TouchSticks.MoveMask(0.1f, 0.15f), Is.Zero, "inside a fifth of its reach: still");
            Assert.That(TouchSticks.MoveMask(1f, 0f), Is.EqualTo(Wire.MoveRight));
            Assert.That(TouchSticks.MoveMask(0f, 1f), Is.EqualTo(Wire.MoveUp), "up the screen is up the world, y falling");
            Assert.That(TouchSticks.MoveMask(-1f, 0f), Is.EqualTo(Wire.MoveLeft));
            Assert.That(TouchSticks.MoveMask(0f, -1f), Is.EqualTo(Wire.MoveDown));
            Assert.That(TouchSticks.MoveMask(0.7f, 0.7f), Is.EqualTo(Wire.MoveUp | Wire.MoveRight));
            Assert.That(TouchSticks.MoveMask(-0.7f, -0.7f), Is.EqualTo(Wire.MoveDown | Wire.MoveLeft));
            float r = 22f * (float)Math.PI / 180f, s = 23f * (float)Math.PI / 180f;
            Assert.That(TouchSticks.MoveMask((float)Math.Cos(r), (float)Math.Sin(r)), Is.EqualTo(Wire.MoveRight), "22°: right");
            Assert.That(TouchSticks.MoveMask((float)Math.Cos(s), (float)Math.Sin(s)), Is.EqualTo(Wire.MoveUp | Wire.MoveRight), "23°: up and right");
        }

        [Test]
        public void AnAimStickAimsAndFiresPastHalfItsReach()
        {
            Assert.That(TouchSticks.Aim(1f, 0f), Is.EqualTo(Wire.QuantiseAim(0f)));
            Assert.That(TouchSticks.Aim(0f, 1f), Is.EqualTo(Wire.QuantiseAim(-(float)Math.PI / 2)), "up the screen: the world's -y");
            Assert.That(TouchSticks.Fires(0.4f, 0f), Is.False);
            Assert.That(TouchSticks.Fires(0.5f, 0f), Is.True, "half its reach, exactly");
            Assert.That(TouchSticks.Fires(0.3f, 0.45f), Is.True, "0.54 of its reach");
        }

        [Test]
        public void KeysAndAMouseInTheEditor()
        {
            Assert.That(TouchSticks.MoveMask(up: true, down: false, left: true, right: false), Is.EqualTo(Wire.MoveUp | Wire.MoveLeft));
            Assert.That(TouchSticks.MoveMask(up: true, down: true, left: false, right: false), Is.Zero, "opposites cancel, as the server's rule does");
            Assert.That(TouchSticks.AimAt(10f, 10f, 10f, 0f), Is.EqualTo(Wire.QuantiseAim(-(float)Math.PI / 2)),
                "from a world position to another, the world's y");
        }
    }
}
