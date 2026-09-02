package dev.thunder.manager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun ThunderDoodle(
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onBackground
    val quietInk = colors.onSurfaceVariant
    val groundColor = colors.surfaceVariant
    val cloudColor = colors.primaryContainer
    val skinColor = colors.primaryContainer
    val shirtColor = colors.primary
    val signalColor = colors.secondary

    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        // A light edge crop lets the character and cloud begin closer to the
        // controls on tall phones without lifting the ground off the bottom.
        val scale = minOf(size.width / 310f, size.height / 220f)
        val left = (size.width - 360f * scale) / 2f
        val top = size.height - 220f * scale
        fun x(value: Float) = left + value * scale
        fun y(value: Float) = top + value * scale
        fun point(horizontal: Float, vertical: Float) = Offset(x(horizontal), y(vertical))
        fun dimensions(width: Float, height: Float) = Size(width * scale, height * scale)

        val line = Stroke(
            width = 2.6f * scale,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val fineLine = Stroke(
            width = 1.8f * scale,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        val ground = Path().apply {
            moveTo(x(0f), y(176f))
            cubicTo(x(44f), y(158f), x(87f), y(182f), x(137f), y(169f))
            cubicTo(x(194f), y(154f), x(232f), y(180f), x(282f), y(168f))
            cubicTo(x(313f), y(161f), x(338f), y(164f), x(360f), y(171f))
            lineTo(x(360f), y(220f))
            lineTo(x(0f), y(220f))
            close()
        }
        drawPath(ground, groundColor)
        val groundLine = Path().apply {
            moveTo(x(0f), y(176f))
            cubicTo(x(44f), y(158f), x(87f), y(182f), x(137f), y(169f))
            cubicTo(x(194f), y(154f), x(232f), y(180f), x(282f), y(168f))
            cubicTo(x(313f), y(161f), x(338f), y(164f), x(360f), y(171f))
        }
        drawPath(groundLine, quietInk, style = fineLine)

        val cloud = Path().apply {
            moveTo(x(224f), y(82f))
            cubicTo(x(214f), y(82f), x(207f), y(75f), x(207f), y(65f))
            cubicTo(x(207f), y(55f), x(215f), y(47f), x(226f), y(46f))
            cubicTo(x(229f), y(34f), x(239f), y(27f), x(251f), y(28f))
            cubicTo(x(258f), y(17f), x(276f), y(16f), x(286f), y(27f))
            cubicTo(x(293f), y(23f), x(303f), y(24f), x(309f), y(30f))
            cubicTo(x(314f), y(35f), x(316f), y(41f), x(315f), y(47f))
            cubicTo(x(327f), y(49f), x(334f), y(56f), x(334f), y(66f))
            cubicTo(x(334f), y(76f), x(326f), y(82f), x(316f), y(82f))
            close()
        }
        drawPath(cloud, cloudColor)
        drawPath(cloud, ink, style = line)

        drawCircle(ink, 1.8f * scale, point(245f, 57f))
        drawCircle(ink, 1.8f * scale, point(258f, 57f))
        val cloudSmile = Path().apply {
            moveTo(x(246f), y(65f))
            cubicTo(x(249f), y(69f), x(255f), y(69f), x(258f), y(65f))
        }
        drawPath(cloudSmile, ink, style = fineLine)

        val bolt = Path().apply {
            moveTo(x(283f), y(53f))
            lineTo(x(269f), y(73f))
            lineTo(x(279f), y(73f))
            lineTo(x(272f), y(95f))
            lineTo(x(297f), y(66f))
            lineTo(x(286f), y(66f))
            close()
        }
        drawPath(bolt, signalColor)
        drawPath(bolt, ink, style = fineLine)

        val phoneTopLeft = point(246f, 94f)
        val phoneSize = dimensions(72f, 100f)
        val phoneRadius = CornerRadius(11f * scale, 11f * scale)
        drawRoundRect(
            color = colors.background,
            topLeft = phoneTopLeft,
            size = phoneSize,
            cornerRadius = phoneRadius,
        )
        drawRoundRect(
            color = ink,
            topLeft = phoneTopLeft,
            size = phoneSize,
            cornerRadius = phoneRadius,
            style = line,
        )
        drawLine(
            color = quietInk,
            start = point(270f, 103f),
            end = point(294f, 103f),
            strokeWidth = 2f * scale,
            cap = StrokeCap.Round,
        )
        drawCircle(quietInk, 2f * scale, point(282f, 184f))

        val screenBolt = Path().apply {
            moveTo(x(284f), y(119f))
            lineTo(x(271f), y(141f))
            lineTo(x(281f), y(141f))
            lineTo(x(276f), y(164f))
            lineTo(x(296f), y(136f))
            lineTo(x(286f), y(136f))
            close()
        }
        drawPath(screenBolt, signalColor)
        drawPath(screenBolt, ink, style = fineLine)

        val cable = Path().apply {
            moveTo(x(170f), y(140f))
            cubicTo(x(197f), y(146f), x(214f), y(125f), x(246f), y(137f))
        }
        drawPath(cable, ink, style = Stroke(6f * scale, cap = StrokeCap.Round))
        drawPath(cable, signalColor, style = Stroke(3f * scale, cap = StrokeCap.Round))
        drawLine(
            color = ink,
            start = point(243f, 132f),
            end = point(249f, 142f),
            strokeWidth = 2.4f * scale,
            cap = StrokeCap.Round,
        )

        drawLine(
            color = ink,
            start = point(98f, 159f),
            end = point(94f, 194f),
            strokeWidth = 12f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = point(129f, 159f),
            end = point(137f, 194f),
            strokeWidth = 12f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = point(93f, 194f),
            end = point(78f, 199f),
            strokeWidth = 7f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = point(137f, 194f),
            end = point(152f, 198f),
            strokeWidth = 7f * scale,
            cap = StrokeCap.Round,
        )

        val body = Path().apply {
            moveTo(x(91f), y(108f))
            cubicTo(x(78f), y(114f), x(75f), y(138f), x(78f), y(163f))
            cubicTo(x(96f), y(169f), x(123f), y(168f), x(142f), y(162f))
            cubicTo(x(142f), y(137f), x(139f), y(115f), x(127f), y(108f))
            close()
        }
        drawPath(body, shirtColor.copy(alpha = 0.82f))
        drawPath(body, ink, style = line)

        drawLine(
            color = ink,
            start = point(88f, 121f),
            end = point(67f, 151f),
            strokeWidth = 13f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = skinColor,
            start = point(88f, 121f),
            end = point(67f, 151f),
            strokeWidth = 9f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = point(133f, 121f),
            end = point(170f, 140f),
            strokeWidth = 13f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = skinColor,
            start = point(133f, 121f),
            end = point(170f, 140f),
            strokeWidth = 9f * scale,
            cap = StrokeCap.Round,
        )
        drawCircle(skinColor, 6f * scale, point(170f, 140f))
        drawCircle(ink, 6f * scale, point(170f, 140f), style = fineLine)

        drawLine(
            color = ink,
            start = point(102f, 103f),
            end = point(102f, 112f),
            strokeWidth = 10f * scale,
            cap = StrokeCap.Round,
        )
        drawCircle(ink, 24f * scale, point(109f, 76f))
        drawCircle(skinColor, 20.8f * scale, point(109f, 76f))

        val hair = Path().apply {
            moveTo(x(88f), y(73f))
            cubicTo(x(89f), y(55f), x(102f), y(48f), x(116f), y(52f))
            cubicTo(x(127f), y(55f), x(132f), y(65f), x(130f), y(77f))
            cubicTo(x(121f), y(76f), x(117f), y(69f), x(113f), y(64f))
            cubicTo(x(107f), y(71f), x(99f), y(74f), x(88f), y(73f))
        }
        drawPath(hair, ink, style = line)
        drawCircle(ink, 1.6f * scale, point(102f, 81f))
        drawCircle(ink, 1.6f * scale, point(116f, 81f))
        val smile = Path().apply {
            moveTo(x(103f), y(90f))
            cubicTo(x(106f), y(94f), x(112f), y(94f), x(116f), y(89f))
        }
        drawPath(smile, ink, style = fineLine)

        drawLine(
            color = quietInk,
            start = point(20f, 199f),
            end = point(52f, 199f),
            strokeWidth = 2f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = quietInk,
            start = point(180f, 191f),
            end = point(207f, 191f),
            strokeWidth = 2f * scale,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = quietInk,
            start = point(326f, 196f),
            end = point(345f, 196f),
            strokeWidth = 2f * scale,
            cap = StrokeCap.Round,
        )
    }
}
