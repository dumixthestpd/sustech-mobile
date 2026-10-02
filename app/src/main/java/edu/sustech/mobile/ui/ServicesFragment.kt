package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.pms.PmsApi
import edu.sustech.mobile.service.ServiceModule
import edu.sustech.mobile.service.Services

/**
 * The whole SUSTech service catalog.
 *
 * Implemented services open; the rest remain visible as roadmap items. Printing
 * gets a live server check because its page can exist while PMS is offline.
 */
class ServicesFragment : Fragment(R.layout.fragment_services), Refreshable {

    private lateinit var adapter: SimpleAdapter<ServiceModule>
    private var printingReachable: Boolean? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = SimpleAdapter(R.layout.item_service) { row, module, _ -> bind(row, module) }

        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ServicesFragment.adapter
        }
        adapter.submit(Services.all)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) checkPrintingReachability()
    }

    private fun bind(row: View, module: ServiceModule) {
        row.findViewById<ImageView>(R.id.service_icon).setImageResource(module.icon)
        row.findViewById<TextView>(R.id.service_title).text = getString(module.title)
        row.findViewById<TextView>(R.id.service_summary).text = getString(module.summary)
        row.findViewById<TextView>(R.id.service_status).apply {
            val status = if (module.id == Services.printing.id) {
                when (printingReachable) {
                    true -> R.string.services_reachable
                    false -> R.string.services_unavailable
                    null -> R.string.services_checking
                }
            } else if (module.available) {
                R.string.services_available
            } else {
                R.string.services_not_implemented
            }
            text = getString(status)
            val color = when {
                module.id == Services.printing.id && printingReachable == true -> R.color.ok
                module.id == Services.printing.id -> R.color.ink_muted
                module.available -> R.color.ok
                else -> R.color.ink_muted
            }
            setTextColor(requireContext().getColor(color))
        }
        row.setOnClickListener {
            if (module.available) {
                startActivity(ServiceActivity.intent(requireContext(), module))
            } else {
                App.toast(getString(R.string.not_implemented_body, getString(module.title)))
            }
        }
    }

    private fun checkPrintingReachability() {
        printingReachable = null
        adapter.submit(Services.all)
        viewLifecycleOwner.runIo(
            block = { App.api.reachability() == PmsApi.Reachability.AVAILABLE },
            onOk = { reachable ->
                if (!isAdded) return@runIo
                printingReachable = reachable
                adapter.submit(Services.all)
            },
            onErr = {
                if (!isAdded) return@runIo
                printingReachable = false
                adapter.submit(Services.all)
            },
        )
    }

    override fun refresh() {
        adapter.submit(Services.all)
        checkPrintingReachability()
    }
}
