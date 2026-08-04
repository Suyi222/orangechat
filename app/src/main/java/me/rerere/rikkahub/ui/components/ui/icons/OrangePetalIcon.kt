/*
 * 隙光 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 隙光 - 自定义"思考中"图标（替代 HugeIcons.Idea01）
 * 主题：树叶缝隙间落下的微光
 * 由用户提供的 SVG (viewBox 0 0 24 24) 转换而来
 */
public val OrangePetalIcon: ImageVector
    get() {
        if (_orangePetalIcon != null) {
            return _orangePetalIcon!!
        }
        _orangePetalIcon = ImageVector.Builder(
            name = "XiGuangThinking",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 叶子轮廓（描边）
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF282828)),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 2f)
                curveTo(7f, 7f, 4f, 11f, 4f, 15f)
                curveTo(4f, 19f, 7f, 22f, 12f, 22f)
                curveTo(17f, 22f, 20f, 19f, 20f, 15f)
                curveTo(20f, 11f, 17f, 7f, 12f, 2f)
                close()
            }
            // 叶柄
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF282828)),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 21.6f)
                lineTo(12f, 23.4f)
            }
            // 主叶脉（缝隙上方）
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF282828)),
                strokeLineWidth = 1.2f,
                strokeLineCap = StrokeCap.Round,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 4f)
                lineTo(12f, 10f)
            }
            // 主叶脉（缝隙下方）
            path(
                fill = null,
                stroke = SolidColor(Color(0xFF282828)),
                strokeLineWidth = 1.2f,
                strokeLineCap = StrokeCap.Round,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(12f, 14f)
                lineTo(12f, 20f)
            }
            // 缝隙中的光点
            path(
                fill = SolidColor(Color(0xFF282828)),
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(10.8f, 12f)
                arcToRelative(1.2f, 1.2f, 0f, true, true, 2.4f, 0f)
                arcToRelative(1.2f, 1.2f, 0f, true, true, -2.4f, 0f)
                close()
            }
        }.build()
        return _orangePetalIcon!!
    }

private var _orangePetalIcon: ImageVector? = null
