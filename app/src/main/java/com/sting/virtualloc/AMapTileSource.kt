package com.sting.virtualloc

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import java.util.Locale

/**
 * 高德地图瓦片源（国内可访问）
 */
class AMapTileSource : OnlineTileSourceBase(
    "Amap",
    1, 18, 256, ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile",
        "https://webrd02.is.autonavi.com/appmaptile",
        "https://webrd03.is.autonavi.com/appmaptile"
    )
) {
    override fun getTileURLString(aMapTileIndex: Long): String {
        // osmdroid tile index 编码: zoom=bits[0-7], x=bits[8-31], y=bits[32-63]
        val z = (aMapTileIndex and 0xFF).toInt()
        val x = ((aMapTileIndex shr 8) and 0xFFFF).toInt()
        val y = ((aMapTileIndex shr 32) and 0xFFFF).toInt()
        val base = baseUrls[kotlin.random.Random.nextInt(baseUrls.size)]
        return String.format(
            Locale.CHINA,
            "%s?lang=zh_cn&size=1&scale=1&style=8&x=%d&y=%d&z=%d",
            base, x, y, z
        )
    }
}
