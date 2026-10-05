package edu.sustech.mobile.ui.ws

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.ServicePortalActivity
import edu.sustech.mobile.ws.WsApi
import edu.sustech.mobile.ws.WsProgram

/**
 * Exchange programmes, read natively.
 *
 * The list is the part people actually browse, so it is ours: it loads with the
 * stored account and never shows a login form. Tapping a programme opens its
 * official page for the application itself — a form-heavy write that belongs on
 * the platform's own page, now with the session already established.
 */
class ExchangeFragment : ListFragment<WsProgram>(R.layout.fragment_exchange_programs) {

    override fun rowLayout() = R.layout.item_ws_program

    override fun cachePrefix() = "ws.programs."

    override fun emptyText() = getString(R.string.ws_no_programs)

    override suspend fun fetch(): List<WsProgram> = App.ws.programs().programs

    override fun bindRow(view: View, item: WsProgram, position: Int) {
        view.findViewById<TextView>(R.id.ws_program_name).text = item.name

        val where = listOf(item.region, item.school, item.type)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val meta = view.findViewById<TextView>(R.id.ws_program_where)
        meta.text = where
        meta.visibility = if (where.isBlank()) View.GONE else View.VISIBLE

        val whenLine = listOf(item.status, item.applyRange)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val status = view.findViewById<TextView>(R.id.ws_program_status)
        status.text = whenLine
        status.visibility = if (whenLine.isBlank()) View.GONE else View.VISIBLE
        status.setTextColor(
            requireContext().getColor(if (item.appliable) R.color.brand else R.color.ink_muted),
        )

        view.setOnClickListener {
            startActivity(ServicePortalActivity.intent(requireContext(), WsApi.detailUrl(item)))
        }
    }
}
