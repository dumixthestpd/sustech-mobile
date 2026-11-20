package edu.sustech.mobile.ws

import java.text.Normalizer
import java.util.Locale

/** Same complete-catalog filter contract as Survival's ws.catalog. */
object WsSearch {
    private fun folded(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

    fun filter(rows: List<WsProgram>, query: String = "", year: String = "", region: String = "",
               type: String = "", appliableOnly: Boolean = false): List<WsProgram> {
        val words = folded(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        return rows.filter { row ->
            (year.isEmpty() || row.year == year) && (region.isEmpty() || row.region == region) &&
                (type.isEmpty() || row.type == type) && (!appliableOnly || row.appliable) &&
                words.all { word -> folded(listOf(row.name, row.englishName, row.school, row.agency, row.region, row.type).joinToString(" ")).contains(word) }
        }
    }
}
