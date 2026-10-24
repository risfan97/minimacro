package com.risfan.minimacro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Step(
    var type: String, // CLICK, WAIT, FIND
    var x: Int = 0, var y: Int = 0,
    var ms: Long = 1000,
    var tpl: String = "",
    var th: Double = 0.85,
    var timeoutMs: Long = 10000,
    var click: Boolean = true,
    var rx: Int = 0, var ry: Int = 0, var rw: Int = 0, var rh: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type); put("x", x); put("y", y); put("ms", ms); put("tpl", tpl)
        put("th", th); put("timeoutMs", timeoutMs); put("click", click)
        put("rx", rx); put("ry", ry); put("rw", rw); put("rh", rh)
    }

    fun label(): String = when (type) {
        "CLICK" -> "KLIK ($x, $y)"
        "WAIT" -> "TUNGGU $ms ms"
        else -> "CARI GAMBAR ${(th * 100).toInt()}%" +
                (if (click) " + klik" else "") +
                (if (rw > 0) " [area]" else "") +
                " (maks ${timeoutMs / 1000}s)"
    }

    companion object {
        fun from(o: JSONObject) = Step(
            o.getString("type"), o.optInt("x"), o.optInt("y"), o.optLong("ms", 1000),
            o.optString("tpl"), o.optDouble("th", 0.85), o.optLong("timeoutMs", 10000),
            o.optBoolean("click", true), o.optInt("rx"), o.optInt("ry"), o.optInt("rw"), o.optInt("rh")
        )
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("macro", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Step> {
        val out = mutableListOf<Step>()
        try {
            val a = JSONArray(p(c).getString("steps", "[]"))
            for (i in 0 until a.length()) out.add(Step.from(a.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return out
    }

    fun save(c: Context, l: List<Step>) {
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        p(c).edit().putString("steps", a.toString()).apply()
    }

    fun loops(c: Context): Int = p(c).getInt("loops", 1)
    fun setLoops(c: Context, n: Int) = p(c).edit().putInt("loops", n).apply()
}
